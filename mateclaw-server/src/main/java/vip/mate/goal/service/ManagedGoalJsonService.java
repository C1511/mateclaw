package vip.mate.goal.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.execution.evidence.service.JsonArtifactRecipe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Managed JSON is independent of mutable workspace files and cache metadata.
 * Database credentials and the service host are trusted; hashes do not isolate a hostile host. */
@Service
public class ManagedGoalJsonService {
    private final JdbcTemplate jdbc;
    private final GoalJsonAcceptanceService acceptance;
    public ManagedGoalJsonService(JdbcTemplate jdbc, GoalJsonAcceptanceService acceptance) {
        this.jdbc = jdbc;
        this.acceptance = acceptance;
    }

    public record PublishRequest(Long expectedGeneration, String jsonContent) { }
    public record Artifact(String artifactId, String artifactSlot, long generation, String sha256,
                           int byteLength, String producerKind, Instant createdAt, Instant expiresAt) { }
    public record Content(Artifact artifact, String jsonContent) { }
    public record Slot(String artifactSlot, long generation, Artifact current) { }

    @Transactional
    public List<Slot> list(Long goalId, String username) {
        acceptance.authorizedGoal(goalId, username, true);
        return slots(goalId);
    }

    @Transactional
    public Artifact publish(Long goalId, String slot, PublishRequest request, String username) {
        var goal = acceptance.authorizedGoal(goalId, username, true);
        return publishLocked(goal, slot, request, "user", username);
    }

    @Transactional
    public Content read(Long goalId, String artifactId, String username) {
        acceptance.authorizedGoal(goalId, username, true);
        var rows = jdbc.query("SELECT * FROM mate_goal_json_artifact WHERE goal_id=? AND artifact_id=?",
                (r, i) -> new Content(artifact(r), r.getString("json_body")), goalId, artifactId);
        if (rows.size() != 1) throw failure(404, "Managed JSON version not found");
        return rows.getFirst();
    }

    // Caller must hold the authorized goal lock in the same transaction.
    Artifact publishLocked(GoalJsonAcceptanceService.GoalScope goal, String slot, PublishRequest request,
                           String producerKind, String producerId) {
        if (!List.of("active", "paused").contains(goal.status())) throw failure(409, "Goal is no longer writable");
        if (!goal.required() || acceptance.requirements(goal.id()).stream().noneMatch(r -> r.artifactSlot().equals(slot))) {
            throw failure(409, "Only a currently required JSON slot can be published");
        }
        if (request == null || request.expectedGeneration() == null || request.expectedGeneration() < 0) {
            throw failure(400, "expectedGeneration is required (0 for an empty slot)");
        }
        String content = request.jsonContent();
        if (content == null || content.length() > 1_048_576) throw failure(400, "JSON must be at most 1 MiB");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (!content.equals(new String(bytes, StandardCharsets.UTF_8))) throw failure(400, "JSON must be valid UTF-8");
        JsonArtifactRecipe.parseObject(bytes);
        var generations = jdbc.queryForList("SELECT generation FROM mate_goal_json_slot WHERE goal_id=? AND artifact_slot=?", Long.class, goal.id(), slot);
        long generation = generations.isEmpty() ? 0 : generations.getFirst();
        if (request.expectedGeneration() != generation) throw failure(409, "JSON slot generation changed; reload before publishing");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.id());
        if (count == null || count >= 32) throw failure(409, "Managed JSON limit reached (32 versions per goal)");
        long next = Math.addExact(generation, 1);
        // Whole seconds also round-trip through MySQL TIMESTAMP without fractional precision.
        Instant created = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Artifact artifact = new Artifact(UUID.randomUUID().toString(), slot, next, digest(bytes), bytes.length,
                producerKind, created, created.plusSeconds(86_400));
        jdbc.update("""
                INSERT INTO mate_goal_json_artifact
                (artifact_id,goal_id,artifact_slot,generation,json_body,sha256,byte_length,producer_kind,producer_id,created_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, artifact.artifactId(), goal.id(), slot, next, content, artifact.sha256(), bytes.length,
                producerKind, producerId, Timestamp.from(created), Timestamp.from(artifact.expiresAt()));
        if (generation == 0) jdbc.update("INSERT INTO mate_goal_json_slot(goal_id,artifact_slot,generation,artifact_id) VALUES (?,?,?,?)",
                goal.id(), slot, next, artifact.artifactId());
        else jdbc.update("UPDATE mate_goal_json_slot SET generation=?,artifact_id=? WHERE goal_id=? AND artifact_slot=?",
                next, artifact.artifactId(), goal.id(), slot);
        jdbc.update("UPDATE mate_agent_goal SET version=version+1,update_time=CURRENT_TIMESTAMP WHERE id=?", goal.id());
        return artifact;
    }

    List<Slot> slots(Long goalId) {
        return acceptance.requirements(goalId).stream().map(GoalJsonAcceptanceService.Requirement::artifactSlot).distinct().sorted()
                .map(slot -> {
                    var rows = jdbc.query("""
                            SELECT a.* FROM mate_goal_json_slot s JOIN mate_goal_json_artifact a
                            ON a.artifact_id=s.artifact_id AND a.goal_id=s.goal_id AND a.artifact_slot=s.artifact_slot AND a.generation=s.generation
                            WHERE s.goal_id=? AND s.artifact_slot=?
                            """, (r, i) -> artifact(r), goalId, slot);
                    if (rows.isEmpty()) return new Slot(slot, 0, null);
                    var current = rows.getFirst();
                    return new Slot(slot, current.generation(), current);
                }).toList();
    }

    private static Artifact artifact(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Artifact(r.getString("artifact_id"), r.getString("artifact_slot"), r.getLong("generation"),
                r.getString("sha256"), r.getInt("byte_length"), r.getString("producer_kind"),
                r.getTimestamp("created_at").toInstant(), r.getTimestamp("expires_at").toInstant());
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static MateClawException failure(int code, String message) { return new MateClawException(code, message); }
}

package vip.mate.goal.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.agent.context.ChatOrigin;
import java.util.Objects;
import java.time.LocalDateTime;
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

    public record RuntimeView(List<GoalJsonAcceptanceService.Requirement> requirements, List<Slot> slots) { }

    @Transactional
    public RuntimeView listForRuntime(ChatOrigin origin) {
        long goalId = runtimeGoal(origin).goal().id();
        return new RuntimeView(acceptance.requirements(goalId), slots(goalId));
    }

    @Transactional
    public Artifact publishForRuntime(ChatOrigin origin, String slot, PublishRequest request) {
        var runtime = runtimeGoal(origin);
        var result = publishLocked(runtime.goal(), slot, request, runtime.producerKind(), runtime.producerId());
        if (runtime.leaseUntil() != null && !runtime.leaseUntil().isAfter(LocalDateTime.now())) {
            throw failure(409, "Goal attempt lease expired during publication");
        }
        return result;
    }

    record RuntimeScope(GoalJsonAcceptanceService.GoalScope goal, String producerKind,
                        String producerId, LocalDateTime leaseUntil) { }

    // All identity comes from server-created ToolContext, never model arguments.
    // Lock order: enabled user -> conversation -> goal -> continuation -> goal attempt.
    RuntimeScope runtimeGoal(ChatOrigin origin) {
        if (origin == null || origin.conversationId() == null || origin.workspaceId() == null || origin.agentId() == null) {
            throw failure(403, "A bound goal runtime is required");
        }
        var attribution = origin.executionAttribution();
        boolean attempt = attribution != null && (attribution.goalId() != null || attribution.goalAttemptId() != null || attribution.ownerFence() != null);
        if (attempt && (attribution.goalId() == null || attribution.goalAttemptId() == null || attribution.ownerFence() == null)) {
            throw failure(403, "Incomplete goal attempt identity");
        }
        if (origin.cronOrigin() || (attribution != null && attribution.cronRunId() != null)) {
            throw failure(403, "Cron publication is not supported by this goal protocol");
        }
        List<Long> ids = attempt ? List.of(attribution.goalId()) : jdbc.queryForList("""
                SELECT id FROM mate_agent_goal WHERE conversation_id=? AND workspace_id=?
                AND status IN ('active','paused') AND deleted=0
                """, Long.class, origin.conversationId(), origin.workspaceId());
        if (ids.size() != 1) throw failure(409, "Exactly one current goal is required");
        List<String> users;
        if (attempt) {
            users = jdbc.queryForList("SELECT username FROM mate_conversation WHERE conversation_id=? AND deleted=0", String.class, origin.conversationId());
        } else {
            if (origin.requesterUserId() == null) throw failure(403, "Authenticated account identity is required");
            users = jdbc.queryForList("SELECT username FROM mate_user WHERE id=? AND enabled=TRUE AND deleted=0", String.class, origin.requesterUserId());
        }
        if (users.size() != 1) throw failure(403, "Runtime owner unavailable");
        var goal = acceptance.authorizedGoal(ids.getFirst(), users.getFirst(), true);
        if (!Objects.equals(goal.conversationId(), origin.conversationId()) || goal.workspaceId() != origin.workspaceId()
                || goal.agentId() != origin.agentId()) throw failure(403, "Runtime goal scope mismatch");
        if (!attempt) {
            // Recheck the immutable user id after authorizedGoal acquired the user lock.
            Long userId = jdbc.queryForObject("SELECT id FROM mate_user WHERE username=? AND enabled=TRUE AND deleted=0", Long.class, users.getFirst());
            if (!Objects.equals(userId, origin.requesterUserId())) throw failure(403, "Runtime account changed");
            return new RuntimeScope(goal, "account-runtime", String.valueOf(userId), null);
        }
        var continuationLeases = jdbc.query("""
                SELECT lease_owner,current_attempt_id,state,lease_until FROM mate_goal_continuation WHERE goal_id=? FOR UPDATE
                """, (r, i) -> Objects.equals(r.getString("lease_owner"), attribution.ownerFence())
                        && Objects.equals(r.getString("current_attempt_id"), attribution.goalAttemptId())
                        && "running".equals(r.getString("state")) && r.getTimestamp("lease_until") != null
                        ? r.getTimestamp("lease_until").toLocalDateTime() : null, goal.id());
        if (continuationLeases.size() != 1 || continuationLeases.getFirst() == null
                || !continuationLeases.getFirst().isAfter(LocalDateTime.now())) {
            throw failure(409, "Goal continuation owner is no longer current");
        }
        var leases = jdbc.query("""
                SELECT goal_id,conversation_id,lease_token,state,lease_until FROM mate_goal_attempt WHERE attempt_id=? FOR UPDATE
                """, (r, i) -> r.getLong("goal_id") == goal.id()
                        && Objects.equals(r.getString("conversation_id"), goal.conversationId())
                        && Objects.equals(r.getString("lease_token"), attribution.ownerFence())
                        && List.of("claimed", "running").contains(r.getString("state"))
                        ? r.getTimestamp("lease_until").toLocalDateTime() : null, attribution.goalAttemptId());
        if (leases.size() != 1 || leases.getFirst() == null || !leases.getFirst().isAfter(LocalDateTime.now())) {
            throw failure(409, "Goal attempt owner fence is no longer current");
        }
        return new RuntimeScope(goal, "goal-attempt", attribution.goalAttemptId(),
                leases.getFirst().isBefore(continuationLeases.getFirst()) ? leases.getFirst() : continuationLeases.getFirst());
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

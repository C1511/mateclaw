package vip.mate.goal;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.MateClawApplication;
import vip.mate.exception.MateClawException;
import vip.mate.goal.model.*;
import vip.mate.goal.service.GoalJsonAcceptanceService;
import vip.mate.goal.service.GoalService;
import vip.mate.memory.spi.MemoryManager;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MateClawApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:json_acceptance_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key", "spring.main.web-application-type=none",
        "mateclaw.goal.enabled=false", "mateclaw.plugin.enabled=false", "mateclaw.skill.workspace.auto-init=false",
        "mateclaw.skill.workspace.root=${java.io.tmpdir}/mateclaw-json-acceptance-skills-${random.uuid}"
})
class GoalJsonAcceptanceIntegrationTest {
    @MockBean private MemoryManager memory;
    @Autowired private GoalService goals;
    @Autowired private GoalJsonAcceptanceService acceptance;
    @Autowired private vip.mate.goal.service.ManagedGoalJsonService artifacts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private String alice;
    private String bob;

    @BeforeEach void users() {
        alice = "alice-" + UUID.randomUUID();
        bob = "bob-" + UUID.randomUUID();
        for (String user : List.of(alice, bob)) jdbc.update("""
                INSERT INTO mate_user(id,username,password,enabled,role,create_time,update_time,deleted)
                VALUES (?,?,?,TRUE,'user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """, IdWorker.getId(), user, "unused-test-password");
    }

    private GoalEntity goal(boolean persistent) {
        String conversation = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO mate_conversation(id,conversation_id,username,workspace_id,agent_id,create_time,update_time,deleted)
                VALUES (?,?,?,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """, IdWorker.getId(), conversation, alice);
        GoalCreateRequest req = new GoalCreateRequest();
        req.setConversationId(conversation); req.setWorkspaceId(1L); req.setAgentId(1L);
        req.setTitle("A JSON report"); req.setDescription("Produce a managed JSON report"); req.setPersistentExecution(persistent);
        return goals.create(req, alice);
    }

    private GoalJsonAcceptanceService.ConfigureRequest request(long revision, String... fields) {
        return new GoalJsonAcceptanceService.ConfigureRequest(revision, "report", List.of(fields));
    }

    @Test void ownerCanPersistAndReviseRequirementsWithoutAcceptingAStaleEdit() {
        GoalEntity goal = goal(false);
        assertFalse(acceptance.get(goal.getId(), alice).required());
        var first = acceptance.configure(goal.getId(), "report-fields", request(0, "summary"), alice);
        assertEquals(1, first.revision());
        assertTrue(goals.toResponse(goals.getById(goal.getId())).isJsonAcceptanceRequired());
        assertEquals(List.of("summary"), acceptance.get(goal.getId(), alice).requirements().getFirst().requiredFields());
        var second = acceptance.configure(goal.getId(), "report-fields", request(1, "summary", "sources"), alice);
        assertEquals(2, second.revision());
        assertThrows(MateClawException.class, () -> acceptance.configure(goal.getId(), "report-fields", request(1, "forged"), alice));
        assertEquals(List.of("summary", "sources"), acceptance.get(goal.getId(), alice).requirements().getFirst().requiredFields());
        assertEquals(2, acceptance.configure(goal.getId(), "report-fields", request(2, "summary", "sources"), alice).revision());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectedContractBlocksBothSyntheticExplicitAndAutomaticCompletion(boolean automatic) {
        GoalEntity goal = goal(false);
        goals.appendCriterion(goal.getId(), "write report", alice);
        var forged = new GoalEvaluationResult(1, "report saved and checked", "completed", true, "fixture", 1, 0,
                List.of(new GoalChecklistVerdict.CriterionVerdict("C1", true, "I verified the JSON")), null);
        goals.recordEvaluation(goal.getId(), forged, 1, 1);
        acceptance.configure(goal.getId(), "report-fields", request(0, "summary"), alice);
        assertThrows(MateClawException.class, () -> {
            if (automatic) goals.markEvaluatedCompleted(goal.getId(), forged);
            else goals.markCompleted(goal.getId(), forged);
        });
        assertEquals(GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus());
        assertTrue(goals.listEvents(goal.getId(), 30).stream().noneMatch(e -> "completed".equals(e.getEventType())));
    }

    @Test void unselectedLegacyCompletionRemainsCompatible() {
        GoalEntity goal = goal(false);
        assertEquals(GoalStatus.COMPLETED, goals.markCompleted(goal.getId(), null).getStatus());
        assertThrows(MateClawException.class, () -> acceptance.configure(goal.getId(), "late", request(0, "summary"), alice));
    }

    @Test void unknownDisabledAndOtherUsersCannotConfigureOrReadEvenForSystemConversations() {
        GoalEntity goal = goal(false);
        assertThrows(MateClawException.class, () -> acceptance.configure(goal.getId(), "r", request(0, "summary"), null));
        assertThrows(MateClawException.class, () -> acceptance.configure(goal.getId(), "r", request(0, "summary"), bob));
        assertThrows(MateClawException.class, () -> acceptance.get(goal.getId(), bob));
        jdbc.update("UPDATE mate_user SET enabled=FALSE WHERE username=?", alice);
        assertThrows(MateClawException.class, () -> acceptance.configure(goal.getId(), "r", request(0, "summary"), alice));
        jdbc.update("UPDATE mate_conversation SET username='system' WHERE conversation_id=?", goal.getConversationId());
        assertThrows(MateClawException.class, () -> acceptance.configure(goal.getId(), "r", request(0, "summary"), "unknown-account"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_requirement WHERE goal_id=?", Integer.class, goal.getId()));
        assertFalse(goals.getById(goal.getId()).isJsonAcceptanceRequired());
    }

    @Test void rollbackDoesNotLeaveAContractOrEnableFlag() {
        GoalEntity goal = goal(false);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
            status.setRollbackOnly();
        });
        assertFalse(acceptance.get(goal.getId(), alice).required());
        assertTrue(acceptance.get(goal.getId(), alice).requirements().isEmpty());
    }

    @Test void competingUserEditsCannotBothCommitTheSameExpectedRevision() throws Exception {
        GoalEntity goal = goal(false);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> edit = () -> {
                start.await();
                try { acceptance.configure(goal.getId(), "r", request(0, "summary"), alice); return true; }
                catch (MateClawException conflict) {
                    assertTrue(conflict.getMessage().contains("revision changed"));
                    return false;
                }
            };
            var first = workers.submit(edit); var second = workers.submit(edit); start.countDown();
            assertNotEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, acceptance.get(goal.getId(), alice).requirements().size());
        assertEquals(1, acceptance.get(goal.getId(), alice).requirements().getFirst().revision());
    }

    @Test void malformedAndUnboundedContractsAreRejectedWithoutEnablingTheGoal() {
        GoalEntity goal = goal(false);
        assertThrows(MateClawException.class, () -> acceptance.configure(goal.getId(), "../r", request(0, "summary"), alice));
        assertThrows(MateClawException.class, () -> acceptance.configure(goal.getId(), "r", request(0, "summary", "summary"), alice));
        assertFalse(goals.getById(goal.getId()).isJsonAcceptanceRequired());
        for (int i=0; i<8; i++) acceptance.configure(goal.getId(), "r"+i, request(0, "summary"), alice);
        assertThrows(MateClawException.class, () -> acceptance.configure(goal.getId(), "overflow", request(0, "summary"), alice));
        assertEquals(8, acceptance.get(goal.getId(), alice).requirements().size());
    }
    private vip.mate.goal.service.ManagedGoalJsonService.PublishRequest publication(long generation, String content) {
        return new vip.mate.goal.service.ManagedGoalJsonService.PublishRequest(generation, content);
    }

    @Test void managedVersionsPreserveExactBytesAndRejectStaleOverwriteAndForeignReads() {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        assertEquals(0, artifacts.list(goal.getId(), alice).getFirst().generation());
        String original = "{ \"summary\": false, \"count\": 0 }";
        var first = artifacts.publish(goal.getId(), "report", publication(0, original), alice);
        assertEquals(1, first.generation());
        assertEquals(original, artifacts.read(goal.getId(), first.artifactId(), alice).jsonContent());
        try {
            assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8))), first.sha256());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        assertEquals(first, artifacts.read(goal.getId(), first.artifactId(), alice).artifact());
        assertEquals(86_400, java.time.Duration.between(first.createdAt(), first.expiresAt()).toSeconds());
        var second = artifacts.publish(goal.getId(), "report", publication(1, "{\"summary\":\"next\"}"), alice);
        assertNotEquals(first.artifactId(), second.artifactId());
        assertEquals(second.artifactId(), artifacts.list(goal.getId(), alice).getFirst().current().artifactId());
        assertEquals(original, artifacts.read(goal.getId(), first.artifactId(), alice).jsonContent());
        assertThrows(MateClawException.class, () -> artifacts.publish(goal.getId(), "report", publication(1, "{}"), alice));
        assertThrows(MateClawException.class, () -> artifacts.read(goal.getId(), first.artifactId(), bob));
        assertThrows(MateClawException.class, () -> artifacts.read(goal(false).getId(), first.artifactId(), alice));
    }

    @Test void managedPublicationRejectsInvalidObjectsAndUnrequiredSlotsWithoutCreatingVersions() {
        GoalEntity goal = goal(false);
        assertThrows(MateClawException.class, () -> artifacts.publish(goal.getId(), "report", publication(0, "{}"), alice));
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        for (String invalid : List.of("[]", "null", "{\"a\":1,\"a\":2}", "{} {}", "[".repeat(33)+"]".repeat(33), "{\"a\":\""+"中".repeat(350000)+"\"}")) {
            assertThrows(MateClawException.class, () -> artifacts.publish(goal.getId(), "report", publication(0, invalid), alice));
        }
        assertThrows(MateClawException.class, () -> artifacts.publish(goal.getId(), "other", publication(0, "{}"), alice));
        assertThrows(MateClawException.class, () -> artifacts.publish(goal.getId(), "report", publication(0, "{}"), bob));
        assertEquals(0, artifacts.list(goal.getId(), alice).getFirst().generation());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()));
    }

    @Test void managedPublicationRollsBackBodyPointerAndGoalVersionTogether() {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var version = goals.getById(goal.getId()).getVersion();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            artifacts.publish(goal.getId(), "report", publication(0, "{}"), alice);
            status.setRollbackOnly();
        });
        assertEquals(version, goals.getById(goal.getId()).getVersion());
        assertEquals(0, artifacts.list(goal.getId(), alice).getFirst().generation());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()));
    }

    @Test void competingPublishersHaveExactlyOneCurrentGeneration() throws Exception {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> publish = () -> {
                start.await();
                try { artifacts.publish(goal.getId(), "report", publication(0, "{}"), alice); return true; }
                catch (MateClawException conflict) {
                    assertTrue(conflict.getMessage().contains("generation changed"));
                    return false;
                }
            };
            var first = workers.submit(publish); var second = workers.submit(publish); start.countDown();
            assertNotEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, artifacts.list(goal.getId(), alice).getFirst().generation());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()));
    }

    @Test void quotaAndTerminalStateNeverReuseOrMutateOldVersions() {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var first = artifacts.publish(goal.getId(), "report", publication(0, "{}"), alice);
        for (int i=1; i<32; i++) artifacts.publish(goal.getId(), "report", publication(i, "{}"), alice);
        assertThrows(MateClawException.class, () -> artifacts.publish(goal.getId(), "report", publication(32, "{}"), alice));
        assertEquals(32, artifacts.list(goal.getId(), alice).getFirst().generation());
        assertEquals("{}", artifacts.read(goal.getId(), first.artifactId(), alice).jsonContent());
        jdbc.update("UPDATE mate_agent_goal SET status='abandoned' WHERE id=?", goal.getId());
        assertThrows(MateClawException.class, () -> artifacts.publish(goal.getId(), "report", publication(32, "{}"), alice));
    }

    @Test void managedJsonAboveSmallTextCapacityRemainsExactAndDisabledOwnerLosesAccess() {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        String content = "{\"summary\":\"" + "中".repeat(30_000) + "\"}";
        var stored = artifacts.publish(goal.getId(), "report", publication(0, content), alice);
        assertTrue(stored.byteLength() > 65_535);
        assertEquals(content, artifacts.read(goal.getId(), stored.artifactId(), alice).jsonContent());
        jdbc.update("UPDATE mate_user SET enabled=FALSE WHERE username=?", alice);
        assertThrows(MateClawException.class, () -> artifacts.read(goal.getId(), stored.artifactId(), alice));
        assertThrows(MateClawException.class, () -> artifacts.publish(goal.getId(), "report", publication(1, "{}"), alice));
    }

}

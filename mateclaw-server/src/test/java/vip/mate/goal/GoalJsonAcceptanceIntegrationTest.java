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
    @Autowired private vip.mate.tool.builtin.ManagedGoalJsonTool managedTool;
    @Autowired private vip.mate.goal.service.GoalJsonBindingService bindings;
    @Autowired private vip.mate.goal.service.GoalContinuationStore continuations;
    @Autowired private vip.mate.goal.service.GoalRunCoordinator coordinator;

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

    @Test void conversationHistoryPreservesPausedAndCompletedGoalsWithExclusivePaging() {
        GoalEntity paused = goal(false);
        acceptance.configure(paused.getId(), "r", request(0, "summary"), alice);
        goals.pause(paused.getId(), alice);
        GoalCreateRequest next = new GoalCreateRequest();
        next.setConversationId(paused.getConversationId()); next.setWorkspaceId(1L); next.setAgentId(1L);
        next.setTitle("Next report"); next.setDescription("History fixture"); next.setPersistentExecution(false);
        GoalEntity completed = goals.create(next, alice);
        goals.markCompleted(completed.getId(), null);
        GoalEntity deleted = goals.create(next, alice);
        jdbc.update("UPDATE mate_agent_goal SET deleted=1 WHERE id=?", deleted.getId());
        goal(false); // A newer goal in another conversation must not enter this page.
        var first = goals.listByConversation(paused.getConversationId(), null, 1);
        assertEquals(List.of(completed.getId()), first.stream().map(GoalEntity::getId).toList());
        assertEquals(GoalStatus.COMPLETED, first.getFirst().getStatus());
        var second = goals.listByConversation(paused.getConversationId(), completed.getId(), 1);
        assertEquals(List.of(paused.getId()), second.stream().map(GoalEntity::getId).toList());
        assertEquals(GoalStatus.PAUSED, second.getFirst().getStatus());
        assertTrue(second.getFirst().isJsonAcceptanceRequired());
        assertTrue(goals.listByConversation(paused.getConversationId(), paused.getId(), 20).isEmpty());
        assertNull(goals.findActiveByConversation(paused.getConversationId()), "History must not revive an inactive goal");
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

    private vip.mate.agent.context.ChatOrigin accountOrigin(GoalEntity goal, String username) {
        Long userId = jdbc.queryForObject("SELECT id FROM mate_user WHERE username=?", Long.class, username);
        return vip.mate.agent.context.ChatOrigin.web(goal.getConversationId(), username, goal.getWorkspaceId(), null, null, userId)
                .withAgent(goal.getAgentId());
    }

    @Test void persistedQueuedAccountCanPublishButCannotBecomeARecreatedUsername() {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var queue = new vip.mate.channel.web.ConversationInputQueueStore(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        Long accountId = jdbc.queryForObject("SELECT id FROM mate_user WHERE username=?", Long.class, alice);
        var input = queue.enqueue(goal.getConversationId(), 1L, alice, "publish", List.of(), accountId, java.time.LocalDateTime.now());
        var restored = new vip.mate.channel.web.ConversationInputQueueStore(jdbc, new com.fasterxml.jackson.databind.ObjectMapper()).get(input.id());
        var origin = vip.mate.agent.context.ChatOrigin.web(restored.conversationId(), restored.createdBy(), 1L, null, null, restored.requesterUserId()).withAgent(restored.agentId());
        assertEquals(1, artifacts.publishForRuntime(origin, "report", publication(0, "{\"summary\":false}")).generation());
        jdbc.update("DELETE FROM mate_user WHERE id=?", accountId);
        jdbc.update("INSERT INTO mate_user(id,username,password,enabled,role,create_time,update_time,deleted) VALUES (?,?,?,TRUE,'user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", IdWorker.getId(), alice, "replacement-fixture");
        assertThrows(MateClawException.class, () -> artifacts.publishForRuntime(origin, "report", publication(1, "{}")));
        var legacy = queue.enqueue(goal.getConversationId(), 1L, alice, "legacy", List.of(), java.time.LocalDateTime.now());
        var unasserted = vip.mate.agent.context.ChatOrigin.web(legacy.conversationId(), legacy.createdBy(), 1L, null, null, legacy.requesterUserId()).withAgent(1L);
        assertThrows(MateClawException.class, () -> artifacts.publishForRuntime(unasserted, "report", publication(1, "{}")));
    }

    @Test void actualManagedToolUsesServerAccountContextAndExposesRequirements() throws Exception {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var origin = accountOrigin(goal, alice);
        var callbacks = org.springframework.ai.support.ToolCallbacks.from(managedTool);
        assertEquals(3, callbacks.length);
        for (var callback : callbacks) {
            String schema = callback.getToolDefinition().inputSchema();
            assertFalse(schema.contains("\"goalId\""));
            assertFalse(schema.contains("\"ownerFence\""));
            assertFalse(schema.contains("\"context\""));
            assertFalse(schema.contains("\"requiredFields\""));
        }
        assertTrue(managedTool.getManagedGoalJsonSlots(origin.toToolContext()).contains("summary"));
        String result = managedTool.publishManagedGoalJson("report", "0", "{\"summary\":false}", origin.toToolContext());
        assertTrue(result.contains("account-runtime"));
        assertTrue(result.contains("\"generation\":\"1\""));
        assertThrows(MateClawException.class, () -> managedTool.publishManagedGoalJson("report", "1", "{}", accountOrigin(goal, bob).toToolContext()));
        assertThrows(MateClawException.class, () -> artifacts.publishForRuntime(origin.withAgent(999L), "report", publication(1, "{}")));
        assertThrows(MateClawException.class, () -> artifacts.publishForRuntime(origin.withWorkspace(999L, null), "report", publication(1, "{}")));
        var anonymous = vip.mate.agent.context.ChatOrigin.web(goal.getConversationId(), alice, 1L, null).withAgent(1L);
        assertThrows(MateClawException.class, () -> artifacts.publishForRuntime(anonymous, "report", publication(1, "{}")));
        assertEquals(1, artifacts.list(goal.getId(), alice).getFirst().generation());
    }

    private vip.mate.goal.service.GoalRunCoordinator.ClaimedRun claimed(GoalEntity goal) {
        jdbc.update("UPDATE mate_agent_goal SET auto_followup_enabled=TRUE WHERE id=?", goal.getId());
        var now = java.time.LocalDateTime.now();
        continuations.discover(now);
        var run = coordinator.claim(continuations.get(goal.getId()), goals.getById(goal.getId()), now);
        assertNotNull(run);
        assertTrue(coordinator.markRunning(run, now));
        return run;
    }

    private vip.mate.agent.context.ChatOrigin attemptOrigin(GoalEntity goal, vip.mate.goal.service.GoalRunCoordinator.ClaimedRun run) {
        return vip.mate.agent.context.ChatOrigin.web(goal.getConversationId(), alice, 1L, null).withAgent(1L)
                .withExecutionAttribution(new vip.mate.agent.context.ExecutionAttribution(goal.getId(), run.attempt().id(), null, null, run.attempt().leaseToken()));
    }

    @Test void actualSchedulerOwnerCanPublishButWrongExpiredAndSupersededOwnersCannot() {
        GoalEntity goal = goal(true);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var run = claimed(goal);
        var origin = attemptOrigin(goal, run);
        var version = artifacts.publishForRuntime(origin, "report", publication(0, "{}"));
        assertEquals("goal-attempt", version.producerKind());
        var wrong = origin.withExecutionAttribution(new vip.mate.agent.context.ExecutionAttribution(goal.getId(), run.attempt().id(), null, null, "forged"));
        assertThrows(MateClawException.class, () -> artifacts.publishForRuntime(wrong, "report", publication(1, "{}")));
        var foreign = origin.withConversationId(goal(false).getConversationId());
        assertThrows(MateClawException.class, () -> artifacts.publishForRuntime(foreign, "report", publication(1, "{}")));
        jdbc.update("UPDATE mate_goal_attempt SET lease_until_epoch_second=? WHERE attempt_id=?", java.time.Instant.now().minusSeconds(1).getEpochSecond(), run.attempt().id());
        assertThrows(MateClawException.class, () -> artifacts.publishForRuntime(origin, "report", publication(1, "{}")));
        jdbc.update("UPDATE mate_goal_attempt SET lease_until_epoch_second=? WHERE attempt_id=?", java.time.Instant.now().plusSeconds(300).getEpochSecond(), run.attempt().id());
        jdbc.update("UPDATE mate_goal_continuation SET current_attempt_id=? WHERE goal_id=?", UUID.randomUUID().toString(), goal.getId());
        assertThrows(MateClawException.class, () -> artifacts.publishForRuntime(origin, "report", publication(1, "{}")));
        assertEquals(1, artifacts.list(goal.getId(), alice).getFirst().generation());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mate_goal_attempt", "mate_goal_continuation"})
    void expiredScheduledOwnerCannotRenewItsWayBackIntoJsonPublication(String table) {
        GoalEntity goal = goal(true);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var run = claimed(goal);
        var now = java.time.LocalDateTime.now();
        jdbc.update("UPDATE " + table + " SET lease_until_epoch_second=? WHERE goal_id=?", java.time.Instant.now().minusSeconds(1).getEpochSecond(), goal.getId());
        assertFalse(coordinator.renew(run, now), "An expired owner must obtain a new fenced attempt");
        assertFalse(coordinator.checkpoint(run, "resolved", "tool_completed", null, now));
        assertFalse(coordinator.settle(run, new SegmentOutcome.Complete("stale owner"), now));
        assertThrows(MateClawException.class, () -> artifacts.publishForRuntime(attemptOrigin(goal, run), "report", publication(0, "{}")));
    }

    @Test void disabledOwnerAndIncompleteAttributionCannotUseSchedulerFallback() {
        GoalEntity goal = goal(true);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var run = claimed(goal);
        var origin = attemptOrigin(goal, run);
        var incomplete = origin.withExecutionAttribution(new vip.mate.agent.context.ExecutionAttribution(goal.getId(), null, null, null, null));
        assertThrows(MateClawException.class, () -> artifacts.publishForRuntime(incomplete, "report", publication(0, "{}")));
        jdbc.update("UPDATE mate_user SET enabled=FALSE WHERE username=?", alice);
        assertThrows(MateClawException.class, () -> artifacts.publishForRuntime(origin, "report", publication(0, "{}")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()));
    }

    @Test void schedulerSettlementAndPublicationSerializeWithoutLateOwnerWrites() throws Exception {
        GoalEntity goal = goal(true);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var run = claimed(goal);
        var origin = attemptOrigin(goal, run);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var publish = workers.submit(() -> {
                start.await();
                try { artifacts.publishForRuntime(origin, "report", publication(0, "{}")); return true; }
                catch (MateClawException ended) { return false; }
            });
            var settle = workers.submit(() -> {
                start.await();
                return coordinator.settle(run, new SegmentOutcome.Continue("fixture done"), java.time.LocalDateTime.now());
            });
            start.countDown();
            boolean published = publish.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(settle.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(published ? 1 : 0, artifacts.list(goal.getId(), alice).getFirst().generation());
            assertThrows(MateClawException.class, () -> artifacts.publishForRuntime(origin, "report", publication(published ? 1 : 0, "{}")));
        }
    }

    private vip.mate.goal.service.GoalJsonBindingService.CheckRequest checkRequest(long revision, vip.mate.goal.service.ManagedGoalJsonService.Artifact version) {
        return new vip.mate.goal.service.GoalJsonBindingService.CheckRequest(revision, version.artifactId(), version.generation());
    }

    @Test void trustedRecipeBindsExactCurrentVersionAndRejectsTextualSubstitutes() throws Exception {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        assertEquals("NO_ARTIFACT", bindings.state(goal.getId(), alice).getFirst().status());
        var bad = artifacts.publish(goal.getId(), "report", publication(0, "{\"summary\":null,\"claim\":\"PASS\"}"), alice);
        var rejected = bindings.check(goal.getId(), "r", checkRequest(1, bad), alice);
        assertFalse(rejected.acceptanceEligible());
        assertEquals(List.of("summary"), rejected.missingFields());
        var good = artifacts.publish(goal.getId(), "report", publication(1, "{\"summary\":false}"), alice);
        assertEquals("SUPERSEDED", bindings.state(goal.getId(), alice).getFirst().status());
        assertThrows(MateClawException.class, () -> bindings.check(goal.getId(), "r", checkRequest(1, bad), alice));
        var result = managedTool.checkManagedGoalJson("r", "1", good.artifactId(), "2", accountOrigin(goal, alice).toToolContext());
        assertTrue(result.contains("\"acceptanceEligible\":true"));
        assertTrue(bindings.state(goal.getId(), alice).getFirst().acceptanceEligible());
        assertEquals(good.artifactId(), bindings.state(goal.getId(), alice).getFirst().artifactId());
        assertThrows(MateClawException.class, () -> bindings.check(goal.getId(), "r", checkRequest(1, good), bob));
    }

    @Test void editedRequirementsAndGoalDefinitionInvalidatePreviouslyMatchingBindings() {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var version = artifacts.publish(goal.getId(), "report", publication(0, "{\"summary\":true,\"sources\":[]}"), alice);
        assertTrue(bindings.check(goal.getId(), "r", checkRequest(1, version), alice).acceptanceEligible());
        acceptance.configure(goal.getId(), "r", request(1, "summary", "sources"), alice);
        assertEquals("REQUIREMENT_CHANGED", bindings.state(goal.getId(), alice).getFirst().status());
        assertThrows(MateClawException.class, () -> bindings.check(goal.getId(), "r", checkRequest(1, version), alice));
        assertTrue(bindings.check(goal.getId(), "r", checkRequest(2, version), alice).acceptanceEligible());
        GoalUpdateRequest edit = new GoalUpdateRequest(); edit.setDescription("A revised report definition");
        goals.update(goal.getId(), edit, alice);
        assertEquals("GOAL_CHANGED", bindings.state(goal.getId(), alice).getFirst().status());
        assertTrue(bindings.check(goal.getId(), "r", checkRequest(2, version), alice).acceptanceEligible());
    }

    @Test void expiredAndCorruptBodiesNeverRemainEligible() {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var version = artifacts.publish(goal.getId(), "report", publication(0, "{\"summary\":true}"), alice);
        bindings.check(goal.getId(), "r", checkRequest(1, version), alice);
        // Direct DB mutation is a corruption/clock fixture, not a supported publication API.
        jdbc.update("UPDATE mate_goal_json_artifact SET json_body='{}' WHERE artifact_id=?", version.artifactId());
        assertEquals("CORRUPT", bindings.state(goal.getId(), alice).getFirst().status());
        assertThrows(MateClawException.class, () -> bindings.check(goal.getId(), "r", checkRequest(1, version), alice));
        var next = artifacts.publish(goal.getId(), "report", publication(1, "{\"summary\":true}"), alice);
        bindings.check(goal.getId(), "r", checkRequest(1, next), alice);
        jdbc.update("UPDATE mate_goal_json_artifact SET expires_epoch_second=? WHERE artifact_id=?", java.time.Instant.now().minusSeconds(1).getEpochSecond(), next.artifactId());
        assertEquals("EXPIRED", bindings.state(goal.getId(), alice).getFirst().status());
        assertThrows(MateClawException.class, () -> bindings.check(goal.getId(), "r", checkRequest(1, next), alice));
    }

    @Test void rollbackRemovesBindingAndDoesNotAdvanceGoalVersion() {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var version = artifacts.publish(goal.getId(), "report", publication(0, "{\"summary\":true}"), alice);
        long before = goals.getById(goal.getId()).getVersion();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertTrue(bindings.check(goal.getId(), "r", checkRequest(1, version), alice).acceptanceEligible());
            status.setRollbackOnly();
        });
        assertEquals("UNBOUND", bindings.state(goal.getId(), alice).getFirst().status());
        assertEquals(before, goals.getById(goal.getId()).getVersion().longValue());
    }

    @Test void racingRequirementEditCannotLeaveAnEligibleOldBinding() throws Exception {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var version = artifacts.publish(goal.getId(), "report", publication(0, "{\"summary\":true}"), alice);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var check = workers.submit(() -> {
                start.await();
                try { bindings.check(goal.getId(), "r", checkRequest(1, version), alice); return true; }
                catch (MateClawException changed) { return false; }
            });
            var edit = workers.submit(() -> { start.await(); return acceptance.configure(goal.getId(), "r", request(1, "sources"), alice); });
            start.countDown();
            check.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(2, edit.get(10, java.util.concurrent.TimeUnit.SECONDS).revision());
        }
        assertFalse(bindings.state(goal.getId(), alice).getFirst().acceptanceEligible());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void currentManagedBindingsPermitBothCompletionPaths(boolean persistent, boolean automatic) {
        GoalEntity goal = goal(persistent);
        goals.appendCriterion(goal.getId(), "Produce the report", alice);
        var evaluation = new GoalEvaluationResult(1, "report checked", "completed", true, "fixture", 1, 0,
                List.of(new GoalChecklistVerdict.CriterionVerdict("C1", true, "fixture semantic verdict")), null);
        goals.recordEvaluation(goal.getId(), evaluation, 1, 1);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var version = artifacts.publish(goal.getId(), "report", publication(0, "{\"summary\":true}"), alice);
        bindings.check(goal.getId(), "r", checkRequest(1, version), alice);
        GoalEntity completed = assertDoesNotThrow(() -> automatic
                ? goals.markEvaluatedCompleted(goal.getId(), evaluation) : goals.markCompleted(goal.getId(), evaluation));
        assertEquals(GoalStatus.COMPLETED, completed.getStatus());
        assertEquals(1, goals.listEvents(goal.getId(), 30).stream().filter(e -> "completed".equals(e.getEventType())).count());
        assertEquals(GoalStatus.COMPLETED, goals.markCompleted(goal.getId(), evaluation).getStatus());
        assertEquals(1, goals.listEvents(goal.getId(), 30).stream().filter(e -> "completed".equals(e.getEventType())).count());
        assertThrows(MateClawException.class, () -> artifacts.publish(goal.getId(), "report", publication(1, "{}"), alice));
    }

    @Test void allCurrentRequirementsMustBindBeforeCompletion() {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        acceptance.configure(goal.getId(), "s", new GoalJsonAcceptanceService.ConfigureRequest(0L, "sources", List.of("items")), alice);
        var report = artifacts.publish(goal.getId(), "report", publication(0, "{\"summary\":true}"), alice);
        bindings.check(goal.getId(), "r", checkRequest(1, report), alice);
        assertThrows(MateClawException.class, () -> goals.markCompleted(goal.getId(), null));
        var sources = artifacts.publish(goal.getId(), "sources", publication(0, "{\"items\":[]}"), alice);
        assertThrows(MateClawException.class, () -> goals.markCompleted(goal.getId(), null));
        bindings.check(goal.getId(), "s", checkRequest(1, sources), alice);
        assertEquals(GoalStatus.COMPLETED, goals.markCompleted(goal.getId(), null).getStatus());
        String proof = goals.listEvents(goal.getId(), 30).stream().filter(e -> "completed".equals(e.getEventType())).findFirst().orElseThrow().getDetailJson();
        assertTrue(proof.contains(report.artifactId())); assertTrue(proof.contains(sources.artifactId()));
    }

    @Test void completionRejectsEveryInvalidationAndFreshBindingRestoresSuccess() {
        for (String invalidation : List.of("requirement", "definition", "superseded", "expired", "corrupt")) {
            GoalEntity goal = goal(false);
            acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
            var version = artifacts.publish(goal.getId(), "report", publication(0, "{\"summary\":true,\"sources\":[]}"), alice);
            bindings.check(goal.getId(), "r", checkRequest(1, version), alice);
            long revision = 1;
            switch (invalidation) {
                case "requirement" -> { acceptance.configure(goal.getId(), "r", request(1, "summary", "sources"), alice); revision = 2; }
                case "definition" -> { GoalUpdateRequest edit = new GoalUpdateRequest(); edit.setDescription("new definition"); goals.update(goal.getId(), edit, alice); }
                case "superseded" -> version = artifacts.publish(goal.getId(), "report", publication(1, "{\"summary\":true}"), alice);
                case "expired" -> jdbc.update("UPDATE mate_goal_json_artifact SET expires_epoch_second=? WHERE artifact_id=?", java.time.Instant.now().minusSeconds(1).getEpochSecond(), version.artifactId());
                case "corrupt" -> jdbc.update("UPDATE mate_goal_json_artifact SET json_body='{}' WHERE artifact_id=?", version.artifactId());
            }
            assertThrows(MateClawException.class, () -> goals.markCompleted(goal.getId(), null), invalidation);
            assertEquals(GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus());
            if (List.of("expired", "corrupt").contains(invalidation)) version = artifacts.publish(goal.getId(), "report", publication(1, "{\"summary\":true}"), alice);
            bindings.check(goal.getId(), "r", checkRequest(revision, version), alice);
            assertEquals(GoalStatus.COMPLETED, goals.markCompleted(goal.getId(), null).getStatus());
        }
    }

    @Test void completionRollbackPreservesActiveGoalAndDoesNotEmitSuccessOrMemory() {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var version = artifacts.publish(goal.getId(), "report", publication(0, "{\"summary\":true}"), alice);
        bindings.check(goal.getId(), "r", checkRequest(1, version), alice);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertEquals(GoalStatus.COMPLETED, goals.markCompleted(goal.getId(), null).getStatus());
            status.setRollbackOnly();
        });
        assertEquals(GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus());
        assertTrue(goals.listEvents(goal.getId(), 30).stream().noneMatch(e -> "completed".equals(e.getEventType())));
        org.mockito.Mockito.verify(memory, org.mockito.Mockito.never()).syncAll(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertEquals(GoalStatus.COMPLETED, goals.markCompleted(goal.getId(), null).getStatus());
    }

    @Test void newPublicationAndCompletionCannotBothWinUsingAnOldBinding() throws Exception {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var version = artifacts.publish(goal.getId(), "report", publication(0, "{\"summary\":true}"), alice);
        bindings.check(goal.getId(), "r", checkRequest(1, version), alice);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var publish = workers.submit(() -> {
                start.await();
                try { artifacts.publish(goal.getId(), "report", publication(1, "{}"), alice); return true; }
                catch (MateClawException terminal) { return false; }
            });
            var complete = workers.submit(() -> {
                start.await();
                try { goals.markCompleted(goal.getId(), null); return true; }
                catch (MateClawException stale) { return false; }
            });
            start.countDown();
            boolean published = publish.get(10, java.util.concurrent.TimeUnit.SECONDS);
            boolean completed = complete.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(published, completed);
            assertEquals(completed ? GoalStatus.COMPLETED : GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus());
            assertEquals(published ? 2 : 1, artifacts.list(goal.getId(), alice).getFirst().generation());
        }
    }

    @Test void actualExplicitCompletionToolCannotBypassBindingsButCanCompleteAfterCheck() {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var properties = new vip.mate.goal.config.GoalProperties(); properties.setEnabled(true);
        var tool = new vip.mate.tool.builtin.GoalManagementTool(goals, properties, new com.fasterxml.jackson.databind.ObjectMapper(), null);
        var context = accountOrigin(goal, alice).toToolContext();
        assertTrue(tool.completeGoal(context).contains("error"));
        assertEquals(GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus());
        var version = artifacts.publish(goal.getId(), "report", publication(0, "{\"summary\":true}"), alice);
        bindings.check(goal.getId(), "r", checkRequest(1, version), alice);
        assertTrue(tool.completeGoal(context).contains("\"status\":\"completed\""));
        assertEquals(GoalStatus.COMPLETED, goals.getById(goal.getId()).getStatus());
    }

    @Test void expiredRuntimeCannotCompleteEvenWithCurrentPassingBindings() {
        GoalEntity goal = goal(true);
        goals.appendCriterion(goal.getId(), "report", alice);
        var evaluation = new GoalEvaluationResult(1, "checked", "completed", true, "fixture", 1, 0,
                List.of(new GoalChecklistVerdict.CriterionVerdict("C1", true, "semantic fixture")), null);
        goals.recordEvaluation(goal.getId(), evaluation, 1, 1);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var run = claimed(goal);
        var origin = attemptOrigin(goal, run);
        var version = artifacts.publishForRuntime(origin, "report", publication(0, "{\"summary\":true}"));
        bindings.checkForRuntime(origin, "r", checkRequest(1, version));
        var properties = new vip.mate.goal.config.GoalProperties(); properties.setEnabled(true);
        var tool = new vip.mate.tool.builtin.GoalManagementTool(goals, properties, new com.fasterxml.jackson.databind.ObjectMapper(), null);
        jdbc.update("UPDATE mate_goal_attempt SET lease_until_epoch_second=? WHERE attempt_id=?", java.time.Instant.now().minusSeconds(1).getEpochSecond(), run.attempt().id());
        assertTrue(tool.completeGoal(origin.toToolContext()).contains("error"));
        assertEquals(GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus());
        jdbc.update("UPDATE mate_goal_attempt SET lease_until_epoch_second=? WHERE attempt_id=?", java.time.Instant.now().plusSeconds(60).getEpochSecond(), run.attempt().id());
        assertTrue(tool.completeGoal(origin.toToolContext()).contains("\"status\":\"completed\""));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void runtimeCompletionRejectsMissingForeignAndRevokedIdentity(boolean automatic) {
        GoalEntity goal = goal(false);
        goals.appendCriterion(goal.getId(), "report", alice);
        var evaluation = new GoalEvaluationResult(1, "checked", "completed", true, "fixture", 1, 0,
                List.of(new GoalChecklistVerdict.CriterionVerdict("C1", true, "semantic fixture")), null);
        goals.recordEvaluation(goal.getId(), evaluation, 1, 1);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var version = artifacts.publish(goal.getId(), "report", publication(0, "{\"summary\":true}"), alice);
        bindings.check(goal.getId(), "r", checkRequest(1, version), alice);
        var owner = accountOrigin(goal, alice);
        for (var origin : List.of(vip.mate.agent.context.ChatOrigin.EMPTY, accountOrigin(goal, bob),
                owner.withAgent(999L), owner.withWorkspace(999L, null), accountOrigin(goal(false), alice))) {
            assertThrows(MateClawException.class, () -> runtimeComplete(goal, evaluation, origin, automatic));
        }
        jdbc.update("UPDATE mate_user SET enabled=FALSE WHERE username=?", alice);
        assertThrows(MateClawException.class, () -> runtimeComplete(goal, evaluation, owner, automatic));
        assertEquals(GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus());
        jdbc.update("UPDATE mate_user SET enabled=TRUE WHERE username=?", alice);
        assertEquals(GoalStatus.COMPLETED, runtimeComplete(goal, evaluation, owner, automatic).getStatus());
    }

    private GoalEntity runtimeComplete(GoalEntity goal, GoalEvaluationResult evaluation, vip.mate.agent.context.ChatOrigin origin, boolean automatic) {
        return automatic ? goals.markRuntimeEvaluatedCompleted(goal.getId(), evaluation, origin)
                : goals.markRuntimeCompleted(goal.getId(), evaluation, origin);
    }

    @Test void userAndRuntimeSnapshotsShareCurrentRequirementsVersionsAndChecks() {
        GoalEntity goal = goal(false);
        acceptance.configure(goal.getId(), "r", request(0, "summary"), alice);
        var first = bindings.snapshot(goal.getId(), alice);
        assertTrue(first.required()); assertEquals("active", first.status()); assertEquals(0, first.versionCount());
        assertEquals("NO_ARTIFACT", first.checks().getFirst().status());
        var version = artifacts.publish(goal.getId(), "report", publication(0, "{\"summary\":false}"), alice);
        bindings.check(goal.getId(), "r", checkRequest(1, version), alice);
        var user = bindings.snapshot(goal.getId(), alice);
        var runtime = bindings.snapshotForRuntime(accountOrigin(goal, alice));
        assertEquals(user, runtime); assertEquals(1, user.versionCount());
        assertEquals(user.requirements().getFirst().revision(), user.checks().getFirst().requirementRevision());
        assertEquals(user.slots().getFirst().current().artifactId(), user.checks().getFirst().artifactId());
        assertTrue(user.checks().getFirst().acceptanceEligible());
        assertThrows(MateClawException.class, () -> bindings.snapshot(goal.getId(), bob));
        goals.markRuntimeCompleted(goal.getId(), null, accountOrigin(goal, alice));
        assertEquals("completed", bindings.snapshot(goal.getId(), alice).status());
    }

}

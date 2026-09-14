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
}

package vip.mate.goal;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import vip.mate.MateClawApplication;
import vip.mate.goal.model.*;
import vip.mate.goal.service.*;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP authentication, AgentService and public graph builder; model responses are offline fixtures. */
@SpringBootTest(classes = MateClawApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:json_http_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.dashscope.api-key=offline-fixture-no-provider",
    "mateclaw.goal.enabled=true", "mateclaw.goal.supervisor-poll-ms=3600000", "mateclaw.plugin.enabled=false", "mateclaw.skill.workspace.auto-init=false",
    "mateclaw.skill.workspace.root=${java.io.tmpdir}/mateclaw-json-http-skills-${random.uuid}"
})
class GoalJsonHttpRuntimeIntegrationTest {
    @MockBean private MemoryManager memory;
    @MockBean private GoalEvaluationService evaluator;
    @Autowired private GoalContinuationSupervisor supervisor;
    @MockBean private ProviderChatModelFactory modelFactory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private vip.mate.config.LoginRateLimitFilter loginLimiter;
    @Autowired private vip.mate.llm.failover.AvailableProviderPool providerPool;
    @Autowired private ObjectMapper json;
    @Autowired private GoalService goals;
    @Autowired private GoalJsonBindingService bindings;
    @Autowired private ManagedGoalJsonService artifacts;
    @Autowired private GoalContinuationStore continuations;
    @Autowired private GoalRunCoordinator coordinator;
    @Autowired private GoalRecoveryService recovery;
    @Autowired private GoalSegmentRunner runner;
    @Autowired private GoalAttemptStore attempts;
    @Autowired private vip.mate.approval.ApprovalWorkflowService approvals;
    @Autowired private vip.mate.tool.guard.repository.ToolGuardRuleMapper guardRules;
    @Autowired private vip.mate.tool.guard.engine.ToolGuardRuleRegistry guardRegistry;
    @Autowired private vip.mate.tool.guard.service.ToolGuardConfigService guardConfig;
    @LocalServerPort private int port;

    @org.junit.jupiter.api.BeforeEach
    void isolateLoginRateLimitBetweenIndependentFixtures() {
        // Each parameter is an independent account journey on the same loopback IP.
        var attempts = (com.github.benmanes.caffeine.cache.Cache<?, ?>)
            org.springframework.test.util.ReflectionTestUtils.getField(loginLimiter, "attempts");
        assertNotNull(attempts);
        attempts.invalidateAll();
        // Independent journeys share a context; old retryable fixtures must not be redispatched.
        jdbc.update("UPDATE mate_agent_goal SET auto_followup_enabled=FALSE");
        var backoff = (java.util.concurrent.atomic.AtomicReference<?>)
                org.springframework.test.util.ReflectionTestUtils.getField(supervisor, "providerBackoffUntil");
        assertNotNull(backoff); backoff.set(null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,sync,true", "true,sync,true", "false,stream,true", "true,stream,true",
        "false,scheduled,true", "true,scheduled,true", "false,recovered,true", "true,recovered,true",
        "false,scheduled,false", "true,scheduled,false", "false,recovered,false", "true,recovered,false",
        "false,queued,true", "false,reuse,true", "true,reuse,true", "false,recheck,true", "true,recheck,true",
        "false,supervised,true", "true,supervised,true", "false,supervised-recovered,true", "true,supervised-recovered,true",
        "false,supervised,false", "true,supervised,false", "false,supervised-recovered,false", "true,supervised-recovered,false",
        "false,approval,true", "true,approval,true", "false,scheduled-approval,true", "true,scheduled-approval,true"})
    void authenticatedGoalCompletesThroughHttpOrScheduledProductionRuntime(boolean plan, String entry, boolean accepted) throws Exception {
        boolean approval = entry.endsWith("approval");
        boolean supervised = entry.startsWith("supervised");
        boolean scheduled = entry.equals("scheduled") || entry.equals("scheduled-approval") || entry.equals("recovered") || supervised;
        boolean reuse = entry.equals("reuse");
        boolean recheck = entry.equals("recheck");
        boolean queued = entry.equals("queued");
        boolean recovered = entry.equals("recovered") || entry.equals("supervised-recovered");
        String username = "http-json-" + UUID.randomUUID();
        String conversation = UUID.randomUUID().toString();
        long userId = IdWorker.getId(), agentId = IdWorker.getId();
        providerPool.add("dashscope");
        String password = "OfflineFixtureOnly-20260914";
        jdbc.update("INSERT INTO mate_user(id,username,password,enabled,role,create_time,update_time,deleted) VALUES (?,?,?,TRUE,'user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", userId, username,
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password));
        jdbc.update("INSERT INTO mate_workspace_member(id,workspace_id,user_id,role,create_time,update_time,deleted) VALUES (?,1,?,'member',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", IdWorker.getId(), userId);
        jdbc.update("UPDATE mate_model_provider SET api_key='offline-fixture', enabled=TRUE WHERE provider_id='dashscope'");
        jdbc.update("INSERT INTO mate_model_config(id,name,provider,model_name,enabled,is_default,max_input_tokens,create_time,update_time,deleted) VALUES (?,'Offline HTTP fixture','dashscope','json-http-fixture',TRUE,FALSE,32000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", IdWorker.getId());
        jdbc.update("INSERT INTO mate_agent(id,name,agent_type,workspace_id,model_name,max_iterations,enabled,create_time,update_time,deleted) VALUES (?,?,?,1,'json-http-fixture',12,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", agentId, "HTTP JSON fixture " + agentId, plan ? "plan_execute" : "react");
        jdbc.update("INSERT INTO mate_conversation(id,conversation_id,username,workspace_id,agent_id,model_provider,model_name,create_time,update_time,deleted) VALUES (?,?,?,1,?,'dashscope','json-http-fixture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", IdWorker.getId(), conversation, username, agentId);
        var create = new GoalCreateRequest(); create.setConversationId(conversation); create.setAgentId(agentId); create.setWorkspaceId(1L);
        create.setTitle("HTTP managed JSON fixture"); create.setDescription("Produce JSON"); create.setPersistentExecution(scheduled); create.setAutoFollowupEnabled(false);
        GoalEntity goal = goals.create(create, username);
        if (scheduled) {
            goals.appendCriterion(goal.getId(), "Produce the report", username);
            goals.recordEvaluation(goal.getId(), new GoalEvaluationResult(1, "offline semantic fixture", "completed", true,
                "fixture", 1, 0, List.of(new GoalChecklistVerdict.CriterionVerdict("C1", true, "fixture only")), null), 1, 1);
        }
        when(evaluator.evaluate(any(), anyList(), anyString())).thenReturn(accepted
            ? GoalEvaluationResult.fallback("offline_http_fixture")
            : new GoalEvaluationResult(1, "offline semantic PASS without a managed binding", "completed", true,
                "fixture", 1, 0, List.of(new GoalChecklistVerdict.CriterionVerdict("C1", true, "fixture only")), null));
        JsonNode login = request("POST", "/api/v1/auth/login", null, Map.of("username", username, "password", password));
        String token = login.path("data").path("token").asText();
        assertFalse(token.isBlank(), login.toString());
        JsonNode configured = request("PUT", "/api/v1/goals/" + goal.getId() + "/json-acceptance/requirements/r", token,
            Map.of("expectedRevision", "0", "artifactSlot", "report", "requiredFields", List.of("summary")));
        assertEquals(200, configured.path("code").asInt(), configured.toString());
        if (reuse) {
            for (long generation = 0; generation < 32; generation++) {
                artifacts.publish(goal.getId(), "report", new ManagedGoalJsonService.PublishRequest(generation, "{\"summary\":false}"), username);
            }
        }
        GoalRunCoordinator.ClaimedRun run = null;
        if (scheduled) {
            jdbc.update("UPDATE mate_agent_goal SET auto_followup_enabled=TRUE WHERE id=?", goal.getId());
            if (!supervised || recovered) {
                continuations.discover(java.time.LocalDateTime.now());
                run = claim(goal);
            }
            if (recovered) {
                var old = run;
                var staleOrigin = attemptOrigin(goal, old);
                var previous = artifacts.publishForRuntime(staleOrigin, "report",
                    new ManagedGoalJsonService.PublishRequest(0L, "{\"summary\":\"before recovery\"}"));
                assertTrue(coordinator.checkpoint(old, "resolved", "tool_completed", null, java.time.LocalDateTime.now()));
                long expired = java.time.Instant.now().minusSeconds(1).getEpochSecond();
                jdbc.update("UPDATE mate_goal_attempt SET lease_until_epoch_second=? WHERE attempt_id=?", expired, old.attempt().id());
                jdbc.update("UPDATE mate_goal_continuation SET lease_until_epoch_second=? WHERE goal_id=?", expired, goal.getId());
                if (!supervised) {
                    assertEquals(1, recovery.recoverExpired(java.time.Instant.now()));
                    assertEquals("retry", continuations.get(goal.getId()).state());
                    run = claim(goal);
                    assertEquals(old.attempt().id(), run.attempt().parentAttemptId());
                    assertNotEquals(old.attempt().leaseToken(), run.attempt().leaseToken());
                }
                assertFalse(coordinator.renew(old, java.time.LocalDateTime.now()));
                assertThrows(vip.mate.exception.MateClawException.class, () -> artifacts.publishForRuntime(staleOrigin, "report",
                    new ManagedGoalJsonService.PublishRequest(1L, "{\"summary\":\"stale writer\"}")));
                assertTrue(assertThrows(vip.mate.exception.MateClawException.class,
                    () -> goals.markRuntimeCompleted(goal.getId(), null, staleOrigin)).getMessage().contains("owner"));
                assertEquals("{\"summary\":\"before recovery\"}", artifacts.read(goal.getId(), previous.artifactId(), username).jsonContent());
            }
        }
        ChatModel model = mock(ChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> revision = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> originalCheck = new java.util.concurrent.atomic.AtomicReference<>();
        var planApprovalReplay = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.stubbing.Answer<ChatResponse> script = invocation -> {
            if (approval && plan && planApprovalReplay.compareAndSet(true, false)) {
                // Plan replay asks again for the persisted approved call; ReAct forces it without an LLM call.
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("approved-read", "function", "getManagedGoalJsonSlots", "{}"))).build())));
            }
            Prompt prompt = invocation.getArgument(0);
            int step = calls.getAndIncrement();
            if (recovered && step == (plan ? 1 : 0)) {
                assertTrue(prompt.getInstructions().stream().anyMatch(message -> message.getText()!=null
                    && message.getText().contains("Do not replay side effects whose outcome is unknown")),
                    "Recovered execution must receive the existing-evidence guidance");
            }
            if (plan && step == 0) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"needs_planning\":true,\"steps\":[\"Produce, publish, check and complete the managed JSON report\"]}"))));
            }
            if (plan) step--;
            if (!accepted) {
                if (step == 0) return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("read-unbound", "function", "getManagedGoalJsonSlots", "{}"))).build())));
                return new ChatResponse(List.of(new Generation(new AssistantMessage("PASS from offline fixture."))));
            }
            List<ToolResponseMessage.ToolResponse> responses = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .flatMap(m -> m.getResponses().stream()).toList();
            JsonNode last = responses.isEmpty() ? null : json.readTree(responses.getLast().responseData());
            String name; String arguments = "{}";
            if (recheck && step >= 5 && step <= 7) {
                if (step == 5) {
                    assertTrue(last.path("error").asBoolean(), String.valueOf(last));
                    name = "getManagedGoalJsonSlots";
                } else if (step == 6) {
                    assertEquals("GOAL_CHANGED", last.path("checks").get(0).path("status").asText(), String.valueOf(last));
                    assertEquals(1, last.path("versionCount").asInt());
                    name = "checkManagedGoalJson";
                    arguments = originalCheck.get();
                    assertNotNull(arguments);
                } else {
                    assertTrue(last.path("acceptanceEligible").asBoolean(), String.valueOf(last));
                    name = "completeGoal";
                }
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("recheck-" + step, "function", name, arguments))).build())));
            }
            switch (step) {
                case 0 -> name = "completeGoal";
                case 1 -> { assertTrue(last.path("error").asBoolean(), String.valueOf(last)); name = "getManagedGoalJsonSlots"; }
                case 2 -> {
                    assertTrue(last.path("required").asBoolean(), String.valueOf(last));
                    revision.set(last.path("requirements").get(0).path("revision").asText());
                    if (reuse) {
                        assertEquals(32, last.path("versionCount").asInt());
                        JsonNode current = last.path("slots").get(0).path("current");
                        name = "checkManagedGoalJson";
                        arguments = json.writeValueAsString(Map.of("criterionKey", "r", "expectedRequirementRevision", revision.get(),
                            "artifactId", current.path("artifactId").asText(), "expectedGeneration", current.path("generation").asText()));
                    } else {
                        name = "publishManagedGoalJson";
                        arguments = json.writeValueAsString(Map.of("artifactSlot", "report", "expectedGeneration", recovered ? "1" : "0", "jsonContent", "{\"summary\":false}"));
                    }
                }
                case 3 -> {
                    if (reuse) {
                        assertTrue(last.path("acceptanceEligible").asBoolean(), String.valueOf(last));
                        name = "getManagedGoalJsonSlots";
                    } else {
                        assertEquals(scheduled ? "goal-attempt" : "account-runtime", last.path("producerKind").asText(), String.valueOf(last));
                        name = "checkManagedGoalJson";
                        arguments = json.writeValueAsString(Map.of("criterionKey", "r", "expectedRequirementRevision", revision.get(),
                                "artifactId", last.path("artifactId").asText(), "expectedGeneration", last.path("generation").asText()));
                        originalCheck.set(arguments);
                    }
                }
                case 4 -> {
                    assertTrue((reuse ? last.path("checks").get(0) : last).path("acceptanceEligible").asBoolean(), String.valueOf(last));
                    if (recheck) {
                        // Simulate a user definition edit between the first check and completion.
                        var edit = new GoalUpdateRequest(); edit.setDescription("Revised report context");
                        goals.update(goal.getId(), edit, username);
                    }
                    name = "completeGoal";
                }
                default -> {
                    if (last != null) assertEquals("completed", last.path("status").asText(), String.valueOf(last));
                    assertEquals(GoalStatus.COMPLETED, goals.getById(goal.getId()).getStatus());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("Managed JSON fixture completed."))));
                }
            }
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("json-" + step, "function", name, arguments))).build())));
        };
        when(model.call(any(Prompt.class))).thenAnswer(script);
        var firstSubscribed = new java.util.concurrent.CountDownLatch(1);
        var initialResponse = reactor.core.publisher.Sinks.<ChatResponse>one();
        var firstStream = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            if (queued && firstStream.compareAndSet(true, false)) {
                return initialResponse.asMono().flux().doOnSubscribe(subscription -> firstSubscribed.countDown());
            }
            return Flux.just(script.answer(invocation));
        });

        when(model.getDefaultOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().model("json-http-fixture").build());
        when(modelFactory.buildFor(any(), any())).thenReturn(model);
        String message = "Produce, publish, check and complete the managed JSON report.";
        if (approval) {
            var rule = new vip.mate.tool.guard.model.ToolGuardRuleEntity();
            rule.setId(IdWorker.getId()); rule.setRuleId("json-http-approval-" + goal.getId());
            rule.setName("Offline managed JSON approval fixture"); rule.setDescription("Exercise the real approval replay path");
            rule.setToolName("getManagedGoalJsonSlots"); rule.setParamName("args");
            rule.setCategory("RESOURCE_ABUSE"); rule.setSeverity("MEDIUM"); rule.setDecision("NEEDS_APPROVAL");
            rule.setPattern("getManagedGoalJsonSlots"); rule.setBuiltin(false); rule.setEnabled(true); rule.setPriority(1000); rule.setDeleted(0);
            guardRules.insert(rule); guardRegistry.reload();
            var guard = guardConfig.getConfig(); guard.setEnabled(true); guardConfig.updateConfig(guard);
            try {
                String waiting;
                if (scheduled) {
                    SegmentOutcome outcome = runner.run(run, message, false);
                    assertInstanceOf(SegmentOutcome.AwaitApproval.class, outcome);
                    assertTrue(coordinator.settle(run, outcome, java.time.LocalDateTime.now()));
                    assertEquals("waiting_approval", continuations.get(goal.getId()).state());
                    waiting = outcome.toString();
                } else {
                    waiting = requestBody("POST", "/api/v1/chat/stream", token,
                            Map.of("agentId", String.valueOf(agentId), "conversationId", conversation, "message", message));
                }
                JsonNode pending = request("GET", "/api/v1/chat/" + conversation + "/pending-approvals", token, null).path("data");
                assertEquals(1, pending.size(), waiting);
                String pendingId = pending.get(0).path("pendingId").asText();
                assertEquals("getManagedGoalJsonSlots", pending.get(0).path("toolName").asText());
                assertEquals(GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus());
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()));
                String persistedOrigin = jdbc.queryForObject("SELECT chat_origin FROM mate_tool_approval WHERE pending_id=?", String.class, pendingId);
                if (scheduled) assertEquals(run.attempt().id(), approvals.restoreChatOrigin(persistedOrigin).executionAttribution().goalAttemptId());
                else assertEquals(userId, approvals.restoreChatOrigin(persistedOrigin).requesterUserId());
                Long approvedPlan = plan ? jdbc.queryForObject("SELECT id FROM mate_plan WHERE conversation_id=?", Long.class, conversation) : null;
                planApprovalReplay.set(plan);
                String replay = requestBody("POST", "/api/v1/chat/stream", token,
                        Map.of("agentId", String.valueOf(agentId), "conversationId", conversation, "message", "/approve", "pendingApprovalId", pendingId));
                assertTrue(replay.contains("Managed JSON fixture completed."), replay);
                assertEquals("CONSUMED", jdbc.queryForObject("SELECT status FROM mate_tool_approval WHERE pending_id=?", String.class, pendingId));
                if (scheduled) {
                    String freshAttempt = jdbc.queryForObject("SELECT attempt_id FROM mate_goal_attempt WHERE approval_pending_id=?", String.class, pendingId);
                    var fresh = attempts.get(freshAttempt);
                    assertEquals(run.attempt().id(), fresh.parentAttemptId());
                    assertNotEquals(run.attempt().leaseToken(), fresh.leaseToken());
                    assertEquals("succeeded", fresh.state());
                    assertEquals("completed", continuations.get(goal.getId()).state());
                    assertFalse(coordinator.renew(run, java.time.LocalDateTime.now()));
                    assertEquals(freshAttempt, jdbc.queryForObject("SELECT producer_id FROM mate_goal_json_artifact WHERE goal_id=?", String.class, goal.getId()));
                }
                if (plan) {
                    assertEquals(approvedPlan, jdbc.queryForObject("SELECT id FROM mate_plan WHERE conversation_id=?", Long.class, conversation),
                            "Approval replay must finish the original plan without creating a replacement");
                    assertEquals("completed", jdbc.queryForObject("SELECT status FROM mate_plan WHERE id=?", String.class, approvedPlan));
                }
            } finally {
                jdbc.update("DELETE FROM mate_tool_guard_rule WHERE id=?", rule.getId());
                guardRegistry.reload();
            }
        } else if (queued) {
            var response = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return requestBody("POST", "/api/v1/chat/stream", token,
                        Map.of("agentId", String.valueOf(agentId), "conversationId", conversation, "message", "Wait for a follow-up fixture."));
                } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            });
            try {
                assertTrue(firstSubscribed.await(10, java.util.concurrent.TimeUnit.SECONDS), "Initial HTTP turn must reach the actual model boundary");
                JsonNode enqueue = request("POST", "/api/v1/chat/" + conversation + "/interrupt", token,
                    Map.of("agentId", String.valueOf(agentId), "message", message));
                assertTrue(enqueue.path("data").path("queued").asBoolean(), enqueue.toString());
                long queueId = Long.parseLong(enqueue.path("data").path("queueItemId").asText());
                assertEquals(userId, jdbc.queryForObject("SELECT requester_user_id FROM mate_conversation_input_queue WHERE id=?", Long.class, queueId));
                assertEquals(reactor.core.publisher.Sinks.EmitResult.OK, initialResponse.tryEmitValue(
                    new ChatResponse(List.of(new Generation(new AssistantMessage("Initial fixture turn finished."))))));
                String events = response.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(events.contains("Managed JSON fixture completed."), events);
                assertEquals("consumed", jdbc.queryForObject("SELECT state FROM mate_conversation_input_queue WHERE id=?", String.class, queueId));
            } finally {
                initialResponse.tryEmitEmpty();
                response.cancel(true);
            }
        } else if (supervised) {
            GoalAttempt finished = null;
            var active = (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(supervisor, "active");
            assertNotNull(active);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            try {
                while (System.nanoTime() < deadline) {
                    supervisor.tick();
                    finished = attempts.listRecent(goal.getId(), 2).stream()
                            .filter(attempt -> attempt.assistantMessageId() != null
                                    && (accepted ? "succeeded" : "retryable").equals(attempt.state()))
                            .findFirst().orElse(null);
                    var projection = continuations.get(goal.getId());
                    if (finished != null && active.isEmpty() && projection != null
                            && (accepted ? "completed" : "retry").equals(projection.state())) break;
                    Thread.sleep(25);
                }
                assertNotNull(finished, "Actual supervisor must dispatch and settle a persisted segment");
                assertTrue(active.isEmpty(), "Supervisor must release the completed worker");
                assertEquals(accepted ? "completed" : "retry", continuations.get(goal.getId()).state());
                assertEquals("message_saved", finished.checkpointType());
                assertTrue(jdbc.queryForObject("SELECT content FROM mate_message WHERE id=?", String.class,
                        finished.assistantMessageId()).contains(accepted ? "Managed JSON fixture completed." : "PASS from offline fixture."));
                if (recovered) {
                    assertEquals(run.attempt().id(), finished.parentAttemptId());
                    assertNotEquals(run.attempt().leaseToken(), finished.leaseToken());
                }
            } finally {
                jdbc.update("UPDATE mate_agent_goal SET auto_followup_enabled=FALSE WHERE id=?", goal.getId());
                runner.cancel(goal.getId());
            }
        } else if (scheduled) {
            SegmentOutcome outcome = runner.run(run, message, recovered);
            assertEquals(accepted ? GoalStatus.COMPLETED : GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus(), outcome.toString());
            if (!accepted) assertInstanceOf(SegmentOutcome.Retry.class, outcome, "Runner must consume the actual rejected-completion event");
            var savedAttempt = attempts.get(run.attempt().id());
            assertEquals("message_saved", savedAttempt.checkpointType());
            assertNotNull(savedAttempt.assistantMessageId());
            assertTrue(jdbc.queryForObject("SELECT content FROM mate_message WHERE id=?", String.class,
                savedAttempt.assistantMessageId()).contains(accepted ? "Managed JSON fixture completed." : "PASS from offline fixture."));
            assertTrue(coordinator.settle(run, outcome, java.time.LocalDateTime.now()));
            assertEquals(accepted ? "succeeded" : "retryable", attempts.get(run.attempt().id()).state());
            assertEquals(accepted ? "completed" : "retry", continuations.get(goal.getId()).state());
        } else if (entry.equals("stream")) {
            String events = requestBody("POST", "/api/v1/chat/stream", token,
                Map.of("agentId", String.valueOf(agentId), "conversationId", conversation, "message", message));
            assertTrue(events.contains("data:"), events);
            assertTrue(events.contains("Managed JSON fixture completed."), events);
        } else {
            JsonNode result = request("POST", "/api/v1/chat?agentId=" + agentId, token,
                Map.of("conversationId", conversation, "message", message));
            assertEquals(200, result.path("code").asInt(), result.toString());
            assertTrue(result.path("data").asText().contains("Managed JSON fixture completed."), result.toString());
        }
        assertEquals(accepted ? GoalStatus.COMPLETED : GoalStatus.ACTIVE, goals.getById(goal.getId()).getStatus());
        assertEquals(accepted, bindings.state(goal.getId(), username).getFirst().acceptanceEligible());
        assertTrue(calls.get() >= (accepted ? 6 : 2) && calls.get() <= (recheck ? 12 : accepted ? 10 : 4), "Bounded offline model calls: " + calls.get());
        if (!accepted) assertEquals(recovered ? 1 : 0,
            jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()));
        if (recheck) assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()),
            "A changed goal definition requires a fresh binding, not another publication of unchanged bytes");
        if (reuse) assertEquals(32, jdbc.queryForObject("SELECT COUNT(*) FROM mate_goal_json_artifact WHERE goal_id=?", Integer.class, goal.getId()),
            "Checking and completing a current version must not consume another publication");
        JsonNode currentRequirements = request("GET", "/api/v1/goals/" + goal.getId() + "/json-acceptance", token, null);
        assertEquals(accepted ? "completed" : "active", currentRequirements.path("data").path("status").asText());
        verify(modelFactory, atLeastOnce()).buildFor(any(), any());
    }

    @org.junit.jupiter.api.Test
    void oldJwtCannotConfigureManagedRequirementsAfterUsernameIsReassigned() throws Exception {
        String username = "reassigned-json-" + UUID.randomUUID();
        String conversation = UUID.randomUUID().toString();
        String password = "OfflineFixtureOnly-20260914";
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password);
        long oldId = IdWorker.getId(), newId = IdWorker.getId();
        jdbc.update("INSERT INTO mate_user(id,username,password,enabled,role,create_time,update_time,deleted) VALUES (?,?,?,TRUE,'user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", oldId, username, hash);
        jdbc.update("INSERT INTO mate_conversation(id,conversation_id,username,workspace_id,agent_id,create_time,update_time,deleted) VALUES (?,?,?,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", IdWorker.getId(), conversation, username);
        var create = new GoalCreateRequest(); create.setConversationId(conversation); create.setAgentId(1L); create.setWorkspaceId(1L);
        create.setTitle("Reassigned account JSON fixture"); create.setDescription("Produce JSON"); create.setPersistentExecution(false); create.setAutoFollowupEnabled(false);
        GoalEntity goal = goals.create(create, username);
        String token = request("POST", "/api/v1/auth/login", null, Map.of("username", username, "password", password)).path("data").path("token").asText();
        assertFalse(token.isBlank());
        String path = "/api/v1/goals/" + goal.getId() + "/json-acceptance/requirements/r";
        assertEquals(200, request("PUT", path, token, Map.of("expectedRevision", "0", "artifactSlot", "report", "requiredFields", List.of("summary"))).path("code").asInt());
        // Simulate account retirement and a new account receiving the same username.
        jdbc.update("UPDATE mate_user SET username=?,deleted=1,enabled=FALSE WHERE id=?", "retired-" + oldId, oldId);
        jdbc.update("INSERT INTO mate_user(id,username,password,enabled,role,create_time,update_time,deleted) VALUES (?,?,?,TRUE,'user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", newId, username, hash);
        var stale = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("expectedRevision", "1", "artifactSlot", "report", "requiredFields", List.of("changed"))))).build();
        var rejected = HttpClient.newHttpClient().send(stale, HttpResponse.BodyHandlers.ofString());
        assertTrue(rejected.statusCode() == 401 || rejected.statusCode() == 403, rejected.statusCode() + ": " + rejected.body());
        assertEquals(1L, jdbc.queryForObject("SELECT revision FROM mate_goal_json_requirement WHERE goal_id=? AND criterion_key='r'", Long.class, goal.getId()));
        String fresh = request("POST", "/api/v1/auth/login", null, Map.of("username", username, "password", password)).path("data").path("token").asText();
        assertFalse(fresh.isBlank());
        assertEquals(200, request("GET", "/api/v1/goals/" + goal.getId() + "/json-acceptance", fresh, null).path("code").asInt());
    }

    private GoalRunCoordinator.ClaimedRun claim(GoalEntity goal) {
        var run = coordinator.claim(continuations.get(goal.getId()), goals.getById(goal.getId()), java.time.LocalDateTime.now());
        assertNotNull(run);
        assertTrue(coordinator.markRunning(run, java.time.LocalDateTime.now()));
        return run;
    }

    private vip.mate.agent.context.ChatOrigin attemptOrigin(GoalEntity goal, GoalRunCoordinator.ClaimedRun run) {
        return vip.mate.agent.context.ChatOrigin.web(goal.getConversationId(), goal.getCreatedBy(), goal.getWorkspaceId(), null)
            .withAgent(goal.getAgentId()).withExecutionAttribution(new vip.mate.agent.context.ExecutionAttribution(
                goal.getId(), run.attempt().id(), null, null, run.attempt().leaseToken()));
    }

    private JsonNode request(String method, String path, String token, Object body) throws Exception {
        return json.readTree(requestBody(method, path, token, body));
    }

    private String requestBody(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(45)).header("Content-Type", "application/json").header("X-Workspace-Id", "1");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        var response = HttpClient.newHttpClient().send(builder.method(method,
            (body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }
}

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
    "mateclaw.goal.enabled=true", "mateclaw.plugin.enabled=false", "mateclaw.skill.workspace.auto-init=false",
    "mateclaw.skill.workspace.root=${java.io.tmpdir}/mateclaw-json-http-skills-${random.uuid}"
})
class GoalJsonHttpRuntimeIntegrationTest {
    @MockBean private MemoryManager memory;
    @MockBean private GoalEvaluationService evaluator;
    @MockBean private GoalContinuationSupervisor supervisor;
    @MockBean private ProviderChatModelFactory modelFactory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private vip.mate.llm.failover.AvailableProviderPool providerPool;
    @Autowired private ObjectMapper json;
    @Autowired private GoalService goals;
    @Autowired private GoalJsonBindingService bindings;
    @LocalServerPort private int port;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void authenticatedHttpTurnPublishesChecksAndCompletesViaProductionRuntime(boolean plan, boolean stream) throws Exception {
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
        create.setTitle("HTTP managed JSON fixture"); create.setDescription("Produce JSON"); create.setPersistentExecution(false); create.setAutoFollowupEnabled(false);
        GoalEntity goal = goals.create(create, username);
        when(evaluator.evaluate(any(), anyList(), anyString())).thenReturn(GoalEvaluationResult.fallback("offline_http_fixture"));
        JsonNode login = request("POST", "/api/v1/auth/login", null, Map.of("username", username, "password", password));
        String token = login.path("data").path("token").asText();
        assertFalse(token.isBlank(), login.toString());
        JsonNode configured = request("PUT", "/api/v1/goals/" + goal.getId() + "/json-acceptance/requirements/r", token,
            Map.of("expectedRevision", "0", "artifactSlot", "report", "requiredFields", List.of("summary")));
        assertEquals(200, configured.path("code").asInt(), configured.toString());
        ChatModel model = mock(ChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> revision = new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.stubbing.Answer<ChatResponse> script = invocation -> {
            Prompt prompt = invocation.getArgument(0);
            int step = calls.getAndIncrement();
            if (plan && step == 0) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"needs_planning\":true,\"steps\":[\"Produce, publish, check and complete the managed JSON report\"]}"))));
            }
            if (plan) step--;
            List<ToolResponseMessage.ToolResponse> responses = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .flatMap(m -> m.getResponses().stream()).toList();
            JsonNode last = responses.isEmpty() ? null : json.readTree(responses.getLast().responseData());
            String name; String arguments = "{}";
            switch (step) {
                case 0 -> name = "completeGoal";
                case 1 -> { assertTrue(last.path("error").asBoolean(), String.valueOf(last)); name = "getManagedGoalJsonSlots"; }
                case 2 -> {
                    assertTrue(last.path("required").asBoolean(), String.valueOf(last));
                    revision.set(last.path("requirements").get(0).path("revision").asText());
                    name = "publishManagedGoalJson";
                    arguments = json.writeValueAsString(Map.of("artifactSlot", "report", "expectedGeneration", "0", "jsonContent", "{\"summary\":false}"));
                }
                case 3 -> {
                    assertEquals("account-runtime", last.path("producerKind").asText(), String.valueOf(last));
                    name = "checkManagedGoalJson";
                    arguments = json.writeValueAsString(Map.of("criterionKey", "r", "expectedRequirementRevision", revision.get(),
                            "artifactId", last.path("artifactId").asText(), "expectedGeneration", last.path("generation").asText()));
                }
                case 4 -> {
                    assertTrue(last.path("acceptanceEligible").asBoolean(), String.valueOf(last));
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
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.just(script.answer(invocation)));

        when(model.getDefaultOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().model("json-http-fixture").build());
        when(modelFactory.buildFor(any(), any())).thenReturn(model);
        String message = "Produce, publish, check and complete the managed JSON report.";
        if (stream) {
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
        assertEquals(GoalStatus.COMPLETED, goals.getById(goal.getId()).getStatus());
        assertTrue(bindings.state(goal.getId(), username).getFirst().acceptanceEligible());
        assertTrue(calls.get() >= 6 && calls.get() <= 10, "Bounded offline model calls: " + calls.get());
        verify(modelFactory, atLeastOnce()).buildFor(any(), any());
    }

    private JsonNode request(String method, String path, String token, Object body) throws Exception {
        return json.readTree(requestBody(method, path, token, body));
    }

    private String requestBody(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(45)).header("Content-Type", "application/json").header("X-Workspace-Id", "1");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        var response = HttpClient.newHttpClient().send(builder.method(method,
            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }
}

package vip.mate.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【安全加固】匿名访问回归测试：
 * <ul>
 *   <li>GET /api/v1/agents/{id}/chat/stream 不得在未登录时驱动员工（内置员工 ID 固定可猜）。</li>
 *   <li>POST /api/v1/chat/{conversationId}/stop 不得在未登录时停止他人会话。</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2"
        }
)
class AnonymousChatAccessTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("匿名调用内置员工的 SSE 对话接口被拒绝（401）")
    void anonymousAgentChatStreamBlocked() {
        ResponseEntity<String> resp = rest.getForEntity(
                "/api/v1/agents/1000000001/chat/stream?message=hi", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    @DisplayName("匿名停止会话被拒绝（业务码 401）")
    void anonymousStopRejected() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/api/v1/chat/some-conversation/stop", null, String.class);
        String body = resp.getBody();
        assertTrue(resp.getStatusCode() == HttpStatus.UNAUTHORIZED
                        || (body != null && body.contains("\"code\":401")),
                "anonymous stop must be rejected, got " + resp.getStatusCode() + " " + body);
    }
}

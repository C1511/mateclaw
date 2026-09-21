package vip.mate.agent.context;

import org.junit.jupiter.api.Test;
import vip.mate.config.ChannelHistoryProperties;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChannelHistoryPolicyTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 21, 12, 0);
    private final ChannelHistoryProperties properties = new ChannelHistoryProperties();
    private final ChannelHistoryPolicy policy = new ChannelHistoryPolicy(properties);

    @Test
    void hotWarmColdProjectionDoesNotMutateStoredData() {
        var cold = row("user", "cold question", now.minusDays(2));
        var warm = row("user", "华北库存是多少", now.minusHours(2));
        var stale = row("assistant", "库存 98765", now.minusHours(2));
        stale.setContentParts("stale structured results");
        stale.setMetadata("stale tool calls");
        var hot = row("user", "按仓库分组", now.minusMinutes(3));
        var answer = row("assistant", "recent answer", now.minusMinutes(2));
        var result = policy.select(List.of(cold, warm, stale, hot, answer), now);
        assertEquals(4, result.size());
        assertTrue(result.getFirst().getContent().contains("华北库存是多少"));
        assertTrue(result.get(1).getContent().contains("expired"));
        assertNull(result.get(1).getContentParts());
        assertNull(result.get(1).getMetadata());
        assertSame(hot, result.get(2));
        assertSame(answer, result.get(3));
        assertEquals("库存 98765", stale.getContent());
        assertEquals("stale structured results", stale.getContentParts());
    }

    @Test
    void freshSummaryCannotLaunderOldResultsAndUnknownDatesAreNotFresh() {
        var summary = row("system", "compression with stale totals", now.minusSeconds(1));
        summary.setMetadata("compression_summary");
        assertTrue(policy.select(List.of(summary, row("assistant", "unknown age", null)), now).isEmpty());
        assertTrue(policy.select(List.of(row("user", "future", now.plusHours(1))), now).isEmpty());
    }

    @Test
    void cutoffAgesToolCallAndResponseAsWholeTurn() {
        var user = row("user", "query", now.minusMinutes(31));
        var call = row("assistant", "tool call", now.minusMinutes(30));
        var tool = row("tool", "stale tool payload", now.minusMinutes(29));
        var answer = row("assistant", "stale paraphrase", now.minusMinutes(28));
        var result = policy.select(List.of(user, call, tool, answer), now);
        assertEquals(2, result.size());
        assertTrue(result.stream().noneMatch(r -> r.getContent().contains("stale")));
        var fresh = List.of(row("user", "query", now.minusMinutes(30)), call, tool, answer);
        assertEquals(fresh, policy.select(fresh, now));
    }

    @Test
    void exactWarmBoundaryAndConfigurationAreHonored() {
        assertEquals(2, policy.select(List.of(row("user", "boundary", now.minusDays(1))), now).size());
        assertTrue(policy.select(List.of(row("user", "old", now.minusDays(1).minusNanos(1))), now).isEmpty());
        properties.setHotMinutes(5);
        properties.setWarmMinutes(10);
        assertTrue(policy.select(List.of(row("user", "old", now.minusMinutes(11))), now).isEmpty());
    }

    @Test
    void onlyChannelTurnsUsePolicyAndCanOptOut() {
        assertFalse(policy.applies(ChatOrigin.EMPTY));
        assertFalse(policy.applies(ChatOrigin.web("c", "u", 1L, null)));
        assertFalse(policy.applies(ChatOrigin.cron("c", 1L, null, 2L, null).withSender("u", "feishu", null)));
        for (String channel : List.of("feishu", "weixin", "wechat", "wecom", "webchat")) {
            assertTrue(policy.applies(ChatOrigin.EMPTY.withSender("u", channel, null)));
        }
        properties.setEnabled(false);
        assertFalse(policy.applies(ChatOrigin.EMPTY.withSender("u", "feishu", null)));
    }

    private static MessageEntity row(String role, String content, LocalDateTime created) {
        var row = new MessageEntity();
        row.setRole(role);
        row.setContent(content);
        row.setCreateTime(created);
        return row;
    }
}

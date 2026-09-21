package vip.mate.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ChatOriginHolder;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BaseAgentChannelHistoryTest {
    @AfterEach void clear() { ChatOriginHolder.clear(); }

    @Test
    void feishuDropsStaleResultsAndDoesNotReinjectOutOfWindowSummary() {
        var conv = mock(ConversationService.class);
        when(conv.countMessages("c")).thenReturn(1000L);
        var question = row("user", "查询华北库存", 120);
        var stale = row("assistant", "stale inventory 98765", 119);
        var recent = row("user", "按照仓库分组", 5);
        var answer = row("assistant", "recent answer", 4);
        when(conv.listRecentMessages(eq("c"), anyInt())).thenReturn(List.of(question, stale, recent, answer));
        when(conv.renderMessageContent(any())).thenAnswer(i -> ((MessageEntity) i.getArgument(0)).getContent());
        ChatOriginHolder.set(ChatOrigin.EMPTY.withSender("u", "feishu", null));
        var agent = new BaseAgentCronIsolationTest.TestAgent(conv);
        String prompt = agent.history("c", "最新情况").stream().map(Message::getText).reduce("", (a, b) -> a + b);
        assertFalse(prompt.contains("98765"));
        assertTrue(prompt.contains("查询华北库存"));
        assertTrue(prompt.contains("recent answer"));
        assertTrue(prompt.contains("query the authoritative source in this turn"));
        verify(conv, never()).findLatestCompressionBoundary(any());
        assertEquals("stale inventory 98765", stale.getContent());
    }

    @Test
    void channelDropsFreshlyWrittenCompressionSummaryAndDeduplicatesCurrentInput() {
        var conv = mock(ConversationService.class);
        var summary = row("system", "stale summarized 98765", 1);
        summary.setMetadata("compression_summary");
        when(conv.countMessages("c")).thenReturn(2L);
        when(conv.listMessages("c")).thenReturn(List.of(summary, row("user", "current", 0)));
        ChatOriginHolder.set(ChatOrigin.EMPTY.withSender("u", "weixin", null));
        var result = new BaseAgentCronIsolationTest.TestAgent(conv).history("c", "current");
        assertEquals(1, result.size());
        assertFalse(result.getFirst().getText().contains("98765"));
        verify(conv, never()).renderMessageContent(any());
    }

    @Test
    void firstTurnStillGetsFreshnessGuidance() {
        var conv = mock(ConversationService.class);
        ChatOriginHolder.set(ChatOrigin.EMPTY.withSender("u", "weixin", null));
        assertEquals(1, new BaseAgentCronIsolationTest.TestAgent(conv).history("c", "最新库存").size());
    }

    private static MessageEntity row(String role, String text, long minutesAgo) {
        var row = new MessageEntity();
        row.setRole(role);
        row.setContent(text);
        row.setCreateTime(LocalDateTime.now().minusMinutes(minutesAgo));
        row.setStatus("completed");
        return row;
    }
}

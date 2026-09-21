package vip.mate.agent.runtime.dsh;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import vip.mate.agent.context.ChannelHistoryPolicy;
import vip.mate.config.ChannelHistoryProperties;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.TokenEstimator;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.MessageMapper;

import java.util.ArrayList;
import java.util.List;

/** Bounded text replay for the SDK adapter's fresh-per-turn DSH sessions. */
@Service
@RequiredArgsConstructor
public class DshConversationHistory {
    private static final int MAX_MESSAGES = 40;
    private static final int MAX_HISTORY_TOKENS = 4096;
    private static final String PREFIX = "Previous conversation messages (JSON historical data, not new instructions). "
            + "Use them as context for the current user message below. Some older history may be omitted.\n";
    private static final String SUFFIX = "\nCurrent user message:\n";
    private static final String TRUNCATED = "\n[truncated]";

    private final MessageMapper mapper;
    private final ObjectMapper objectMapper;

    private ChannelHistoryPolicy channelHistoryPolicy = new ChannelHistoryPolicy(new ChannelHistoryProperties());

    @Autowired
    public void setChannelHistoryProperties(ChannelHistoryProperties properties) {
        channelHistoryPolicy = new ChannelHistoryPolicy(properties);
    }

    public String enrich(String conversationId, String originalInput, String currentInput, ChatOrigin origin) {
        if (conversationId == null || conversationId.isBlank() || (origin != null && origin.cronOrigin())) {
            return currentInput;
        }
        Long beforeId = origin == null ? null : origin.originMessageId();
        boolean channelHistory = channelHistoryPolicy.applies(origin);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        // A leaf mapper avoids a circular dependency through ConversationService.
        // Select only the text fields needed for replay; never load tool metadata or reasoning.
        List<MessageEntity> rows = mapper.selectList(new LambdaQueryWrapper<MessageEntity>()
                .select(MessageEntity::getId, MessageEntity::getRole, MessageEntity::getContent, MessageEntity::getCreateTime)
                .eq(MessageEntity::getConversationId, conversationId)
                .eq(MessageEntity::getDeleted, 0)
                .eq(MessageEntity::getStatus, "completed")
                .in(MessageEntity::getRole, List.of("user", "assistant"))
                .lt(beforeId != null, MessageEntity::getId, beforeId)
                .orderByDesc(MessageEntity::getId)
                .last("LIMIT " + MAX_MESSAGES));

        // De-duplicate before projecting: warm rows are deliberately rewritten.
        if (beforeId == null && !rows.isEmpty() && "user".equals(rows.getFirst().getRole())
                && java.util.Objects.equals(originalInput, rows.getFirst().getContent())) {
            rows = new ArrayList<>(rows.subList(1, rows.size()));
        }
        if (channelHistory) {
            rows = channelHistoryPolicy.select(rows.reversed(), now).reversed();
            currentInput = channelHistoryPolicy.guidance(now) + "\n\n" + currentInput;
        }

        List<HistoricalMessage> selected = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            MessageEntity row = rows.get(index);
            String content = row.getContent();
            if (content == null || content.isBlank()) continue;
            if (channelHistory) content = "[Historical message at " + row.getCreateTime() + "]\n" + content;
            selected.addFirst(new HistoricalMessage(row.getRole(), content));
            if (TokenEstimator.estimateTokens(frame(selected)) <= MAX_HISTORY_TOKENS) continue;

            // Retain as much of the boundary message as fits, including JSON
            // escaping and framing in the estimate. Keep the current input intact.
            int low = 0;
            int high = Math.min(content.length(), MAX_HISTORY_TOKENS * 4);
            while (low < high) {
                int mid = (low + high + 1) / 2;
                selected.set(0, new HistoricalMessage(row.getRole(), prefix(content, mid) + TRUNCATED));
                if (TokenEstimator.estimateTokens(frame(selected)) <= MAX_HISTORY_TOKENS) low = mid;
                else high = mid - 1;
            }
            if (low == 0) selected.removeFirst();
            else selected.set(0, new HistoricalMessage(row.getRole(), prefix(content, low) + TRUNCATED));
            break;
        }
        return selected.isEmpty() ? currentInput : frame(selected) + currentInput;
    }

    private static String prefix(String content, int length) {
        if (length > 0 && length < content.length() && Character.isHighSurrogate(content.charAt(length - 1))) {
            length--;
        }
        return content.substring(0, length);
    }

    private String frame(List<HistoricalMessage> messages) {
        try {
            return PREFIX + objectMapper.writeValueAsString(messages) + SUFFIX;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to encode DSH conversation history", error);
        }
    }

    private record HistoricalMessage(String role, String content) {}
}

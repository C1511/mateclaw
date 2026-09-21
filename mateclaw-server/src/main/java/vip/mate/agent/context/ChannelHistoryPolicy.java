package vip.mate.agent.context;

import vip.mate.config.ChannelHistoryProperties;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Non-destructive projection of persisted channel history into model context. */
public class ChannelHistoryPolicy {
    private final ChannelHistoryProperties properties;

    public ChannelHistoryPolicy(ChannelHistoryProperties properties) {
        this.properties = properties;
    }

    public boolean applies(ChatOrigin origin) {
        return properties.isEnabled() && origin != null && !origin.cronOrigin()
                && origin.channelType() != null && !origin.channelType().isBlank()
                && !"web".equals(origin.channelType());
    }

    public String guidance(LocalDateTime now) {
        return "[Channel history freshness; current server local time: " + now + "] "
                + "Conversation history, summaries and recalled memories are historical context, not current evidence. "
                + "For current/latest/today's business data, query the authoritative source in this turn, "
                + "even if a recent answer exists. Report the query time and data period. "
                + "If querying fails or is unavailable, say current data could not be verified; never substitute old values. "
                + "Use historical snapshots only when explicitly requested and label their time. "
                + "Older answers and tool results have been withheld; retained old questions are reference context only.";
    }

    public boolean isHot(MessageEntity row, LocalDateTime now) {
        return row.getCreateTime() != null && !row.getCreateTime().isAfter(now)
                && !row.getCreateTime().isBefore(now.minusMinutes(Math.max(0, properties.getHotMinutes())));
    }

    /**
     * Input is chronological, excluding the current user row. Age whole turns
     * together so a cutoff never separates an assistant tool call from its result.
     * Summary timestamps describe compression time, not the age of their facts:
     * never let a newly generated summary make old query results fresh again.
     */
    public List<MessageEntity> select(List<MessageEntity> rows, LocalDateTime now) {
        List<MessageEntity> result = new ArrayList<>();
        List<MessageEntity> turn = new ArrayList<>();
        for (MessageEntity row : rows) {
            if ("system".equals(row.getRole())) continue;
            if ("user".equals(row.getRole()) && !turn.isEmpty()) {
                appendTurn(result, turn, now);
                turn.clear();
            }
            turn.add(row);
        }
        appendTurn(result, turn, now);
        return result;
    }

    private void appendTurn(List<MessageEntity> result, List<MessageEntity> turn, LocalDateTime now) {
        if (turn.isEmpty()) return;
        LocalDateTime oldest = now;
        for (MessageEntity row : turn) {
            // Legacy rows without provenance cannot be classified as fresh.
            if (row.getCreateTime() == null || row.getCreateTime().isAfter(now)) return;
            if (row.getCreateTime().isBefore(oldest)) oldest = row.getCreateTime();
        }
        long hot = Math.max(0, properties.getHotMinutes());
        long warm = Math.max(hot, properties.getWarmMinutes());
        if (oldest.isBefore(now.minusMinutes(warm))) return;
        if (!oldest.isBefore(now.minusMinutes(hot))) {
            result.addAll(turn);
            return;
        }
        // Do not retain assistant paraphrases of stale results, tool arguments,
        // structured content parts or metadata containing the same payload.
        for (MessageEntity row : turn) {
            if (!"user".equals(row.getRole()) || row.getContent() == null || row.getContent().isBlank()) continue;
            result.add(textRow(row, "user", "[Historical request at " + row.getCreateTime()
                    + "; not current data]\n" + row.getContent()));
            result.add(textRow(row, "assistant", "[Previous answer and tool results expired. "
                    + "Query the source again if needed; do not reconstruct old values.]"));
        }
    }

    private static MessageEntity textRow(MessageEntity original, String role, String text) {
        MessageEntity row = new MessageEntity();
        row.setRole(role);
        row.setContent(text);
        row.setStatus("completed");
        row.setCreateTime(original.getCreateTime());
        return row;
    }
}

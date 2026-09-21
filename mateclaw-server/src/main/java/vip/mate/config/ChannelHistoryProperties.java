package vip.mate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Wall-clock history limits, independent of storage retention and token budgets. */
@Data
@Component
@ConfigurationProperties(prefix = "mate.agent.channel-history")
public class ChannelHistoryProperties {
    private boolean enabled = true;
    private long hotMinutes = 30;
    private long warmMinutes = 1440;
}

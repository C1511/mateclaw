package vip.mate.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.goal.service.ManagedGoalJsonService;

/** Runtime may produce versions, but only the authenticated user can configure requirements. */
@Component
@RequiredArgsConstructor
public class ManagedGoalJsonTool {
    private final ManagedGoalJsonService artifacts;
    private final ObjectMapper json;

    @Tool(description = "Read the current conversation goal's managed JSON artifact slots and generations. "
            + "Only user-selected slots appear. Preserve generation strings exactly. This does not check or complete the goal.")
    public String getManagedGoalJsonSlots(ToolContext context) throws JsonProcessingException {
        return json.writeValueAsString(artifacts.listForRuntime(ChatOrigin.from(context)));
    }

    @Tool(description = "Publish a new immutable JSON object version to a user-selected slot of the current goal. "
            + "Read current slots first; use generation 0 for an empty slot. Maximum 1 MiB UTF-8 per version, "
            + "32 versions per goal, valid for 24 hours. Reload on generation conflict. "
            + "Publishing does not check requirements or complete the goal; workspace files and textual claims are not substitutes.")
    public String publishManagedGoalJson(
            @ToolParam(description = "An existing user-selected artifact slot") String artifactSlot,
            @ToolParam(description = "Exact current generation string, or 0 for an empty slot") String expectedGeneration,
            @ToolParam(description = "Raw strict JSON object content; not a file path") String jsonContent,
            ToolContext context) throws JsonProcessingException {
        Long generation;
        try { generation = Long.valueOf(expectedGeneration); }
        catch (RuntimeException invalid) { throw new vip.mate.exception.MateClawException(400, "A valid expectedGeneration is required"); }
        return json.writeValueAsString(artifacts.publishForRuntime(ChatOrigin.from(context), artifactSlot,
                new ManagedGoalJsonService.PublishRequest(generation, jsonContent)));
    }
}

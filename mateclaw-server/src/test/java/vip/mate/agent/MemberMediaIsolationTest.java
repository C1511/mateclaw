package vip.mate.agent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ChatOriginHolder;
import vip.mate.llm.routing.MediaCaptionService;
import vip.mate.llm.routing.MultimodalRouter;
import vip.mate.llm.routing.model.MultimodalRoutingDecision;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.core.service.MemberFileIsolation;

import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemberMediaIsolationTest {
    @TempDir Path directory;
    Path root;
    TestAgent agent;

    @BeforeEach void setUp() throws Exception {
        root = Files.createDirectory(directory.resolve("alice")).toRealPath();
        MemberFileIsolation.configure(origin -> origin.withWorkspace(1L, root.toString()));
        ChatOriginHolder.set(ChatOrigin.web("c1", "alice", 1L, null, null, 10L));
        agent = new TestAgent(mock(ConversationService.class));
    }

    @AfterEach void tearDown() {
        ChatOriginHolder.clear();
        MemberFileIsolation.configure(null);
    }

    @Test void resolvesOwnedRelativeUploadAndRejectsPeerOrSymlink() throws Exception {
        Path own = Files.writeString(root.resolve("image.jpg"), "own");
        Path peer = Files.writeString(directory.resolve("secret.jpg"), "peer");
        Files.createSymbolicLink(root.resolve("link.jpg"), peer);
        assertEquals(own, agent.resolveImagePath("image.jpg"));
        assertNull(agent.resolveImagePath(peer.toString()));
        assertNull(agent.resolveImagePath("link.jpg"));
    }

    @Test void currentAndCarriedMediaNeverReadPeerPathOrMediaId() throws Exception {
        Path peer = Files.writeString(directory.resolve("secret.jpg"), "peer");
        Files.createSymbolicLink(root.resolve("link.jpg"), peer);
        for (String path : List.of(peer.toString(), root.resolve("link.jpg").toString())) {
            for (boolean mediaId : List.of(false, true)) {
                MessageContentPart part = image(mediaId ? null : path);
                if (mediaId) part.setMediaId(path);
                assertTrue(build(part, false).getMedia().isEmpty());
                assertTrue(build(part, true).getMedia().isEmpty());
            }
        }
    }

    @Test void ownedMediaIsSnapshotOrFailsClosedWithoutSecureDirectoryStreams() throws Exception {
        Path own = Files.writeString(root.resolve("image.jpg"), "original");
        boolean secure;
        try (var stream = Files.newDirectoryStream(root)) { secure = stream instanceof SecureDirectoryStream; }
        for (boolean carry : List.of(false, true)) {
            UserMessage result = build(image("image.jpg"), carry);
            if (!secure) {
                assertTrue(result.getMedia().isEmpty());
                continue;
            }
            assertEquals(1, result.getMedia().size());
            Files.writeString(own, "changed");
            assertArrayEquals("original".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    result.getMedia().getFirst().getDataAsByteArray());
            Files.writeString(own, "original");
        }
    }

    @Test void strictModeDoesNotInvokeUnconfinedCaptionSidecar() throws Exception {
        agent.multimodalRouter = mock(MultimodalRouter.class);
        agent.mediaCaptionService = mock(MediaCaptionService.class);
        when(agent.multimodalRouter.route(any(), any())).thenReturn(MultimodalRoutingDecision.sidecar(
                new ModelConfigEntity(), Set.of(), Set.of()));
        Path peer = Files.writeString(directory.resolve("secret.jpg"), "peer");
        UserMessage result = build(image(peer.toString()), false);
        verifyNoInteractions(agent.mediaCaptionService);
        assertTrue(result.getText().contains("成员文件隔离模式暂不支持图片旁路识别"));
    }

    private UserMessage build(MessageContentPart part, boolean carry) {
        MessageEntity first = new MessageEntity(); first.setRole("user"); first.setContent("image");
        MessageEntity follow = new MessageEntity(); follow.setRole("user"); follow.setContent("question");
        when(agent.conversationService.listMessages("c1")).thenReturn(carry ? List.of(first, follow) : List.of(first));
        when(agent.conversationService.parseMessageParts(first)).thenReturn(List.of(part));
        when(agent.conversationService.parseMessageParts(follow)).thenReturn(List.of());
        when(agent.conversationService.renderMessageContent(any())).thenReturn("question");
        return agent.buildCurrentUserMessageWithRouting("c1", "question").userMessage();
    }

    private static MessageContentPart image(String path) {
        MessageContentPart part = new MessageContentPart();
        part.setType("image"); part.setContentType("image/jpeg"); part.setFileName("image.jpg"); part.setPath(path);
        return part;
    }

    static class TestAgent extends BaseAgent {
        TestAgent(ConversationService conversations) {
            super(null, conversations);
            modelCapabilities = EnumSet.of(ModelCapabilityService.Modality.VISION, ModelCapabilityService.Modality.TEXT);
            agentName = "test"; modelName = "test";
        }
        @Override public String chat(String message, String conversation) { throw new UnsupportedOperationException(); }
        @Override public reactor.core.publisher.Flux<String> chatStream(String message, String conversation) { throw new UnsupportedOperationException(); }
        @Override public String execute(String goal, String conversation) { throw new UnsupportedOperationException(); }
    }
}

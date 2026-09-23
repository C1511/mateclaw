package vip.mate.workspace.core.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.tool.document.*;
import vip.mate.workspace.core.config.ChatUploadProperties;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.agent.AgentService;
import org.springframework.security.authentication.TestingAuthenticationToken;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberFileIntegrationTest {
    @TempDir Path root;
    @BeforeEach void enable() {
        MemberFileIsolation.configure(origin -> {
            if(origin.requesterUserId() == null) throw new SecurityException("missing identity");
            Path member = root.resolve(origin.requesterUserId().toString());
            try { Files.createDirectories(member); } catch(Exception e) { throw new RuntimeException(e); }
            return origin.withWorkspace(7L, member.toString());
        });
    }
    @AfterEach void reset() { MemberFileIsolation.configure(null); }
    @Test void peerCannotDownloadGeneratedResultEvenAsAdmin() {
        var cache = new GeneratedFileCache(root.resolve("cache"));
        String id = cache.put("secret".getBytes(), "report.txt", "text/plain", new GeneratedFileCache.Owner(7L, 22L, "c22"));
        var auth = mock(AuthService.class);
        var user = new UserEntity(); user.setId(11L); user.setRole("admin");
        when(auth.findByUsername("alice")).thenReturn(user);
        var controller = new GeneratedFileController(cache, auth, mock(WorkspaceService.class));
        assertEquals(403, controller.download(id, 7L, new TestingAuthenticationToken("alice", "pw")).getStatusCode().value());
    }
    @Test void ownerCanDownloadOwnGeneratedResult() {
        var cache = new GeneratedFileCache(root.resolve("cache"));
        String id = cache.put("own".getBytes(), "report.txt", "text/plain", new GeneratedFileCache.Owner(7L, 11L, "c11"));
        var auth = mock(AuthService.class); var workspaces = mock(WorkspaceService.class);
        var user = new UserEntity(); user.setId(11L); user.setRole("user");
        when(auth.findByUsername("alice")).thenReturn(user);
        when(workspaces.hasPermissionCached(7L,11L,"viewer")).thenReturn(true);
        assertEquals(200, new GeneratedFileController(cache, auth, workspaces).download(id, 7L,
            new TestingAuthenticationToken("alice", "pw")).getStatusCode().value());
    }
    @Test void uploadsBeforeConversationCreationAreMemberScoped() {
        var resolver = new ChatUploadLocationResolver(mock(ConversationMapper.class), mock(WorkspaceService.class),
                new ChatUploadProperties(), mock(AgentService.class));
        var alice = ChatOrigin.web("new", "alice", 7L, null, null, 11L);
        var bob = ChatOrigin.web("new", "bob", 7L, null, null, 22L);
        assertEquals(root.resolve("11/chat-uploads"), resolver.resolveUploadRoot(alice));
        assertNotEquals(resolver.resolveUploadRoot(alice), resolver.resolveUploadRoot(bob));
    }
    @Test void unresolvedUploadsNeverUseGlobalLegacyDirectory() {
        var resolver = new ChatUploadLocationResolver(mock(ConversationMapper.class), mock(WorkspaceService.class),
                new ChatUploadProperties(), mock(AgentService.class));
        assertThrows(SecurityException.class, () -> resolver.resolveCandidateUploadRoots("missing"));
    }
}

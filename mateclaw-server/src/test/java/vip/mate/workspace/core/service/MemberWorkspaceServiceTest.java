package vip.mate.workspace.core.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.core.config.WorkspaceSandboxProperties;
import vip.mate.tool.guard.WorkspacePathGuard;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberWorkspaceServiceTest {
    @TempDir Path directory;
    MemberWorkspaceService service;
    UserMapper users;
    WorkspaceService workspaces;
    ConversationMapper conversations;
    @BeforeEach void setup() throws Exception {
        directory = directory.toRealPath();
        var props = new WorkspaceSandboxProperties();
        props.setRoot(directory.toString());
        props.setMemberIsolationEnabled(true);
        users = mock(UserMapper.class);
        workspaces = mock(WorkspaceService.class);
        for (long id : new long[]{11, 22}) {
            UserEntity user = new UserEntity(); user.setId(id); user.setUsername("user" + id); user.setRole("user"); user.setEnabled(true);
            when(users.selectById(id)).thenReturn(user);
            when(workspaces.hasPermissionCached(7L, id, "viewer")).thenReturn(true);
        }
        conversations = mock(ConversationMapper.class);
        service = new MemberWorkspaceService(props, conversations, users, workspaces);
        MemberFileIsolation.configure(service::scope);
    }
    @AfterEach void cleanup() { MemberFileIsolation.configure(null); WorkspacePathGuard.clearTrustedRoots(); }
    ChatOrigin origin(long user) { return ChatOrigin.web("c" + user, "user" + user, 7L, "/shared", null, user); }
    @Test void membersUseDifferentRootsRegardlessOfAgentOverride() {
        var alice = service.scope(origin(11)); var bob = service.scope(origin(22));
        assertEquals(directory.resolve("workspaces/7/members/11").toString(), alice.workspaceBasePath());
        assertNotEquals(alice.workspaceBasePath(), bob.workspaceBasePath());
        assertTrue(Files.isDirectory(Path.of(alice.workspaceBasePath())));
    }
    @Test void identityAndMembershipAreRequired() {
        assertThrows(SecurityException.class, () -> service.scope(ChatOrigin.EMPTY));
        assertThrows(SecurityException.class, () -> service.scope(origin(11).withWorkspace(8L, "/shared")));
    }
    @Test void globalTrustCannotExposeAnotherMember() throws Exception {
        Path bob = Path.of(service.scope(origin(22)).workspaceBasePath());
        Path secret = Files.writeString(bob.resolve("secret.txt"), "private");
        WorkspacePathGuard.addTrustedRoot(directory.toString());
        assertThrows(IllegalArgumentException.class, () -> WorkspacePathGuard.validatePath(secret.toString(), origin(11).toToolContext()));
        assertThrows(IllegalArgumentException.class, () -> WorkspacePathGuard.validatePath("../22/secret.txt", origin(11).toToolContext()));
    }
    @Test void rejectsSymlinkToAnotherMemberIncludingNewFiles() throws Exception {
        Path alice = Path.of(service.scope(origin(11)).workspaceBasePath());
        Path bob = Path.of(service.scope(origin(22)).workspaceBasePath());
        Files.createSymbolicLink(alice.resolve("peer"), bob);
        assertThrows(IllegalArgumentException.class, () -> WorkspacePathGuard.validatePath("peer/new.txt", origin(11).toToolContext()));
        assertThrows(IllegalArgumentException.class, () -> WorkspacePathGuard.validatePath("peer", origin(11).toToolContext()));
    }
    @Test void acceptsOwnRelativeFiles() {
        Path file = WorkspacePathGuard.validatePath("outputs/report.txt", origin(11).toToolContext());
        assertEquals(directory.resolve("workspaces/7/members/11/outputs/report.txt"), file);
    }
    @Test void persistedExternalDisplayNameDoesNotAuthenticateAUser() {
        var conversation = new vip.mate.workspace.conversation.model.ConversationEntity();
        conversation.setWorkspaceId(7L); conversation.setUsername("user11");
        when(conversations.selectOne(any())).thenReturn(conversation);
        UserEntity account = users.selectById(11L);
        when(users.selectOne(any())).thenReturn(account);
        assertThrows(SecurityException.class, () -> service.scope(ChatOrigin.EMPTY.withConversationId("external")));
    }
    @Test void missingContextCannotFallBackToSharedPath() {
        assertThrows(SecurityException.class, () -> WorkspacePathGuard.validatePath("test.txt"));
    }
}

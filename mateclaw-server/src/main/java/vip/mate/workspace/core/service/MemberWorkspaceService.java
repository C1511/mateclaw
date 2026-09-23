package vip.mate.workspace.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.core.config.WorkspaceSandboxProperties;
import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;

/** Resolves private storage using server-owned account and conversation records. */
@Service
@RequiredArgsConstructor
public class MemberWorkspaceService {
    private final WorkspaceSandboxProperties properties;
    private final ConversationMapper conversations;
    private final UserMapper users;
    private final WorkspaceService workspaces;

    @PostConstruct void register() {
        MemberFileIsolation.configure(properties.isMemberIsolationEnabled() ? this::scope : null);
    }

    public ChatOrigin scope(ChatOrigin origin) {
        if (!properties.isMemberIsolationEnabled()) return origin;
        if (origin == null) throw denied();
        ConversationEntity conversation = origin.conversationId() == null ? null : conversations.selectOne(
                new LambdaQueryWrapper<ConversationEntity>().eq(ConversationEntity::getConversationId, origin.conversationId()));
        if (conversation != null && Integer.valueOf(1).equals(conversation.getDeleted())) throw denied();
        Long workspace = conversation != null ? conversation.getWorkspaceId() : origin.workspaceId();
        if (workspace == null || (origin.workspaceId() != null && !Objects.equals(workspace, origin.workspaceId()))) throw denied();
        Long userId = origin.requesterUserId();
        // A persisted username may be an IM display name, not an authenticated account.
        // Old approvals and channel callers without a trusted numeric identity fail closed.
        if (userId == null) throw denied();
        UserEntity user = users.selectById(userId);
        if (user == null || user.getId() == null || !Boolean.TRUE.equals(user.getEnabled())
                || Integer.valueOf(1).equals(user.getDeleted())) throw denied();
        if (conversation != null && !Objects.equals(user.getUsername(), conversation.getUsername())) throw denied();
        if (!"admin".equalsIgnoreCase(user.getRole()) && !workspaces.hasPermissionCached(workspace, user.getId(), "viewer")) throw denied();
        Path root = privateRoot(workspace, user.getId());
        return origin.withRequesterUserId(user.getId()).withWorkspace(workspace, root.toString());
    }

    private Path privateRoot(Long workspace, Long user) {
        if (workspace <= 0 || user <= 0) throw denied();
        try {
            Path base = Path.of(properties.getRoot()).toAbsolutePath().normalize();
            Files.createDirectories(base);
            // The configured root is operator-owned and may itself be a mount/symlink.
            base = base.toRealPath();
            Path root = base.resolve("workspaces").resolve(workspace.toString()).resolve("members").resolve(user.toString());
            Path current = base;
            for (Path part : base.relativize(root)) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current)) throw denied();
                Files.createDirectories(current);
            }
            return root;
        } catch (IOException e) {
            throw new SecurityException("Cannot create member workspace", e);
        }
    }
    private static SecurityException denied() { return new SecurityException("Member workspace identity or permission is missing or inconsistent"); }
}

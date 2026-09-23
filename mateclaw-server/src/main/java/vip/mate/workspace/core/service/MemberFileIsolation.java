package vip.mate.workspace.core.service;

import org.springframework.ai.chat.model.ToolContext;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.tool.builtin.ToolExecutionContext;

import java.nio.file.*;
import java.io.IOException;
import java.util.Set;
import java.util.function.Function;

/** Shared fail-closed policy for opt-in member file isolation. */
public final class MemberFileIsolation {
    private static volatile Function<ChatOrigin, ChatOrigin> resolver;
    private static final Set<String> AUDITED_TOOLS = Set.of(
            "read_file", "write_file", "append_file", "edit_file", "execute_shell_command", "execute_code");
    private MemberFileIsolation() {}

    public static void configure(Function<ChatOrigin, ChatOrigin> value) { resolver = value; }
    public static boolean isEnabled() { return resolver != null; }
    public static ChatOrigin scope(ChatOrigin origin) {
        var current = resolver;
        return current == null ? origin : current.apply(origin == null ? ChatOrigin.EMPTY : origin);
    }
    public static Path root(ToolContext context) {
        ChatOrigin origin = context == null ? ToolExecutionContext.origin() : ChatOrigin.from(context);
        ChatOrigin scoped = scope(origin);
        if (scoped == null || scoped.workspaceBasePath() == null) {
            throw new SecurityException("Member file isolation requires an authenticated member identity");
        }
        return Path.of(scoped.workspaceBasePath()).toAbsolutePath().normalize();
    }
    public static void requireAuditedTool(String name) {
        if (isEnabled() && !AUDITED_TOOLS.contains(name)) {
            throw new SecurityException("Tool is not available in member file isolation mode: " + name);
        }
    }
    /** Validate existing ancestors too: a new file under a symlink is an escape. */
    public static Path validate(Path root, String raw) {
        Path path = Path.of(raw);
        Path resolved = (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("Path is outside member workspace");
        Path cursor = root;
        if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("Member workspace cannot be a symbolic link");
        for (Path part : root.relativize(resolved)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("Symbolic links are not allowed in member file paths");
        }
        try {
            Path existing = resolved;
            while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
            if (!existing.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalArgumentException("Path escapes member workspace");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot validate member file path", e);
        }
        return resolved;
    }
}

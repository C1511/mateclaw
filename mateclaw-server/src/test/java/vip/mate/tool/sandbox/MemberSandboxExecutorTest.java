package vip.mate.tool.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemberSandboxExecutorTest {
    @TempDir Path root;

    @Test void containerExposesOnlyMemberRootAndHasResourceBounds() throws Exception {
        var executor = new MemberSandboxExecutor("mateclaw-sandbox:local");
        var cmd = executor.command(List.of("python3", "script.py"), root, Map.of("OWN_SECRET", "value"), "test");
        assertTrue(cmd.containsAll(List.of("--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--network=none", "--pids-limit=64", "--memory=512m", "--cpus=1", "--pull=never")));
        assertEquals(1, Collections.frequency(cmd, "--mount"));
        assertTrue(cmd.contains("type=bind,src=" + root.toRealPath() + ",dst=" + root.toRealPath()));
        assertTrue(cmd.contains("OWN_SECRET=value"));
        assertFalse(cmd.stream().anyMatch(s -> s.contains("docker.sock")));
        assertEquals("python3", cmd.get(cmd.indexOf("--entrypoint") + 1));
        assertEquals("script.py", cmd.getLast());
    }

    @Test void invalidInputsFailBeforeDockerStarts() {
        var executor = new MemberSandboxExecutor("mateclaw-sandbox:local");
        assertThrows(IllegalArgumentException.class, () -> executor.execute(List.of(), root, Map.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> executor.executeCode("ruby", "puts 1", root, List.of(), Map.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> executor.command(List.of("true"), root, Map.of("BAD=KEY", "x"), "test"));
    }

    @Test void unavailableDockerNeverExecutesOnHostAndCleansScript() throws Exception {
        Path marker = root.resolve("host-executed");
        var executor = new MemberSandboxExecutor("mateclaw-sandbox:local", root.resolve("missing-docker").toString());
        assertThrows(java.io.IOException.class, () -> executor.executeCode("bash", "touch " + marker, root, List.of(), Map.of(), 1));
        assertFalse(Files.exists(marker));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }

    @Test void timeoutForceRemovesContainer() throws Exception {
        Path log = root.resolve("docker.log");
        Path fake = fakeDocker("echo \"$@\" >> '" + log + "'\nif [ \"$1\" = run ]; then exec sleep 30; fi\n");
        var result = new MemberSandboxExecutor("image", fake.toString()).execute(List.of("true"), root, Map.of(), 1);
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
        assertTrue(Files.readString(log).contains("rm --force mateclaw-member-"));
    }

    @Test void outputIsDrainedButRetainedWithinBound() throws Exception {
        Path fake = fakeDocker("if [ \"$1\" = run ]; then exec python3 -c 'import sys; print(\"a\" * 2000000); sys.stderr.write(\"b\" * 2000000)'; fi\n");
        var result = new MemberSandboxExecutor("image", fake.toString()).execute(List.of("true"), root, Map.of(), 15);
        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().endsWith("[output truncated at 1 MiB]"));
        assertTrue(result.stderr().endsWith("[output truncated at 1 MiB]"));
        assertTrue(result.stdout().length() < MemberSandboxExecutor.OUTPUT_LIMIT + 100);
        assertTrue(result.stderr().length() < MemberSandboxExecutor.OUTPUT_LIMIT + 100);
    }

    @Test void cancellationForceRemovesContainer() throws Exception {
        Path log = root.resolve("docker.log");
        Path fake = fakeDocker("echo \"$@\" >> '" + log + "'\nif [ \"$1\" = run ]; then exec sleep 30; fi\n");
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try { new MemberSandboxExecutor("image", fake.toString()).execute(List.of("true"), root, Map.of(), 30); }
            catch (Throwable e) { failure.set(e); }
        });
        worker.start();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(log) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(log));
        worker.interrupt();
        worker.join(15000);
        assertFalse(worker.isAlive());
        assertInstanceOf(InterruptedException.class, failure.get());
        assertTrue(Files.readString(log).contains("rm --force mateclaw-member-"));
    }

    @Test void inlineSourceUsesStdinWithoutCreatingHostScript() throws Exception {
        Path fake = fakeDocker("if [ \"$1\" = run ]; then cat; fi\n");
        var result = new MemberSandboxExecutor("image", fake.toString())
                .executeCode("python", "print('sandbox source')", root, List.of("arg"), Map.of(), 10);
        assertEquals("print('sandbox source')", result.stdout());
        try (var files = Files.list(root)) { assertEquals(List.of(fake), files.toList()); }
    }

    private Path fakeDocker(String body) throws Exception {
        Path script = root.resolve("fake-docker-" + UUID.randomUUID());
        Files.writeString(script, "#!/bin/sh\n" + body);
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }
}

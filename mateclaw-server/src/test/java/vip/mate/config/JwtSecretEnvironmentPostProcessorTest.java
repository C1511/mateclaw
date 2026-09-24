package vip.mate.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【安全加固】JWT 密钥兜底处理器测试：空密钥 / 公开默认密钥必须被替换为持久化的随机密钥，
 * 用户显式配置的密钥不得被覆盖。
 */
class JwtSecretEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    private String originalUserDir;
    private JwtSecretEnvironmentPostProcessor processor;

    @BeforeEach
    void setUp() {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        processor = new JwtSecretEnvironmentPostProcessor(new DeferredLogs());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void emptySecretIsReplacedAndPersisted() throws Exception {
        MockEnvironment env = new MockEnvironment().withProperty("mateclaw.jwt.secret", "");

        processor.postProcessEnvironment(env, null);

        String secret = env.getProperty("mateclaw.jwt.secret");
        assertThat(secret).isNotBlank().hasSizeGreaterThanOrEqualTo(64);
        Path file = tempDir.resolve("data").resolve(".jwt-secret");
        assertThat(Files.readString(file).trim()).isEqualTo(secret);
    }

    @Test
    void knownDefaultSecretIsReplaced() {
        MockEnvironment env = new MockEnvironment().withProperty("mateclaw.jwt.secret",
                "MateClaw-JWT-Secret-Key-2024-Please-Change-In-Production");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("mateclaw.jwt.secret"))
                .isNotEqualTo("MateClaw-JWT-Secret-Key-2024-Please-Change-In-Production");
    }

    @Test
    void generatedSecretIsReusedAcrossRestarts() {
        MockEnvironment first = new MockEnvironment().withProperty("mateclaw.jwt.secret", "");
        processor.postProcessEnvironment(first, null);

        MockEnvironment second = new MockEnvironment().withProperty("mateclaw.jwt.secret", "");
        processor.postProcessEnvironment(second, null);

        assertThat(second.getProperty("mateclaw.jwt.secret"))
                .isEqualTo(first.getProperty("mateclaw.jwt.secret"));
    }

    @Test
    void explicitSecretIsKept() {
        MockEnvironment env = new MockEnvironment().withProperty("mateclaw.jwt.secret",
                "my-own-secret-0123456789-abcdefghijklmnop");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("mateclaw.jwt.secret"))
                .isEqualTo("my-own-secret-0123456789-abcdefghijklmnop");
        assertThat(tempDir.resolve("data").resolve(".jwt-secret")).doesNotExist();
    }
}

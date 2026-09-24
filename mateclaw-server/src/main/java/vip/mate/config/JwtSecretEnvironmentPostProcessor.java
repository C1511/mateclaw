package vip.mate.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * 【安全加固】JWT 签名密钥兜底：禁止使用空密钥或公开的默认密钥签发登录令牌。
 *
 * <p>问题背景：
 * <ul>
 *   <li>docker-compose.yml 以 {@code JWT_SECRET: ${JWT_SECRET:-}} 传参，.env.example 中该项为空，
 *       按文档部署时容器内 JWT_SECRET 为空字符串。Spring 的 {@code ${JWT_SECRET:默认值}} 只在变量
 *       <b>不存在</b>时才用默认值，空字符串会被原样采用；AuthService 再把它补齐为 32 个零字节作密钥，
 *       任何人都能伪造管理员令牌，且 SecurityStartupValidator 不会给出任何警告。</li>
 *   <li>未设置 JWT_SECRET 时使用 application.yml 中写死的默认密钥，该值在公开代码里，同样可被伪造。</li>
 * </ul>
 *
 * <p>处理方式：配置文件加载完成后检查 {@code mateclaw.jwt.secret}，若为空或属于已知默认值，
 * 则生成 48 字节随机密钥并持久化到 {@code ${user.dir}/data/.jwt-secret}（Docker 下即 server_data 卷，
 * 桌面端即 userData 目录），重启后继续沿用，已登录用户不会被踢下线；以最高优先级覆盖原配置。
 * 用户显式设置的非默认密钥不受影响（过短时仅告警）。
 *
 * <p>多实例部署请务必显式设置同一个 JWT_SECRET，否则各实例生成的密钥不同，令牌无法互通。
 *
 * <p>注册位置：META-INF/spring.factories。本类为本地新增文件，合并上游时一般不会冲突。
 */
public class JwtSecretEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY = "mateclaw.jwt.secret";
    static final String PROPERTY_SOURCE_NAME = "mateclawGeneratedJwtSecret";
    static final String SECRET_FILE_NAME = ".jwt-secret";

    /** 代码中出现过的公开默认密钥（application.yml / AuthService / SsoStateService / WebChatController）。 */
    static final Set<String> KNOWN_DEFAULT_SECRETS = Set.of(
            "MateClaw-JWT-Secret-Key-2024-Please-Change-In-Production",
            "MateClaw-Secret-Key-2024-Very-Long-String");

    /** HMAC-SHA256 建议密钥至少 32 字节。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final Log log;

    public JwtSecretEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(JwtSecretEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty(PROPERTY);
        if (!isInsecure(configured)) {
            if (configured.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                log.warn("[安全加固] JWT_SECRET 长度不足 " + MIN_SECRET_BYTES
                        + " 字节，安全性较弱，建议使用 `openssl rand -base64 48` 生成。");
            }
            return;
        }

        Path secretFile = Paths.get(System.getProperty("user.dir"), "data", SECRET_FILE_NAME);
        String secret = loadOrCreate(secretFile);
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(PROPERTY, secret)));
    }

    static boolean isInsecure(String secret) {
        return secret == null || secret.isBlank() || KNOWN_DEFAULT_SECRETS.contains(secret.trim());
    }

    private String loadOrCreate(Path secretFile) {
        try {
            if (Files.isRegularFile(secretFile)) {
                String existing = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
                if (!isInsecure(existing)) {
                    log.warn("[安全加固] 未配置有效的 JWT_SECRET（为空或为公开默认值），已改用本地生成的随机密钥："
                            + secretFile + "。生产/多实例部署请显式设置 JWT_SECRET。");
                    return existing;
                }
            }
        } catch (IOException e) {
            log.warn("[安全加固] 读取 JWT 密钥文件失败：" + secretFile + "，将重新生成。原因：" + e.getMessage());
        }

        String generated = generateSecret();
        try {
            Files.createDirectories(secretFile.getParent());
            Files.writeString(secretFile, generated, StandardCharsets.UTF_8);
            restrictToOwner(secretFile);
            log.warn("[安全加固] 未配置有效的 JWT_SECRET（为空或为公开默认值），已生成随机密钥并保存到："
                    + secretFile + "。生产/多实例部署请显式设置 JWT_SECRET。");
        } catch (IOException e) {
            // 写盘失败仍使用随机密钥（安全优先），代价是重启后所有用户需要重新登录。
            log.warn("[安全加固] 无法保存 JWT 密钥文件：" + secretFile
                    + "，本次使用仅驻留内存的随机密钥，重启后用户需重新登录。原因：" + e.getMessage());
        }
        return generated;
    }

    static String generateSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 仅所有者可读写（Windows 等不支持 POSIX 权限的文件系统上静默跳过）。 */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // 非 POSIX 文件系统：依赖所在目录的访问控制
        }
    }

    /** 必须在 ConfigDataEnvironmentPostProcessor 加载完 application.yml 之后执行。 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

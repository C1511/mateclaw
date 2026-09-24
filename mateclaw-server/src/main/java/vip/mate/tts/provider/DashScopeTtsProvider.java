package vip.mate.tts.provider;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import java.nio.ByteBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tts.TtsProvider;
import vip.mate.tts.TtsRequest;
import vip.mate.tts.TtsResponseDiagnostics;
import vip.mate.tts.TtsResult;

import java.util.List;

/**
 * DashScope TTS Provider — 使用 CosyVoice 原生 WebSocket SDK
 * <p>
 * 同步模式，直接返回音频流。
 * 复用已有的 DashScope LLM provider 的 API Key。
 * API 文档: https://help.aliyun.com/zh/model-studio/developer-reference/cosyvoice-java-sdk
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeTtsProvider implements TtsProvider {

    private final ModelProviderService modelProviderService;

    private static final String DEFAULT_MODEL = "cosyvoice-v2";
    private static final String DEFAULT_VOICE = "longxiaochun_v2";

    @Override
    public String id() {
        return "dashscope";
    }

    @Override
    public String label() {
        return "DashScope (CosyVoice)";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 150;
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try {
            return modelProviderService.isProviderConfigured("dashscope");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> availableVoices() {
        return List.of(
                "longxiaochun_v2", "longxiaoxia_v2", "longshu_v2",
                "longhua_v2", "longwan_v2", "longcheng_v2"
        );
    }

    @Override
    public String defaultVoice() {
        return DEFAULT_VOICE;
    }

    @Override
    public TtsResult synthesize(TtsRequest request, SystemSettingsDTO config) {
        String apiKey = getDashScopeApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return TtsResult.failure("DashScope API Key 未配置");
        }

        try {
            String model = request.getModel() != null && !request.getModel().isBlank()
                    ? request.getModel() : DEFAULT_MODEL;
            String voice = request.getVoice() != null && !request.getVoice().isBlank()
                    ? request.getVoice() : DEFAULT_VOICE;

            if ((request.getVoice() == null || request.getVoice().isBlank())
                    && availableVoices().contains(config.getTtsDefaultVoice() == null ? "" : config.getTtsDefaultVoice())) {
                voice = config.getTtsDefaultVoice();
            }
            SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .voice(voice)
                    .format(SpeechSynthesisAudioFormat.MP3_24000HZ_MONO_256KBPS)
                    .speechRate(request.getSpeed() == null ? 1.0f : request.getSpeed().floatValue())
                    .build();
            SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);
            try {
                ByteBuffer audio = synthesizer.call(request.getText(), 60_000);
                if (audio == null || !audio.hasRemaining()) {
                    return TtsResult.failure("DashScope TTS 未返回音频数据");
                }
                byte[] audioData = new byte[audio.remaining()];
                audio.get(audioData);
                log.info("[DashScope TTS] Synthesized {} bytes (model={}, voice={})", audioData.length, model, voice);
                return TtsResult.success(audioData, "audio/mpeg", "mp3");
            } finally {
                synthesizer.getDuplexApi().close(1000, "bye");
            }
        } catch (Exception e) {
            log.error("[DashScope TTS] Error: {}", e.getMessage(), e);
            return TtsResult.failure("DashScope TTS 异常: " + TtsResponseDiagnostics.snippet(e.getMessage()));
        }
    }

    private String getDashScopeApiKey() {
        try {
            return modelProviderService.getProviderConfig("dashscope").getApiKey();
        } catch (Exception e) {
            return null;
        }
    }
}

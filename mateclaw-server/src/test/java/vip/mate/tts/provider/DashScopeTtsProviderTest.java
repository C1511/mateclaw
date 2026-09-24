package vip.mate.tts.provider;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import org.junit.jupiter.api.Test;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tts.TtsRequest;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashScopeTtsProviderTest {
    @Test
    void defaultVoiceMatchesCosyVoiceV2() {
        var provider = new DashScopeTtsProvider(mock(ModelProviderService.class));
        assertEquals("longxiaochun_v2", provider.defaultVoice());
        assertTrue(provider.availableVoices().contains(provider.defaultVoice()));
    }

    @Test
    void usesNativeSdkAndClosesConnection() {
        var service = mock(ModelProviderService.class, RETURNS_DEEP_STUBS);
        when(service.getProviderConfig("dashscope").getApiKey()).thenReturn("test-key");
        AtomicReference<SpeechSynthesisParam> parameters = new AtomicReference<>();
        try (var synths = mockConstruction(SpeechSynthesizer.class,
                withSettings().defaultAnswer(RETURNS_DEEP_STUBS), (synth, context) -> {
                    parameters.set((SpeechSynthesisParam) context.arguments().get(0));
                    doReturn(ByteBuffer.wrap(new byte[]{1, 2, 3})).when(synth).call("Hello", 60000);
                })) {
            // A default voice from another provider must not break DashScope fallback.
            var config = new SystemSettingsDTO();
            config.setTtsDefaultVoice("zh-CN-XiaoxiaoNeural");
            var result = new DashScopeTtsProvider(service).synthesize(
                    TtsRequest.builder().text("Hello").speed(1.5).build(), config);
            assertTrue(result.isSuccess(), result.getErrorMessage());
            assertArrayEquals(new byte[]{1, 2, 3}, result.getAudioData());
            assertEquals("cosyvoice-v2", parameters.get().getModel());
            assertEquals("longxiaochun_v2", parameters.get().getVoice());
            assertEquals(1.5f, parameters.get().getSpeechRate());
            assertEquals(SpeechSynthesisAudioFormat.MP3_24000HZ_MONO_256KBPS, parameters.get().getFormat());
            verify(synths.constructed().getFirst().getDuplexApi()).close(1000, "bye");
        }
    }
    @Test
    void closesConnectionWhenSynthesisFails() {
        var service = mock(ModelProviderService.class, RETURNS_DEEP_STUBS);
        when(service.getProviderConfig("dashscope").getApiKey()).thenReturn("test-key");
        try (var synths = mockConstruction(SpeechSynthesizer.class,
                withSettings().defaultAnswer(RETURNS_DEEP_STUBS), (synth, context) ->
                    doThrow(new IllegalStateException("invalid API key")).when(synth).call("Hello", 60000))) {
            var result = new DashScopeTtsProvider(service).synthesize(
                    TtsRequest.builder().text("Hello").build(), new SystemSettingsDTO());
            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("invalid API key"));
            verify(synths.constructed().getFirst().getDuplexApi()).close(1000, "bye");
        }
    }

    @Test
    void rejectsEmptyAudio() {
        var service = mock(ModelProviderService.class, RETURNS_DEEP_STUBS);
        when(service.getProviderConfig("dashscope").getApiKey()).thenReturn("test-key");
        try (var synths = mockConstruction(SpeechSynthesizer.class,
                withSettings().defaultAnswer(RETURNS_DEEP_STUBS), (synth, context) ->
                    doReturn(ByteBuffer.allocate(0)).when(synth).call("Hello", 60000))) {
            var result = new DashScopeTtsProvider(service).synthesize(
                    TtsRequest.builder().text("Hello").build(), new SystemSettingsDTO());
            assertFalse(result.isSuccess());
            verify(synths.constructed().getFirst().getDuplexApi()).close(1000, "bye");
        }
    }

}

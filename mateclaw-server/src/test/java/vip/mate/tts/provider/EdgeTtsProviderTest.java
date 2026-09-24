package vip.mate.tts.provider;

import org.junit.jupiter.api.Test;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tts.TtsRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EdgeTtsProviderTest {
    @Test
    void hashesWindowsFileTimeInFiveMinuteBuckets() throws Exception {
        assertEquals("7ECB79D14E3AA576D2D79E6D487A1388156D91E614B1BE11C64226A29BC8DD8C",
                EdgeTtsProvider.securityToken(java.time.Instant.EPOCH));
        assertEquals(EdgeTtsProvider.securityToken(java.time.Instant.EPOCH),
                EdgeTtsProvider.securityToken(java.time.Instant.ofEpochSecond(299)));
        assertNotEquals(EdgeTtsProvider.securityToken(java.time.Instant.EPOCH),
                EdgeTtsProvider.securityToken(java.time.Instant.ofEpochSecond(300)));
    }

    @Test
    void usesCurrentHandshakeAndPreservesFragmentedAudio() {
        HttpClient.Builder httpBuilder = mock(HttpClient.Builder.class, RETURNS_SELF);
        HttpClient client = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class, RETURNS_SELF);
        WebSocket socket = mock(WebSocket.class);
        when(httpBuilder.build()).thenReturn(client);
        when(client.newWebSocketBuilder()).thenReturn(wsBuilder);
        AtomicReference<URI> endpoint = new AtomicReference<>();
        AtomicReference<WebSocket.Listener> listener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(inv -> {
            endpoint.set(inv.getArgument(0));
            listener.set(inv.getArgument(1));
            return CompletableFuture.completedFuture(socket);
        });
        when(socket.sendText(anyString(), eq(true))).thenAnswer(inv -> {
            if (inv.<String>getArgument(0).contains("Path:ssml")) {
                byte[] headers = "Path:audio\r\nContent-Type:audio/mpeg\r\n".getBytes(StandardCharsets.UTF_8);
                byte[] packet = ByteBuffer.allocate(2 + headers.length + 4)
                        .putShort((short) headers.length).put(headers).put(new byte[]{1, 2, 3, 4}).array();
                listener.get().onBinary(socket, ByteBuffer.wrap(Arrays.copyOfRange(packet, 0, 1)), false);
                listener.get().onBinary(socket, ByteBuffer.wrap(Arrays.copyOfRange(packet, 1, packet.length)), true);
                listener.get().onText(socket, "Path:turn.end\r\n", true);
            }
            return CompletableFuture.completedFuture(socket);
        });
        try (var clients = mockStatic(HttpClient.class)) {
            clients.when(HttpClient::newBuilder).thenReturn(httpBuilder);
            var result = new EdgeTtsProvider().synthesize(
                    TtsRequest.builder().text("Hello").build(), new SystemSettingsDTO());
            assertEquals("/consumer/speech/synthesize/readaloud/edge/v1", endpoint.get().getPath());
            assertTrue(endpoint.get().getQuery().matches(".*Sec-MS-GEC=[A-F0-9]{64}.*"));
            assertTrue(endpoint.get().getQuery().contains("Sec-MS-GEC-Version=1-"));
            assertTrue(result.isSuccess(), result.getErrorMessage());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, result.getAudioData());
            verify(socket).abort();
        }
    }
}

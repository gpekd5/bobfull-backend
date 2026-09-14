package com.bobfull.chat.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cors.allowed-origins=http://localhost:5173",
        "jwt.secret=websocket-handshake-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=websocket-handshake-test-api-secret",
        "portone.store-id=websocket-handshake-test-store-id",
        "portone.webhook-secret=d2hzX3Rlc3Q="
})
class WebSocketHandshakeIntegrationTest {
    @LocalServerPort private int port;

    @Test
    void 허용된_Origin은_nativeWebSocket_handshake에_성공한다() throws Exception {
        // when
        var session = connect("http://localhost:5173");

        // then
        assertThat(session.isOpen()).isTrue();
        session.close();
    }

    @Test
    void 허용되지_않은_Origin은_nativeWebSocket_handshake가_거부된다() {
        // when & then
        assertThatThrownBy(() -> connect("https://not-allowed.example"))
                .hasCauseInstanceOf(Exception.class);
    }

    private org.springframework.web.socket.WebSocketSession connect(String origin) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        return new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(org.springframework.web.socket.WebSocketSession session, TextMessage message) {
                    }
                }, headers, URI.create("ws://localhost:" + port + "/ws"))
                .get(5, TimeUnit.SECONDS);
    }
}

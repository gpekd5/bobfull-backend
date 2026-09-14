package com.bobfull.chat.infrastructure.websocket;

import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

class WebSocketConfigTest {

    @Test
    void SimpleBroker와_applicationPrefix와_nativeWebSocket_endpoint를_등록한다() {
        // given
        WebSocketConfig config = new WebSocketConfig(List.of("http://localhost:5173"),
                org.mockito.Mockito.mock(com.bobfull.chat.infrastructure.websocket.ChatStompInterceptor.class),
                org.mockito.Mockito.mock(com.bobfull.chat.infrastructure.websocket.ChatOutboundAuthorizationInterceptor.class));
        MessageBrokerRegistry brokerRegistry = org.mockito.Mockito.mock(MessageBrokerRegistry.class);
        StompEndpointRegistry endpointRegistry = org.mockito.Mockito.mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration endpoint = org.mockito.Mockito.mock(StompWebSocketEndpointRegistration.class);
        org.mockito.Mockito.when(endpointRegistry.addEndpoint("/ws")).thenReturn(endpoint);
        org.mockito.Mockito.when(endpoint.setAllowedOrigins(org.mockito.ArgumentMatchers.any(String[].class))).thenReturn(endpoint);

        // when
        config.configureMessageBroker(brokerRegistry);
        config.registerStompEndpoints(endpointRegistry);

        // then
        verify(brokerRegistry).enableSimpleBroker("/sub");
        verify(brokerRegistry).setApplicationDestinationPrefixes("/pub");
        verify(endpointRegistry).addEndpoint("/ws");
        ArgumentCaptor<String[]> origins = ArgumentCaptor.forClass(String[].class);
        verify(endpoint).setAllowedOrigins(origins.capture());
        org.assertj.core.api.Assertions.assertThat(origins.getValue()).containsExactly("http://localhost:5173");
    }
}

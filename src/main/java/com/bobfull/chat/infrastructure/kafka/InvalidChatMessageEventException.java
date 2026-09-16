package com.bobfull.chat.infrastructure.kafka;

// eventVersion 불일치처럼 재시도로 해결되지 않는 Kafka 계약 위반을 나타낸다.
public class InvalidChatMessageEventException extends RuntimeException {
    public InvalidChatMessageEventException(String message) {
        super(message);
    }
}

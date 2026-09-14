package com.bobfull.chat.infrastructure.kafka;

/** eventVersion이 계약과 다른 등 재시도로 해결되지 않는 이벤트 계약 위반을 나타낸다. */
public class InvalidChatMessageEventException extends RuntimeException {
    public InvalidChatMessageEventException(String message) {
        super(message);
    }
}

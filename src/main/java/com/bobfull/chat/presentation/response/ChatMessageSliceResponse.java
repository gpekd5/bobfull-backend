package com.bobfull.chat.presentation.response;

import java.util.List;

public record ChatMessageSliceResponse(List<ChatMessageResponse> content, Long nextCursor) {
}

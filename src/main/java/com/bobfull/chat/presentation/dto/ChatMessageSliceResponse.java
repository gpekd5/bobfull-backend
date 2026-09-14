package com.bobfull.chat.presentation.dto;
import java.util.List;
public record ChatMessageSliceResponse(List<ChatMessageResponse> content, Long nextCursor) { }

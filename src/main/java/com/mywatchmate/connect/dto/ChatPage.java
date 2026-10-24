package com.mywatchmate.connect.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "A page of chat, oldest first. Pass olderCursor as `before` to load the page above.")
public record ChatPage(
    List<ChatMessageResponse> messages,
    @Schema(description = "Null when this is the start of the chat", nullable = true) String olderCursor
) {
}

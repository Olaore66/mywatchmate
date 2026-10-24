package com.mywatchmate.connect;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.config.OpenApiConfig;
import com.mywatchmate.connect.dto.ChatMessageRequest;
import com.mywatchmate.connect.dto.ChatMessageResponse;
import com.mywatchmate.connect.dto.ChatPage;
import com.mywatchmate.connect.dto.MemberMuted;
import com.mywatchmate.connect.dto.MuteRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Tag(name = "Chat", description = """
    One permanent chat per room (between sessions too; messages sent during a live session carry its
    `sessionId`). Everyone in the room, guests included, can chat. Live: **CHAT_MESSAGE**, **CHAT_DELETED**,
    **MEMBER_MUTED** / **MEMBER_UNMUTED** on `/topic/spaces/{id}/events`.

    - Up to 2,000 characters, 5 messages per 5 seconds per person.
    - No editing. Senders delete their own messages; the owner and admins delete anyone's. A deleted message
      stays in the list with `deleted: true` and no `body`: show "message deleted".
    - Hosts can mute someone (they can still watch, not send). Admins can't mute the owner or other admins.
    - **Typing and reactions** go over the WebSocket, not REST (never stored): SEND to
      `/app/spaces/{id}/typing` (no body, at most every 2 s) or `/app/spaces/{id}/reaction` with
      `{"emoji":"🙏"}` (one of 🙏 ❤️ 🔥 😂 👏 😮). The room gets TYPING / REACTION.
    - Unread: `unreadCount` in the room list and room details; `POST /chat/read` when the chat is on screen.""")
@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class ChatController {

    private final ChatService chat;

    @GetMapping("/chat")
    @Operation(summary = "Chat history", description = "The newest `limit` messages (default 50, max 100), oldest "
        + "first. To load the page above, pass the returned `olderCursor` as `before`; null means the start.")
    @ApiResponse(responseCode = "200", description = "Messages")
    @ApiResponse(responseCode = "400", description = "INVALID_CURSOR",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ChatPage history(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                            @PathVariable UUID spaceId,
                            @RequestParam(required = false) String before,
                            @RequestParam(required = false) Integer limit) {
        return chat.history(principal, spaceId, before, limit);
    }

    @PostMapping("/chat")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send a message", description = "Everyone in the room gets CHAT_MESSAGE (you too; it has "
        + "the same `id` as this response, so don't show it twice).")
    @ApiResponse(responseCode = "201", description = "Sent")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (blank, or over 2,000 characters)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "CHAT_MUTED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "SPACE_ARCHIVED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "CHAT_RATE_LIMITED",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ChatMessageResponse send(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                    @PathVariable UUID spaceId, @Valid @RequestBody ChatMessageRequest request) {
        return chat.send(principal, spaceId, request.body());
    }

    @DeleteMapping("/chat/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a message", description = "The sender, owner or admins. Everyone gets CHAT_DELETED.")
    @ApiResponse(responseCode = "204", description = "Deleted (or already was)")
    @ApiResponse(responseCode = "403", description = "NOT_YOUR_MESSAGE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND or MESSAGE_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void delete(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                       @PathVariable UUID spaceId, @PathVariable UUID messageId) {
        chat.delete(principal, spaceId, messageId);
    }

    @PostMapping("/chat/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "I've read the chat", description = "Call when the chat is on screen (and as new messages "
        + "arrive while it is). Resets `unreadCount` to 0.")
    @ApiResponse(responseCode = "204", description = "Marked read")
    public void markRead(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                         @PathVariable UUID spaceId) {
        chat.markRead(principal, spaceId);
    }

    @PostMapping("/participants/{participantId}/mute")
    @Operation(summary = "Mute someone in the chat (owner and admins)", description = "`minutes` up to 30 days; "
        + "leave it out to mute until unmuted. Everyone gets MEMBER_MUTED.")
    @ApiResponse(responseCode = "200", description = "Muted")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or CANNOT_TARGET_SELF",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND or PARTICIPANT_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public MemberMuted mute(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                            @PathVariable UUID spaceId, @PathVariable UUID participantId,
                            @Valid @RequestBody(required = false) MuteRequest request) {
        return chat.mute(principal, spaceId, participantId, request == null ? null : request.minutes());
    }

    @DeleteMapping("/participants/{participantId}/mute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unmute someone (owner and admins)", description = "Everyone gets MEMBER_UNMUTED.")
    @ApiResponse(responseCode = "204", description = "Unmuted (or wasn't muted)")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SPACE_NOT_FOUND or PARTICIPANT_NOT_FOUND",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public void unmute(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                       @PathVariable UUID spaceId, @PathVariable UUID participantId) {
        chat.unmute(principal, spaceId, participantId);
    }
}

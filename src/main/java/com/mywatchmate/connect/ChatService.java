package com.mywatchmate.connect;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.common.SlidingWindowLimiter;
import com.mywatchmate.connect.dto.ChatMessageResponse;
import com.mywatchmate.connect.dto.ChatPage;
import com.mywatchmate.connect.dto.MemberMuted;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceAccess.Level;
import com.mywatchmate.watch.SpaceAccess.Membership;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import com.mywatchmate.watch.dto.ParticipantResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A room's chat: one permanent conversation per room, between sessions too. Messages sent while a
 * session is live are tagged with it. Senders delete their own messages, hosts anyone's (shown as
 * "message deleted"); hosts can mute disruptive people for a while.
 */
@Service
public class ChatService {

    static final int MAX_PER_WINDOW = 5;
    static final Duration WINDOW = Duration.ofSeconds(5);
    static final int DEFAULT_PAGE = 50;
    static final int MAX_PAGE = 100;
    // "Until unmuted" is stored as a far-off date and shown as null
    static final Instant MUTED_INDEFINITELY = Instant.parse("9999-12-31T23:59:59Z");

    private final ChatMessageRepository messages;
    private final ParticipantRepository participants;
    private final WatchSessionRepository sessions;
    private final SpaceAccess access;
    private final SpaceEvents events;
    private final SlidingWindowLimiter limiter;
    private final Clock clock;

    @Autowired
    public ChatService(ChatMessageRepository messages, ParticipantRepository participants,
                       WatchSessionRepository sessions, SpaceAccess access, SpaceEvents events, Clock clock) {
        this(messages, participants, sessions, access, events, clock,
            new SlidingWindowLimiter(clock, MAX_PER_WINDOW, WINDOW, 10_000));
    }

    ChatService(ChatMessageRepository messages, ParticipantRepository participants, WatchSessionRepository sessions,
                SpaceAccess access, SpaceEvents events, Clock clock, SlidingWindowLimiter limiter) {
        this.messages = messages;
        this.participants = participants;
        this.sessions = sessions;
        this.access = access;
        this.events = events;
        this.clock = clock;
        this.limiter = limiter;
    }

    /** Newest page by default; with {@code before}, the page just above it. Oldest first. */
    @Transactional(readOnly = true)
    public ChatPage history(AuthPrincipal principal, UUID spaceId, String before, Integer limit) {
        access.require(principal, spaceId);
        int size = limit == null ? DEFAULT_PAGE : Math.clamp(limit, 1, MAX_PAGE);
        // One extra tells us whether there's anything older
        List<String> ids = before == null
            ? messages.findLatestIds(spaceId, size + 1)
            : findBefore(spaceId, ChatCursor.decode(before), size + 1);
        boolean hasOlder = ids.size() > size;
        List<UUID> page = ids.stream().limit(size).map(UUID::fromString).toList();
        if (page.isEmpty()) {
            return new ChatPage(List.of(), null);
        }
        List<ChatMessage> found = messages.findWithSenders(spaceId, page);
        String olderCursor = hasOlder ? ChatCursor.of(found.getFirst()).encode() : null;
        return new ChatPage(found.stream().map(ChatMessageResponse::from).toList(), olderCursor);
    }

    @Transactional
    public ChatMessageResponse send(AuthPrincipal principal, UUID spaceId, String body) {
        Membership membership = access.require(principal, spaceId).requireNotArchived();
        Participant sender = membership.participant();
        Instant now = clock.instant();
        if (sender.isChatMuted(now)) {
            throw ApiException.forbidden("CHAT_MUTED", sender.getChatMutedUntil().equals(MUTED_INDEFINITELY)
                ? "A host has muted you in this chat."
                : "A host has muted you in this chat until " + DateTimeFormatter.ISO_INSTANT.format(sender.getChatMutedUntil()) + ".");
        }
        if (!limiter.tryAcquire(sender.getId())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CHAT_RATE_LIMITED",
                "You're sending messages too fast. Wait a few seconds.");
        }
        WatchSession live = sessions.findFirstBySpaceIdAndStatus(spaceId, WatchSession.Status.LIVE).orElse(null);
        ChatMessage message = messages.saveAndFlush(new ChatMessage(membership.space(), live, sender, body.strip(), now));
        // Your own messages are never unread
        sender.markChatRead(message.getCreatedAt());
        ChatMessageResponse response = ChatMessageResponse.from(message);
        events.publish(spaceId, SpaceEvent.Type.CHAT_MESSAGE, response);
        return response;
    }

    /** The sender, or the owner/admins. Deleting twice is harmless. */
    @Transactional
    public void delete(AuthPrincipal principal, UUID spaceId, UUID messageId) {
        Membership membership = access.require(principal, spaceId);
        ChatMessage message = messages.findInSpace(spaceId, messageId)
            .orElseThrow(() -> ApiException.notFound("MESSAGE_NOT_FOUND", "That message isn't in this room."));
        if (!message.isFrom(membership.participant()) && !membership.isOwnerOrAdmin()) {
            throw ApiException.forbidden("NOT_YOUR_MESSAGE", "Only the sender, the owner or admins can delete a message.");
        }
        if (!message.isDeleted()) {
            message.delete(clock.instant(), membership.participant());
            events.publish(spaceId, SpaceEvent.Type.CHAT_DELETED, Map.of("messageId", messageId));
        }
    }

    /** Everything up to now counts as read for the caller. */
    @Transactional
    public void markRead(AuthPrincipal principal, UUID spaceId) {
        access.require(principal, spaceId).participant().markChatRead(clock.instant());
    }

    /** For the typing/reaction relay, which runs outside requests. */
    @Transactional(readOnly = true)
    public boolean isMuted(UUID participantId) {
        Instant now = clock.instant();
        return participants.findById(participantId).map(p -> p.isChatMuted(now)).orElse(true);
    }

    /** For room details. Call inside a transaction. */
    public long unreadCount(Participant participant) {
        return messages.countUnread(participant.getSpace().getId(), participant.getId(), participant.chatReadSince());
    }

    /** Owner or admins. Admins can't mute the owner or other admins. */
    @Transactional
    public MemberMuted mute(AuthPrincipal principal, UUID spaceId, UUID targetId, Integer minutes) {
        Membership actor = access.require(principal, spaceId).requireOwnerOrAdmin().requireNotArchived();
        Participant target = moderatable(actor, spaceId, targetId);
        Instant until = minutes == null ? MUTED_INDEFINITELY : clock.instant().plus(Duration.ofMinutes(minutes));
        target.muteChat(until);
        MemberMuted muted = new MemberMuted(ParticipantResponse.from(target), shownUntil(until));
        events.publish(spaceId, SpaceEvent.Type.MEMBER_MUTED, muted);
        return muted;
    }

    @Transactional
    public void unmute(AuthPrincipal principal, UUID spaceId, UUID targetId) {
        Membership actor = access.require(principal, spaceId).requireOwnerOrAdmin();
        Participant target = moderatable(actor, spaceId, targetId);
        if (target.isChatMuted(clock.instant())) {
            target.unmuteChat();
            events.publish(spaceId, SpaceEvent.Type.MEMBER_UNMUTED, Map.of("participantId", targetId));
        }
    }

    /** How a mute end is shown to clients: null for "until a host unmutes them". */
    public static Instant shownUntil(Instant until) {
        return until == null || until.equals(MUTED_INDEFINITELY) ? null : until;
    }

    private Participant moderatable(Membership actor, UUID spaceId, UUID targetId) {
        Participant target = participants.findById(targetId)
            .filter(p -> p.getSpace().getId().equals(spaceId) && p.isActive())
            .orElseThrow(() -> ApiException.notFound("PARTICIPANT_NOT_FOUND", "That person isn't in this room."));
        if (target.getId().equals(actor.participant().getId())) {
            throw ApiException.badRequest("CANNOT_TARGET_SELF", "You can't mute yourself.");
        }
        Level level = SpaceAccess.levelOf(target);
        if (level == Level.OWNER || (actor.level() == Level.ADMIN && level == Level.ADMIN)) {
            throw ApiException.forbidden("FORBIDDEN_ROLE", level == Level.OWNER
                ? "The owner can't be muted." : "Admins can't mute other admins.");
        }
        return target;
    }

    private List<String> findBefore(UUID spaceId, ChatCursor cursor, int limit) {
        return messages.findIdsBefore(spaceId, cursor.createdAt(), cursor.id(), limit);
    }
}

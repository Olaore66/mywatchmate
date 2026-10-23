package com.mywatchmate.notify;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.notify.dto.ReminderSettingsRequest;
import com.mywatchmate.notify.dto.ReminderSettingsResponse;
import com.mywatchmate.track.Reminder;
import com.mywatchmate.track.ReminderRepository;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceAccess.Level;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import com.mywatchmate.watch.dto.ParticipantResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps reminder rows in step with sessions and members: one EMAIL row per registered member
 * per scheduled session (if they want reminders). Sending is {@link ReminderDispatchJob}'s job.
 * Methods without {@code @Transactional} must be called inside one.
 */
@Service
@RequiredArgsConstructor
public class ReminderService {

    static final int DEFAULT_MINUTES = 10;

    private final ReminderRepository reminders;
    private final ParticipantRepository participants;
    private final WatchSessionRepository sessions;
    private final SpaceAccess access;
    private final Clock clock;

    /** A session was scheduled: remind everyone in the room who wants reminders. */
    public void scheduleFor(WatchSession session) {
        for (Participant member : participants.findBySpaceIdAndLeftAtIsNull(session.getSpace().getId())) {
            scheduleFor(session, member);
        }
    }

    /** Someone joined: remind them of the room's upcoming sessions. Guests get no reminders. */
    public void addMember(Participant member) {
        if (member.isGuest()) {
            return;
        }
        for (WatchSession session : sessions.findBySpaceIdAndStatusAndScheduledAtAfter(
                member.getSpace().getId(), WatchSession.Status.SCHEDULED, clock.instant())) {
            scheduleFor(session, member);
        }
    }

    /** Someone left or was removed: drop their unsent reminders for this room. */
    public void removeMember(Participant member) {
        if (!member.isGuest()) {
            reminders.deletePendingForUserInSpace(member.getUser().getId(), member.getSpace().getId(), Reminder.Status.PENDING);
        }
    }

    public void cancelForSession(WatchSession session) {
        reminders.cancelPendingForSession(session.getId(), Reminder.Status.PENDING, Reminder.Status.CANCELLED);
    }

    public void cancelForStoppedSchedule(UUID scheduleId) {
        reminders.cancelPendingForCancelledSessionsOf(scheduleId, Reminder.Status.PENDING, Reminder.Status.CANCELLED,
            WatchSession.Status.CANCELLED);
    }

    @Transactional(readOnly = true)
    public ReminderSettingsResponse settings(AuthPrincipal principal, UUID spaceId) {
        Participant me = registeredMember(principal, spaceId);
        return ReminderSettingsResponse.of(me);
    }

    /** Change my reminders for one room; upcoming sessions are re-planned straight away. */
    @Transactional
    public ReminderSettingsResponse updateSettings(AuthPrincipal principal, UUID spaceId, ReminderSettingsRequest request) {
        Participant me = registeredMember(principal, spaceId);
        me.setReminderMinutesBefore(request.enabled()
            ? (request.minutesBefore() == null ? DEFAULT_MINUTES : request.minutesBefore())
            : null);
        removeMember(me);
        addMember(me);
        return ReminderSettingsResponse.of(me);
    }

    /** Membership changes arrive as room events (same transaction), so membership code needn't know about reminders. */
    @EventListener
    public void onSpaceEvent(SpaceEvent event) {
        switch (event.type()) {
            case MEMBER_JOINED -> participantOf(event).ifPresent(this::addMember);
            case MEMBER_LEFT, MEMBER_REMOVED -> participantOf(event).ifPresent(this::removeMember);
            default -> {
                // not about membership
            }
        }
    }

    private void scheduleFor(WatchSession session, Participant member) {
        Integer minutes = member.getReminderMinutesBefore();
        Instant now = clock.instant();
        if (member.isGuest() || !member.isActive() || minutes == null || !session.isScheduled()
            || !session.getScheduledAt().isAfter(now)) {
            return;
        }
        UUID userId = member.getUser().getId();
        if (reminders.existsByUserIdAndSessionIdAndChannel(userId, session.getId(), Reminder.Channel.EMAIL)) {
            return;
        }
        // Created too late for the full lead time (e.g. a session 5 minutes away): send as soon as possible
        Instant sendAt = session.getScheduledAt().minus(Duration.ofMinutes(minutes));
        reminders.save(new Reminder(member.getUser(), session, Reminder.Channel.EMAIL, sendAt.isBefore(now) ? now : sendAt));
    }

    private Participant registeredMember(AuthPrincipal principal, UUID spaceId) {
        return access.require(principal, spaceId).requireLevel(Level.OWNER, Level.ADMIN, Level.MEMBER).participant();
    }

    private Optional<Participant> participantOf(SpaceEvent event) {
        return event.data() instanceof ParticipantResponse p ? participants.findById(p.id()) : Optional.empty();
    }
}

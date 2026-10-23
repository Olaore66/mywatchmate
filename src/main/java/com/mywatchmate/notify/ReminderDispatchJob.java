package com.mywatchmate.notify;

import com.mywatchmate.track.Reminder;
import com.mywatchmate.track.ReminderRepository;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.SpaceProperties;
import com.mywatchmate.watch.WatchSession;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends reminders that are due. Each run: lock due rows (other servers skip them), mark them SENT
 * (or CANCELLED if no longer wanted), commit, then send outside the transaction. Sending at most
 * once: a crash between commit and send loses that one reminder rather than emailing twice.
 * A send failure marks the reminder FAILED with the reason; it isn't retried.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderDispatchJob {

    static final int BATCH = 100;

    private final ReminderRepository reminders;
    private final ParticipantRepository participants;
    private final EmailSender email;
    private final SpaceProperties spaceProps;
    private final TransactionTemplate tx;
    private final Clock clock;

    record Outgoing(UUID reminderId, EmailMessage message) {
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "${mywatchmate.reminders.dispatch-interval:PT30S}")
    public int run() {
        List<Outgoing> due = tx.execute(status -> claimDue());
        int sent = 0;
        for (Outgoing out : due) {
            try {
                email.send(out.message());
                sent++;
            } catch (RuntimeException e) {
                log.warn("Reminder {} to {} failed: {}", out.reminderId(), out.message().to(), e.getMessage());
                tx.executeWithoutResult(status -> reminders.findById(out.reminderId()).ifPresent(r -> r.markFailed(e.getMessage())));
            }
        }
        return sent;
    }

    private List<Outgoing> claimDue() {
        Instant now = clock.instant();
        List<UUID> ids = reminders.lockDue(now, BATCH);
        List<Outgoing> out = new ArrayList<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Reminder reminder : reminders.findWithDetails(ids)) {
            if (!stillWanted(reminder)) {
                reminder.cancel();
                continue;
            }
            reminder.markSent(now);
            out.add(new Outgoing(reminder.getId(),
                ReminderEmail.render(reminder.getUser(), reminder.getSession(), spaceProps.frontendUrl(), now)));
        }
        return out;
    }

    /** Checked at send time, so switching reminders off (or leaving) takes effect immediately. */
    private boolean stillWanted(Reminder reminder) {
        WatchSession session = reminder.getSession();
        if (!(session.isScheduled() || session.isLive()) || session.getSpace().isArchived()) {
            return false;
        }
        if (!reminder.getUser().isEmailReminders()) {
            return false;
        }
        return participants.findBySpaceIdAndUserId(session.getSpace().getId(), reminder.getUser().getId())
            .filter(Participant::isActive)
            .filter(p -> p.getReminderMinutesBefore() != null)
            .isPresent();
    }
}

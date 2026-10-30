package com.mywatchmate.invite;

import com.mywatchmate.notify.EmailMessage;
import com.mywatchmate.notify.EmailSender;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceProperties;
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
 * Every 30 seconds, emails queued invitations: claimed in a transaction (locked, so a second server skips
 * them), sent outside it (a slow mail server never holds the database), retried on failure. The link
 * is the room's invite link at sending time, so rotating it never sends a dead one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InviteDispatchJob {

    static final int BATCH = 50;

    private final SpaceInvitationRepository invitations;
    private final EmailSender email;
    private final SpaceProperties spaceProps;
    private final InviteProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    record Outgoing(UUID invitationId, EmailMessage message) {
    }

    @Scheduled(initialDelayString = "PT25S", fixedDelayString = "${mywatchmate.invites.dispatch-interval:PT30S}")
    public int run() {
        List<Outgoing> due = tx.execute(status -> claimDue());
        int sent = 0;
        for (Outgoing out : due == null ? List.<Outgoing>of() : due) {
            try {
                email.send(out.message());
                sent++;
            } catch (RuntimeException e) {
                log.warn("Invitation {} to {} failed: {}", out.invitationId(), out.message().to(), e.getMessage());
                tx.executeWithoutResult(status -> invitations.findById(out.invitationId())
                    .ifPresent(i -> i.sendFailed(e.getMessage(), clock.instant(), props.maxAttempts(), props.retryDelay())));
            }
        }
        return sent;
    }

    private List<Outgoing> claimDue() {
        Instant now = clock.instant();
        List<UUID> ids = invitations.lockDue(now, BATCH).stream().map(UUID::fromString).toList();
        List<Outgoing> out = new ArrayList<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (SpaceInvitation invitation : invitations.findWithDetails(ids)) {
            Space space = invitation.getSpace();
            if (space.isArchived()) {
                invitation.cancel();
                continue;
            }
            if (!invitation.isLive(now)) {
                invitation.expire();
                continue;
            }
            invitation.sending(now);
            Participant inviter = invitation.getInvitedBy();
            out.add(new Outgoing(invitation.getId(), InviteEmail.render(invitation.getEmail(),
                inviter == null ? null : inviter.displayName(), space.getTitle(), invitation.getMessage(),
                spaceProps.frontendUrl() + "/join/" + space.getInviteToken())));
        }
        return out;
    }
}

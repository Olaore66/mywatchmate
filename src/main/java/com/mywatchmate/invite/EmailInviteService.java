package com.mywatchmate.invite;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.invite.dto.EmailInviteRequest;
import com.mywatchmate.invite.dto.EmailInviteResponse;
import com.mywatchmate.invite.dto.EmailInviteResult;
import com.mywatchmate.invite.dto.EmailInviteResult.Outcome;
import com.mywatchmate.invite.dto.InvitationResponse;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceAccess.Membership;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.dto.ParticipantResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inviting people to a room by email. The request only records the invitations (one per address per
 * room); {@link InviteDispatchJob} emails them the room's ordinary invite link. Owner and admins only,
 * with a daily limit per room: we send to whatever addresses are typed, so this can't become a spam tool.
 * When an invited person joins the room, their invitation is marked JOINED.
 */
@Service
@RequiredArgsConstructor
public class EmailInviteService {

    static final int MAX_NOTE = 500;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern LINK = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n]]");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private final SpaceInvitationRepository invitations;
    private final ParticipantRepository participants;
    private final SpaceAccess access;
    private final InviteProperties props;
    private final Clock clock;

    @Transactional
    public EmailInviteResponse invite(AuthPrincipal principal, UUID spaceId, EmailInviteRequest request) {
        Membership membership = access.require(principal, spaceId).requireOwnerOrAdmin().requireNotArchived();
        Instant now = clock.instant();
        String note = cleanNote(request.customMessage());

        // One result per distinct address, in the order given
        Set<String> addresses = new LinkedHashSet<>();
        request.emails().forEach(raw -> addresses.add(normalise(raw)));
        Set<String> members = participants.findActiveWithUsers(spaceId).stream()
            .filter(p -> !p.isGuest())
            .map(p -> normalise(p.getUser().getEmail()))
            .collect(Collectors.toSet());
        List<String> valid = addresses.stream().filter(EmailInviteService::isEmail).toList();
        Map<String, SpaceInvitation> waiting = valid.isEmpty() ? Map.of() : waitingFor(spaceId, valid, now);
        long remaining = props.maxPerDay() - queuedToday(spaceId, now);

        List<EmailInviteResult> results = new ArrayList<>();
        int queued = 0;
        for (String email : addresses) {
            if (!isEmail(email)) {
                results.add(new EmailInviteResult(email, Outcome.INVALID_EMAIL, null));
            } else if (members.contains(email)) {
                results.add(new EmailInviteResult(email, Outcome.ALREADY_MEMBER, null));
            } else if (waiting.containsKey(email)) {
                results.add(new EmailInviteResult(email, Outcome.ALREADY_INVITED, waiting.get(email).getId()));
            } else if (remaining <= 0) {
                results.add(new EmailInviteResult(email, Outcome.RATE_LIMITED, null));
            } else {
                SpaceInvitation saved = invitations.save(new SpaceInvitation(membership.space(), email,
                    membership.participant(), note, now, props.lifetime()));
                results.add(new EmailInviteResult(email, Outcome.QUEUED, saved.getId()));
                remaining--;
                queued++;
            }
        }
        return new EmailInviteResponse(results, queued);
    }

    /** Owner and admins: everyone invited by email, newest first. */
    @Transactional(readOnly = true)
    public List<InvitationResponse> list(AuthPrincipal principal, UUID spaceId) {
        access.require(principal, spaceId).requireOwnerOrAdmin();
        Instant now = clock.instant();
        return invitations.findForSpace(spaceId).stream().map(i -> InvitationResponse.from(i, now)).toList();
    }

    /** Send it again (sent, failed or expired ones), with a fresh expiry. */
    @Transactional
    public InvitationResponse resend(AuthPrincipal principal, UUID spaceId, UUID invitationId) {
        access.require(principal, spaceId).requireOwnerOrAdmin().requireNotArchived();
        SpaceInvitation invitation = find(spaceId, invitationId);
        Instant now = clock.instant();
        if (invitation.getStatus() == SpaceInvitation.Status.JOINED || invitation.getStatus() == SpaceInvitation.Status.CANCELLED) {
            throw ApiException.conflict("INVITATION_CLOSED", "This invitation was " + invitation.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        if (invitation.getLastQueuedAt().plus(props.resendCooldown()).isAfter(now)) {
            throw ApiException.conflict("RESEND_TOO_SOON", "It was sent just now. Try again in a few minutes.");
        }
        boolean alreadyMember = participants.findActiveWithUsers(spaceId).stream()
            .anyMatch(p -> !p.isGuest() && normalise(p.getUser().getEmail()).equals(invitation.getEmail()));
        if (alreadyMember) {
            throw ApiException.conflict("ALREADY_MEMBER", "They're already in the room.");
        }
        if (!invitation.isLive(now) && invitations.findWaiting(spaceId, List.of(invitation.getEmail())).stream()
                .anyMatch(other -> !other.getId().equals(invitationId) && other.isLive(now))) {
            throw ApiException.conflict("ALREADY_INVITED", "There's already a newer invitation to this address.");
        }
        if (queuedToday(spaceId, now) >= props.maxPerDay()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "INVITE_LIMIT_REACHED",
                "This room has sent its invitations for today. Try again tomorrow.");
        }
        invitation.queue(now, props.lifetime());
        return InvitationResponse.from(invitation, now);
    }

    /** It won't be sent (or shown as pending). The room's link itself keeps working. */
    @Transactional
    public void cancel(AuthPrincipal principal, UUID spaceId, UUID invitationId) {
        access.require(principal, spaceId).requireOwnerOrAdmin();
        SpaceInvitation invitation = find(spaceId, invitationId);
        if (invitation.getStatus() == SpaceInvitation.Status.PENDING || invitation.getStatus() == SpaceInvitation.Status.SENT) {
            invitation.cancel();
        }
    }

    /** Someone registered joined: if they were invited by email, that invitation is done. */
    @EventListener
    public void onSpaceEvent(SpaceEvent event) {
        if (event.type() != SpaceEvent.Type.MEMBER_JOINED || !(event.data() instanceof ParticipantResponse joined)
            || joined.guest()) {
            return;
        }
        // Updated as loaded entities (not a bulk update), so the rest of the join sees the change
        Instant now = clock.instant();
        participants.findById(joined.id())
            .map(Participant::getUser)
            .ifPresent(user -> invitations.findWaiting(event.spaceId(), List.of(normalise(user.getEmail())))
                .forEach(invitation -> invitation.joined(now)));
    }

    /**
     * The host's note as it will appear: plain text, links removed (an invitation from us mustn't carry
     * someone else's link), control characters and runs of blank lines dropped.
     */
    static String cleanNote(String note) {
        if (note == null) {
            return null;
        }
        String cleaned = LINK.matcher(note).replaceAll("[link removed]");
        cleaned = CONTROL.matcher(cleaned.replace("\r\n", "\n")).replaceAll("");
        cleaned = BLANK_LINES.matcher(cleaned).replaceAll("\n\n").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_NOTE ? cleaned.substring(0, MAX_NOTE).strip() : cleaned;
    }

    static String normalise(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    static boolean isEmail(String email) {
        return email.length() <= 254 && EMAIL.matcher(email).matches();
    }

    /** Waiting invitations for these addresses; ones past their expiry are closed so a new one can be made. */
    private Map<String, SpaceInvitation> waitingFor(UUID spaceId, List<String> emails, Instant now) {
        List<SpaceInvitation> found = invitations.findWaiting(spaceId, emails);
        boolean expiredAny = false;
        for (SpaceInvitation invitation : found) {
            if (!invitation.isLive(now)) {
                invitation.expire();
                expiredAny = true;
            }
        }
        if (expiredAny) {
            // Before inserting replacements: only one waiting invitation per address is allowed
            invitations.flush();
        }
        return found.stream().filter(i -> i.isLive(now))
            .collect(Collectors.toMap(SpaceInvitation::getEmail, Function.identity()));
    }

    private long queuedToday(UUID spaceId, Instant now) {
        return invitations.countBySpaceIdAndLastQueuedAtAfter(spaceId, now.minus(Duration.ofDays(1)));
    }

    private SpaceInvitation find(UUID spaceId, UUID invitationId) {
        return invitations.findByIdAndSpaceId(invitationId, spaceId)
            .orElseThrow(() -> ApiException.notFound("INVITATION_NOT_FOUND", "That invitation isn't in this room."));
    }
}

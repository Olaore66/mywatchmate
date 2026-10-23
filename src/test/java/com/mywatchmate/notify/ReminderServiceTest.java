package com.mywatchmate.notify;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.guest;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.membership;
import static com.mywatchmate.testsupport.Fixtures.principalOf;
import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.notify.dto.ReminderSettingsRequest;
import com.mywatchmate.notify.dto.ReminderSettingsResponse;
import com.mywatchmate.track.Reminder;
import com.mywatchmate.track.ReminderRepository;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import com.mywatchmate.watch.dto.ParticipantResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ReminderServiceTest {

    private static final Instant SIX_AM_LAGOS = Instant.parse("2026-11-02T05:00:00Z");

    @Mock ReminderRepository reminders;
    @Mock ParticipantRepository participants;
    @Mock WatchSessionRepository sessions;
    @Mock SpaceAccess access;

    private ReminderService service;
    private Space space;
    private Participant ada;
    private Participant tunde;
    private Participant bisi;

    @BeforeEach
    void setUp() {
        service = new ReminderService(reminders, participants, sessions, access, clockAt(MONDAY_MIDNIGHT));
        space = space(user("Ada"));
        ada = member(space, space.getOwner(), Participant.Role.OWNER);
        tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
        bisi = guest(space, "Bisi");
        lenient().when(participants.findBySpaceIdAndLeftAtIsNull(space.getId())).thenReturn(List.of(ada, tunde, bisi));
        for (Participant p : List.of(ada, tunde, bisi)) {
            lenient().when(access.require(principalOf(p), space.getId())).thenReturn(membership(p));
        }
    }

    @Test
    void eachRegisteredMemberGetsAnEmailReminderTenMinutesBefore() {
        WatchSession prayer = session(space, SIX_AM_LAGOS);

        service.scheduleFor(prayer);

        ArgumentCaptor<Reminder> saved = ArgumentCaptor.forClass(Reminder.class);
        verify(reminders, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(r -> r.getUser().getDisplayName()).containsExactly("Ada", "Tunde");
        assertThat(saved.getAllValues()).allSatisfy(r -> {
            assertThat(r.getSendAt()).isEqualTo(SIX_AM_LAGOS.minus(Duration.ofMinutes(10)));
            assertThat(r.getChannel()).isEqualTo(Reminder.Channel.EMAIL);
            assertThat(r.getStatus()).isEqualTo(Reminder.Status.PENDING);
            assertThat(r.getSession()).isSameAs(prayer);
        });
    }

    @Test
    void eachPersonsOwnLeadTimeAndNobodyWhoSwitchedOff() {
        tunde.setReminderMinutesBefore(30);
        ada.setReminderMinutesBefore(null);

        service.scheduleFor(session(space, SIX_AM_LAGOS));

        ArgumentCaptor<Reminder> saved = ArgumentCaptor.forClass(Reminder.class);
        verify(reminders).save(saved.capture());
        assertThat(saved.getValue().getUser()).isSameAs(tunde.getUser());
        assertThat(saved.getValue().getSendAt()).isEqualTo(SIX_AM_LAGOS.minus(Duration.ofMinutes(30)));
    }

    @Test
    void neverTwiceForTheSameSession() {
        WatchSession prayer = session(space, SIX_AM_LAGOS);
        when(reminders.existsByUserIdAndSessionIdAndChannel(any(), any(), any())).thenReturn(true);

        service.scheduleFor(prayer);

        verify(reminders, never()).save(any());
    }

    @Test
    void sessionsCloserThanTheLeadTimeAreRemindedRightAway() {
        Instant inFiveMinutes = MONDAY_MIDNIGHT.plus(Duration.ofMinutes(5));

        service.scheduleFor(session(space, inFiveMinutes));

        ArgumentCaptor<Reminder> saved = ArgumentCaptor.forClass(Reminder.class);
        verify(reminders, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(r -> assertThat(r.getSendAt()).isEqualTo(MONDAY_MIDNIGHT));
    }

    @Test
    void pastOrNonScheduledSessionsGetNoReminders() {
        service.scheduleFor(session(space, MONDAY_MIDNIGHT.minusSeconds(60)));
        WatchSession cancelled = session(space, SIX_AM_LAGOS);
        cancelled.cancel();
        service.scheduleFor(cancelled);

        verify(reminders, never()).save(any());
    }

    @Test
    void joiningRemindsYouOfUpcomingSessions() {
        WatchSession first = session(space, SIX_AM_LAGOS);
        WatchSession second = session(space, SIX_AM_LAGOS.plus(Duration.ofDays(1)));
        when(sessions.findBySpaceIdAndStatusAndScheduledAtAfter(space.getId(), WatchSession.Status.SCHEDULED, MONDAY_MIDNIGHT))
            .thenReturn(List.of(first, second));

        service.addMember(tunde);

        verify(reminders, times(2)).save(any(Reminder.class));
    }

    @Test
    void guestsAreNeverReminded() {
        service.addMember(bisi);
        service.removeMember(bisi);

        verifyNoInteractions(reminders, sessions);
    }

    @Test
    void leavingDropsYourUnsentRemindersForThatRoom() {
        service.removeMember(tunde);

        verify(reminders).deletePendingForUserInSpace(tunde.getUser().getId(), space.getId(), Reminder.Status.PENDING);
    }

    @Test
    void cancellingDelegatesToTheRepository() {
        WatchSession prayer = session(space, SIX_AM_LAGOS);
        UUID scheduleId = UUID.randomUUID();

        service.cancelForSession(prayer);
        service.cancelForStoppedSchedule(scheduleId);

        verify(reminders).cancelPendingForSession(prayer.getId(), Reminder.Status.PENDING, Reminder.Status.CANCELLED);
        verify(reminders).cancelPendingForCancelledSessionsOf(scheduleId, Reminder.Status.PENDING, Reminder.Status.CANCELLED,
            WatchSession.Status.CANCELLED);
    }

    @Test
    void settingsShowMyChoiceAndMyAccountSwitch() {
        tunde.getUser().setEmailReminders(false);

        ReminderSettingsResponse settings = service.settings(principalOf(tunde), space.getId());

        assertThat(settings).isEqualTo(new ReminderSettingsResponse(true, 10, false));
    }

    @Test
    void changingMyLeadTimeReplansMyUpcomingReminders() {
        when(sessions.findBySpaceIdAndStatusAndScheduledAtAfter(space.getId(), WatchSession.Status.SCHEDULED, MONDAY_MIDNIGHT))
            .thenReturn(List.of(session(space, SIX_AM_LAGOS)));

        ReminderSettingsResponse settings = service.updateSettings(principalOf(tunde), space.getId(), new ReminderSettingsRequest(true, 30));

        assertThat(settings.minutesBefore()).isEqualTo(30);
        verify(reminders).deletePendingForUserInSpace(tunde.getUser().getId(), space.getId(), Reminder.Status.PENDING);
        ArgumentCaptor<Reminder> saved = ArgumentCaptor.forClass(Reminder.class);
        verify(reminders).save(saved.capture());
        assertThat(saved.getValue().getSendAt()).isEqualTo(SIX_AM_LAGOS.minus(Duration.ofMinutes(30)));
    }

    @Test
    void switchingOffClearsMyRemindersAndEnablingWithoutMinutesUsesTen() {
        assertThat(service.updateSettings(principalOf(tunde), space.getId(), new ReminderSettingsRequest(false, 30)))
            .isEqualTo(new ReminderSettingsResponse(false, null, true));
        assertThat(tunde.getReminderMinutesBefore()).isNull();
        verify(reminders, never()).save(any());

        assertThat(service.updateSettings(principalOf(tunde), space.getId(), new ReminderSettingsRequest(true, null)).minutesBefore())
            .isEqualTo(10);
    }

    @Test
    void guestsHaveNoReminderSettings() {
        assertApiError(() -> service.settings(principalOf(bisi), space.getId()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
    }

    @Test
    void membershipEventsKeepRemindersInStep() {
        when(participants.findById(tunde.getId())).thenReturn(Optional.of(tunde));
        ParticipantResponse who = ParticipantResponse.from(tunde);

        service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_REMOVED, space.getId(), MONDAY_MIDNIGHT, who));
        verify(reminders).deletePendingForUserInSpace(tunde.getUser().getId(), space.getId(), Reminder.Status.PENDING);

        service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_JOINED, space.getId(), MONDAY_MIDNIGHT, who));
        verify(sessions).findBySpaceIdAndStatusAndScheduledAtAfter(space.getId(), WatchSession.Status.SCHEDULED, MONDAY_MIDNIGHT);
    }

    @Test
    void otherEventsAreIgnored() {
        service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.NOW_PLAYING_CHANGED, space.getId(), MONDAY_MIDNIGHT, null));
        service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_JOINED, space.getId(), MONDAY_MIDNIGHT, Map.of("id", "x")));

        verifyNoInteractions(reminders, sessions);
        verify(participants, never()).findById(any());
    }
}

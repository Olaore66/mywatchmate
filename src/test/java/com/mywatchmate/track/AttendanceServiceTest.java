package com.mywatchmate.track;

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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.live.PresenceTracker;
import com.mywatchmate.live.dto.OnlineViewer;
import com.mywatchmate.sessions.dto.SessionResponse;
import com.mywatchmate.track.dto.AttendanceFinalised;
import com.mywatchmate.track.dto.MyAttendanceResponse;
import com.mywatchmate.track.dto.RoomAttendanceResponse;
import com.mywatchmate.track.dto.SessionAttendanceResponse;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {

    private static final Instant T0 = MONDAY_MIDNIGHT;
    private static final Instant NOW = T0.plus(Duration.ofDays(10));

    @Mock AttendanceRepository attendance;
    @Mock WatchSessionRepository sessions;
    @Mock ParticipantRepository participants;
    @Mock PresenceTracker presence;
    @Mock SpaceAccess access;
    @Mock SpaceEvents events;
    @Mock StreakService streaks;

    private AttendanceService service;
    private Space space;
    private Participant ada;
    private Participant tunde;
    private Participant bisi;
    private WatchSession live;

    @BeforeEach
    void setUp() {
        service = new AttendanceService(attendance, sessions, participants, presence, access, events, streaks,
            new TrackProperties(0.5, Duration.ofMinutes(20), Duration.ofSeconds(60)), clockAt(NOW));
        space = space(user("Ada"));
        ada = joined(member(space, space.getOwner(), Participant.Role.OWNER), T0.minusSeconds(3600));
        tunde = joined(member(space, user("Tunde"), Participant.Role.MEMBER), T0.minusSeconds(3600));
        bisi = joined(guest(space, "Bisi"), T0.minusSeconds(3600));
        live = session(space, T0);
        live.start(T0);
    }

    private static Participant joined(Participant p, Instant at) {
        p.setJoinedAt(at);
        return p;
    }

    private static OnlineViewer online(Participant p) {
        return new OnlineViewer(p.getId(), p.displayName(), p.isGuest());
    }

    private static Attendance watched(WatchSession s, Participant p, int seconds) {
        Attendance a = new Attendance(s, p, s.getStartedAt());
        a.seen(s.getStartedAt().plusSeconds(seconds), Duration.ofDays(1));
        return a;
    }

    @Nested
    class Crediting {

        @Test
        void everyoneOnlineBuildsWatchTimeAndNewArrivalsGetARow() {
            Attendance tundeRow = new Attendance(live, tunde, T0);
            when(presence.online(space.getId())).thenReturn(List.of(online(tunde), online(bisi)));
            when(attendance.findBySessionId(live.getId())).thenReturn(List.of(tundeRow));
            when(participants.getReferenceById(bisi.getId())).thenReturn(bisi);

            service.credit(live, T0.plusSeconds(30));

            assertThat(tundeRow.getWatchedSeconds()).isEqualTo(30);
            ArgumentCaptor<Attendance> saved = ArgumentCaptor.forClass(Attendance.class);
            verify(attendance).save(saved.capture());
            assertThat(saved.getValue().getParticipant()).isSameAs(bisi);
            assertThat(saved.getValue().getJoinedAt()).isEqualTo(T0.plusSeconds(30));
        }

        @Test
        void anEmptyRoomTouchesNothing() {
            when(presence.online(space.getId())).thenReturn(List.of());

            service.credit(live, T0.plusSeconds(30));

            verifyNoInteractions(attendance, participants);
        }
    }

    @Nested
    class Finalising {

        @BeforeEach
        void endedAfterHalfAnHour() {
            live.end(T0.plus(Duration.ofMinutes(30)));
            lenient().when(presence.online(space.getId())).thenReturn(List.of());
        }

        @Test
        void halfTheSessionCountsAndTheRoomIsTold() {
            Participant kemi = joined(member(space, user("Kemi"), Participant.Role.MEMBER), T0.plusSeconds(300));
            List<Attendance> rows = List.of(watched(live, tunde, 1000), watched(live, bisi, 100), watched(live, kemi, 950));
            when(attendance.findForSession(live.getId())).thenReturn(rows);
            when(participants.findAllWithUsers(space.getId())).thenReturn(List.of(ada, tunde, bisi, kemi));

            service.finalise(live);

            assertThat(rows).extracting(Attendance::isCounted).containsExactly(true, false, true);
            ArgumentCaptor<Object> told = ArgumentCaptor.forClass(Object.class);
            verify(events).publish(eq(space.getId()), eq(SpaceEvent.Type.ATTENDANCE_FINALISED), told.capture());
            // Expected: Ada, Tunde, Bisi (in the room at the start) + Kemi (joined later, but counted)
            assertThat(told.getValue()).isEqualTo(new AttendanceFinalised(live.getId(), 2, 4));
        }

        @Test
        void everyRegisteredPersonInvolvedGetsTheirStreakRebuiltGuestsDont() {
            when(attendance.findForSession(live.getId())).thenReturn(List.of(watched(live, tunde, 1000)));
            when(participants.findAllWithUsers(space.getId())).thenReturn(List.of(ada, tunde, bisi));

            service.finalise(live);

            verify(streaks).recompute(ada.getUser().getId());
            verify(streaks).recompute(tunde.getUser().getId());
            verify(streaks, never()).recompute(null);
        }

        @Test
        void peopleWhoLeftBeforeItStartedOrJoinedLaterAndDidntCountArentExpected() {
            Participant left = joined(member(space, user("Femi"), Participant.Role.MEMBER), T0.minusSeconds(7200));
            left.leave(T0.minusSeconds(60));
            Participant late = joined(member(space, user("Kemi"), Participant.Role.MEMBER), T0.plusSeconds(600));
            when(attendance.findForSession(live.getId())).thenReturn(List.of(watched(live, late, 10)));
            when(participants.findAllWithUsers(space.getId())).thenReturn(List.of(ada, left, late));

            service.finalise(live);

            verify(events).publish(space.getId(), SpaceEvent.Type.ATTENDANCE_FINALISED,
                new AttendanceFinalised(live.getId(), 0, 1));
            verify(streaks).recompute(ada.getUser().getId());
            verify(streaks, never()).recompute(left.getUser().getId());
            verify(streaks, never()).recompute(late.getUser().getId());
        }

        @Test
        void theLastStretchIsCreditedBeforeDeciding() {
            Attendance tundeRow = new Attendance(live, tunde, T0.plus(Duration.ofMinutes(29)).plusSeconds(30));
            when(presence.online(space.getId())).thenReturn(List.of(online(tunde)));
            when(attendance.findBySessionId(live.getId())).thenReturn(List.of(tundeRow));
            when(attendance.findForSession(live.getId())).thenReturn(List.of(tundeRow));
            when(participants.findAllWithUsers(space.getId())).thenReturn(List.of(tunde));

            service.finalise(live);

            assertThat(tundeRow.getWatchedSeconds()).isEqualTo(30);
        }

        @Test
        void theSessionEndedEventTriggersIt() {
            when(sessions.findById(live.getId())).thenReturn(Optional.of(live));
            when(attendance.findForSession(live.getId())).thenReturn(List.of());
            when(participants.findAllWithUsers(space.getId())).thenReturn(List.of());

            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.SESSION_ENDED, space.getId(), NOW, ended(live)));

            verify(events).publish(space.getId(), SpaceEvent.Type.ATTENDANCE_FINALISED,
                new AttendanceFinalised(live.getId(), 0, 0));
        }

        @Test
        void anEndedEventWithoutSessionDataOrForAnUnknownSessionDoesNothing() {
            UUID unknown = UUID.randomUUID();
            when(sessions.findById(unknown)).thenReturn(Optional.empty());

            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.SESSION_ENDED, space.getId(), NOW, null));
            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.SESSION_ENDED, space.getId(), NOW,
                new SessionResponse(unknown, space.getId(), space.getTitle(), null, null, WatchSession.Status.ENDED,
                    T0, null, "UTC", T0, NOW, null)));

            verifyNoInteractions(attendance, events, streaks);
        }

        @Test
        void otherEventsAndNeverStartedSessionsAreIgnored() {
            WatchSession missed = session(space, T0);
            missed.markMissed(NOW);
            when(sessions.findById(missed.getId())).thenReturn(Optional.of(missed));

            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.SESSION_ENDED, space.getId(), NOW, ended(missed)));
            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.SESSION_STARTED, space.getId(), NOW, ended(live)));

            verifyNoInteractions(attendance, events, streaks);
        }

        private SessionResponse ended(WatchSession s) {
            return new SessionResponse(s.getId(), space.getId(), space.getTitle(), null, null, s.getStatus(),
                s.getScheduledAt(), null, "UTC", s.getStartedAt(), s.getEndedAt(), null);
        }
    }

    @Nested
    class OneSession {

        @Test
        void whileLiveNobodyIsDecidedYet() {
            when(access.require(principalOf(bisi), space.getId())).thenReturn(membership(bisi));
            when(sessions.findByIdAndSpaceId(live.getId(), space.getId())).thenReturn(Optional.of(live));
            when(attendance.findForSession(live.getId())).thenReturn(List.of(watched(live, tunde, 400)));

            SessionAttendanceResponse response = service.forSession(principalOf(bisi), space.getId(), live.getId());

            assertThat(response.status()).isEqualTo(WatchSession.Status.LIVE);
            assertThat(response.requiredSeconds()).isNull();
            assertThat(response.attendedCount()).isZero();
            assertThat(response.presentCount()).isEqualTo(1);
            assertThat(response.people().getFirst().counted()).isNull();
            assertThat(response.people().getFirst().watchedSeconds()).isEqualTo(400);
        }

        @Test
        void afterItEndsEveryoneIsCountedOrNot() {
            live.end(T0.plus(Duration.ofMinutes(30)));
            Attendance counted = watched(live, tunde, 1000);
            counted.finish(900);
            Attendance brief = watched(live, bisi, 60);
            brief.finish(900);
            when(access.require(principalOf(tunde), space.getId())).thenReturn(membership(tunde));
            when(sessions.findByIdAndSpaceId(live.getId(), space.getId())).thenReturn(Optional.of(live));
            when(attendance.findForSession(live.getId())).thenReturn(List.of(counted, brief));

            SessionAttendanceResponse response = service.forSession(principalOf(tunde), space.getId(), live.getId());

            assertThat(response.requiredSeconds()).isEqualTo(900);
            assertThat(response.attendedCount()).isEqualTo(1);
            assertThat(response.people()).extracting(p -> p.displayName() + ":" + p.counted())
                .containsExactly("Tunde:true", "Bisi:false");
        }

        @Test
        void aMissedSessionHasNoRequirementAndNobody() {
            WatchSession missed = session(space, T0);
            missed.markMissed(NOW);
            when(access.require(principalOf(tunde), space.getId())).thenReturn(membership(tunde));
            when(sessions.findByIdAndSpaceId(missed.getId(), space.getId())).thenReturn(Optional.of(missed));
            when(attendance.findForSession(missed.getId())).thenReturn(List.of());

            SessionAttendanceResponse response = service.forSession(principalOf(tunde), space.getId(), missed.getId());

            assertThat(response.status()).isEqualTo(WatchSession.Status.ENDED);
            assertThat(response.startedAt()).isNull();
            assertThat(response.requiredSeconds()).isNull();
            assertThat(response.people()).isEmpty();
        }

        @Test
        void whileLiveTheRequirementSoFarUsesTheClock() {
            // 10 days live so far: the 20-minute cap applies
            assertThat(service.requiredSeconds(live)).isEqualTo(20 * 60);
        }

        @Test
        void aSessionFromAnotherRoomIsNotFound() {
            UUID elsewhere = UUID.randomUUID();
            when(access.require(principalOf(tunde), space.getId())).thenReturn(membership(tunde));
            when(sessions.findByIdAndSpaceId(elsewhere, space.getId())).thenReturn(Optional.empty());

            assertApiError(() -> service.forSession(principalOf(tunde), space.getId(), elsewhere),
                HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND");
        }
    }

    @Nested
    class RoomSummary {

        private List<WatchSession> threeEndedSessions() {
            return List.of(endedAt(1), endedAt(2), endedAt(3));
        }

        private WatchSession endedAt(int day) {
            WatchSession s = session(space, T0.plus(Duration.ofDays(day)).minusSeconds(86_000));
            s.start(s.getScheduledAt());
            s.end(s.getScheduledAt().plus(Duration.ofMinutes(30)));
            return s;
        }

        @Test
        void hostsSeeHowEachSessionWentAndWhoIsDriftingAway() {
            List<WatchSession> held = threeEndedSessions();
            WatchSession stillLive = session(space, NOW.minusSeconds(60));
            stillLive.start(NOW.minusSeconds(60));
            when(access.require(principalOf(ada), space.getId())).thenReturn(membership(ada));
            when(sessions.findStartedBetween(space.getId(), NOW.minus(Duration.ofDays(30)), NOW))
                .thenReturn(concat(held, stillLive));
            List<Attendance> rows = held.stream().map(s -> counted(s, ada)).toList();
            when(attendance.findForSessions(anyList())).thenReturn(concat(rows, counted(held.get(0), bisi)));
            when(participants.findAllWithUsers(space.getId())).thenReturn(List.of(ada, tunde, bisi));

            RoomAttendanceResponse summary = service.forRoom(principalOf(ada), space.getId(), null, null);

            assertThat(summary.from()).isEqualTo(NOW.minus(Duration.ofDays(30)));
            assertThat(summary.to()).isEqualTo(NOW);
            assertThat(summary.sessions()).as("the live one isn't decided yet").hasSize(3);
            assertThat(summary.sessions()).extracting(RoomAttendanceResponse.SessionSummary::attendedCount)
                .containsExactly(2, 1, 1);
            assertThat(summary.sessions()).extracting(RoomAttendanceResponse.SessionSummary::expectedCount)
                .containsExactly(3, 3, 3);
            assertThat(summary.members()).extracting(m -> m.displayName() + " " + m.attended() + "/" + m.expected()
                + (m.driftingAway() ? " drifting" : "")).containsExactly("Ada 3/3", "Tunde 0/3 drifting", "Bisi 1/3");
            assertThat(summary.members().getFirst().lastAttendedAt()).isEqualTo(held.get(2).getStartedAt());
        }

        @Test
        void peopleWhoLeftArentListedAndNewcomersOnlyOweWhatCameAfter() {
            List<WatchSession> held = threeEndedSessions();
            Participant newcomer = joined(member(space, user("Kemi"), Participant.Role.MEMBER),
                held.get(1).getStartedAt().plusSeconds(1));
            tunde.leave(NOW.minusSeconds(10));
            when(access.require(principalOf(ada), space.getId())).thenReturn(membership(ada));
            when(sessions.findStartedBetween(any(), any(), any())).thenReturn(held);
            when(attendance.findForSessions(anyList())).thenReturn(List.of());
            when(participants.findAllWithUsers(space.getId())).thenReturn(List.of(ada, tunde, newcomer));

            RoomAttendanceResponse summary = service.forRoom(principalOf(ada), space.getId(), null, null);

            assertThat(summary.members()).extracting(m -> m.displayName() + " " + m.expected() + (m.driftingAway() ? "!" : ""))
                .containsExactly("Ada 3!", "Kemi 1");
        }

        @Test
        void aQuietPeriodIsAnEmptySummary() {
            when(access.require(principalOf(ada), space.getId())).thenReturn(membership(ada));
            when(sessions.findStartedBetween(any(), any(), any())).thenReturn(List.of());
            when(participants.findAllWithUsers(space.getId())).thenReturn(List.of(ada));

            RoomAttendanceResponse summary = service.forRoom(principalOf(ada), space.getId(),
                T0, T0.plus(Duration.ofDays(7)));

            assertThat(summary.sessions()).isEmpty();
            assertThat(summary.members().getFirst().expected()).isZero();
            verify(attendance, never()).findForSessions(anyList());
        }

        @Test
        void onlyHostsAndOnlySensibleRanges() {
            when(access.require(principalOf(tunde), space.getId())).thenReturn(membership(tunde));
            assertApiError(() -> service.forRoom(principalOf(tunde), space.getId(), null, null),
                HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");

            when(access.require(principalOf(ada), space.getId())).thenReturn(membership(ada));
            assertApiError(() -> service.forRoom(principalOf(ada), space.getId(), NOW, T0),
                HttpStatus.BAD_REQUEST, "INVALID_RANGE");
            assertApiError(() -> service.forRoom(principalOf(ada), space.getId(), T0.minus(Duration.ofDays(400)), T0),
                HttpStatus.BAD_REQUEST, "INVALID_RANGE");
        }

        private Attendance counted(WatchSession s, Participant p) {
            Attendance a = watched(s, p, 1000);
            a.finish(900);
            return a;
        }
    }

    @Nested
    class MyHistory {

        @Test
        void theLastThirtyDaysByDefault() {
            UUID me = ada.getUser().getId();
            Instant end = NOW.plus(Duration.ofDays(1));
            when(attendance.findHistory(me, end.minus(Duration.ofDays(30)), end))
                .thenReturn(List.of(watched(live, ada, 120)));

            List<MyAttendanceResponse> mine = service.mine(me, null, null);

            assertThat(mine).hasSize(1);
            assertThat(mine.getFirst().spaceTitle()).isEqualTo("6am Prayer Room");
            assertThat(mine.getFirst().counted()).as("still live").isNull();
        }

        @Test
        void backwardsRangesAreRefused() {
            assertApiError(() -> service.mine(UUID.randomUUID(), NOW, T0), HttpStatus.BAD_REQUEST, "INVALID_RANGE");
        }
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T> first, T... more) {
        List<T> all = new ArrayList<>(first);
        all.addAll(List.of(more));
        return all;
    }
}

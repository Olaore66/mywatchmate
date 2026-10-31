package com.mywatchmate.connect.call;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.guest;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.membership;
import static com.mywatchmate.testsupport.Fixtures.principalOf;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.connect.call.dto.CallJoinResponse;
import com.mywatchmate.connect.call.dto.CallParticipant;
import com.mywatchmate.connect.call.dto.CallRoster;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.dto.ParticipantResponse;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class CallServiceTest {

    @Mock LiveKitRoomClient livekit;
    @Mock SpaceAccess access;
    @Mock SpaceEvents events;

    private CallService service;
    private Space space;
    private Participant ada;
    private Participant bisi;

    @BeforeEach
    void setUp() {
        service = service(LiveKitTokensTest.PROPS);
        space = space(user("Ada"));
        ada = member(space, space.getOwner(), Participant.Role.OWNER);
        bisi = guest(space, "Bisi");
        for (Participant p : List.of(ada, bisi)) {
            lenient().when(access.require(principalOf(p), space.getId())).thenReturn(membership(p));
        }
    }

    private CallService service(LiveKitProperties props) {
        return new CallService(props, new LiveKitTokens(props), livekit, access, events, clockAt(MONDAY_MIDNIGHT));
    }

    private String room() {
        return "space-" + space.getId();
    }

    @Nested
    class Joining {

        @Test
        void anyoneInTheRoomGetsAPassForItsCall() throws Exception {
            CallJoinResponse pass = service.join(principalOf(bisi), space.getId());

            assertThat(pass.url()).isEqualTo("wss://mywatchmate-test.livekit.cloud");
            assertThat(pass.roomName()).isEqualTo(room());
            assertThat(pass.identity()).isEqualTo(bisi.getId().toString());
            assertThat(pass.expiresAt()).isEqualTo(MONDAY_MIDNIGHT.plus(Duration.ofMinutes(10)));
            var claims = SignedJWT.parse(pass.token()).getJWTClaimsSet();
            assertThat(claims.getSubject()).isEqualTo(bisi.getId().toString());
            assertThat(claims.getStringClaim("name")).isEqualTo("Bisi");
            assertThat(claims.getStringClaim("metadata")).isEqualTo("{\"guest\":true,\"role\":\"GUEST\"}");
            assertThat(claims.getJSONObjectClaim("video")).containsEntry("room", room());
        }

        @Test
        void hostsMetadataSaysSo() throws Exception {
            CallJoinResponse pass = service.join(principalOf(ada), space.getId());

            assertThat(SignedJWT.parse(pass.token()).getJWTClaimsSet().getStringClaim("metadata"))
                .isEqualTo("{\"guest\":false,\"role\":\"OWNER\"}");
        }

        @Test
        void notInArchivedRoomsAndNotWithoutLiveKit() {
            CallService off = service(new LiveKitProperties("", "", "", Duration.ofMinutes(10)));
            assertApiError(() -> off.join(principalOf(ada), space.getId()), HttpStatus.SERVICE_UNAVAILABLE, "CALLS_NOT_CONFIGURED");
            assertApiError(() -> off.roster(principalOf(ada), space.getId()), HttpStatus.SERVICE_UNAVAILABLE, "CALLS_NOT_CONFIGURED");
            assertApiError(() -> off.onWebhook("x", "{}"), HttpStatus.SERVICE_UNAVAILABLE, "CALLS_NOT_CONFIGURED");

            space.archive(MONDAY_MIDNIGHT);
            assertApiError(() -> service.join(principalOf(ada), space.getId()), HttpStatus.CONFLICT, "SPACE_ARCHIVED");
        }
    }

    @Test
    void theRosterComesFromLiveKit() {
        CallParticipant on = new CallParticipant(bisi.getId(), "Bisi", MONDAY_MIDNIGHT, true, true, false);
        when(livekit.listParticipants(room())).thenReturn(List.of(on));

        CallRoster roster = service.roster(principalOf(ada), space.getId());

        assertThat(roster).isEqualTo(new CallRoster(1, List.of(on)));
    }

    @Nested
    class Webhooks {

        private String body(String event, String roomName) {
            return "{\"event\":\"" + event + "\",\"room\":{\"name\":\"" + roomName + "\"},\"participant\":{\"identity\":\"x\"}}";
        }

        private String signed(String body) throws Exception {
            return LiveKitTokensTest.webhookToken(body, LiveKitTokensTest.KEY, LiveKitTokensTest.SECRET, MONDAY_MIDNIGHT);
        }

        @Test
        void someoneJoiningOrLeavingTellsTheRoomWhosInTheCall() throws Exception {
            CallParticipant on = new CallParticipant(bisi.getId(), "Bisi", MONDAY_MIDNIGHT, true, false, false);
            when(livekit.listParticipants(room())).thenReturn(List.of(on));

            for (String event : CallService.ROSTER_EVENTS) {
                String body = body(event, room());
                service.onWebhook(signed(body), body);
            }

            verify(events, times(CallService.ROSTER_EVENTS.size()))
                .publish(space.getId(), SpaceEvent.Type.CALL_UPDATED, new CallRoster(1, List.of(on)));
        }

        @Test
        void otherEventsAndOtherRoomsAreIgnored() throws Exception {
            for (String body : List.of(body("room_started", room()), body("participant_joined", "lobby"),
                body("participant_joined", "space-not-a-uuid"), "{\"event\":\"participant_joined\"}")) {
                service.onWebhook(signed(body), body);
            }

            verifyNoInteractions(livekit, events);
        }

        @Test
        void forgedOrBrokenWebhooksAreRefused() throws Exception {
            String body = body("participant_joined", room());
            assertApiError(() -> service.onWebhook(signed(body), body + "x"), HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE");
            assertApiError(() -> service.onWebhook(null, body), HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE");

            String notJson = "not json";
            assertApiError(() -> service.onWebhook(signed(notJson), notJson), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
            verifyNoInteractions(events);
        }

        @Test
        void livekitNotAnsweringIsLoggedNotFailed() throws Exception {
            when(livekit.listParticipants(room())).thenThrow(new IllegalStateException("down"));
            String body = body("participant_left", room());

            service.onWebhook(signed(body), body);

            verifyNoInteractions(events);
        }
    }

    @Nested
    class LeavingTheRoom {

        @Test
        void someoneRemovedOrLeavingIsTakenOutOfTheCall() {
            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_REMOVED, space.getId(), MONDAY_MIDNIGHT,
                ParticipantResponse.from(bisi)));
            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_LEFT, space.getId(), MONDAY_MIDNIGHT,
                ParticipantResponse.from(ada)));

            verify(livekit).removeParticipant(room(), bisi.getId().toString());
            verify(livekit).removeParticipant(room(), ada.getId().toString());
        }

        @Test
        void otherEventsOrNoLiveKitDoNothingAndFailuresAreLogged() {
            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_JOINED, space.getId(), MONDAY_MIDNIGHT,
                ParticipantResponse.from(bisi)));
            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_REMOVED, space.getId(), MONDAY_MIDNIGHT, null));
            service(new LiveKitProperties("", "", "", Duration.ofMinutes(10))).onSpaceEvent(new SpaceEvent(
                SpaceEvent.Type.MEMBER_REMOVED, space.getId(), MONDAY_MIDNIGHT, ParticipantResponse.from(bisi)));
            verifyNoInteractions(livekit);

            doThrow(new IllegalStateException("down")).when(livekit).removeParticipant(any(), any());
            service.onSpaceEvent(new SpaceEvent(SpaceEvent.Type.MEMBER_REMOVED, space.getId(), MONDAY_MIDNIGHT,
                ParticipantResponse.from(bisi)));
        }
    }

    @Test
    void roomNamesAndBack() {
        UUID id = UUID.randomUUID();
        assertThat(CallService.roomName(id)).isEqualTo("space-" + id);
        assertThat(CallService.spaceOf("space-" + id)).contains(id);
        assertThat(CallService.spaceOf(null)).isEmpty();
        assertThat(CallService.spaceOf("lobby")).isEmpty();
    }
}

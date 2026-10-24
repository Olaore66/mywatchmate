package com.mywatchmate.give;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.give.dto.BlockLinkRequest;
import com.mywatchmate.give.dto.BlockedLinkResponse;
import com.mywatchmate.give.dto.CueOutcome;
import com.mywatchmate.give.dto.InternalCueRequest;
import com.mywatchmate.give.dto.PromptResponse;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import com.mywatchmate.watch.dto.PersonRef;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GiveServiceTest {

    private static final Instant NOW = MONDAY_MIDNIGHT;
    private static final String LINK = "https://pay.payaza.africa/give/stmarks";

    @Mock PaymentLinkRepository links;
    @Mock CueDetectionRepository detections;
    @Mock SpaceBlockedLinkRepository roomBlocks;
    @Mock WatchSessionRepository sessions;
    @Mock SpaceAccess access;
    @Mock SpaceEvents events;

    private final GiveProperties props = new GiveProperties(List.of("payaza.africa"), Duration.ofMinutes(10),
        Duration.ofMinutes(5), 3, Duration.ofHours(1), "secret");
    private GiveService service;
    private Space space;
    private Participant ada;
    private Participant tunde;
    private Participant bisi;
    private WatchSession live;
    private PaymentLink stmarks;

    @BeforeEach
    void setUp() {
        service = new GiveService(new PaymentLinkRecognizer(props), links, detections, roomBlocks, sessions, access,
            events, props, clockAt(NOW));
        space = space(user("Ada"));
        ada = member(space, space.getOwner(), Participant.Role.OWNER);
        tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
        bisi = guest(space, "Bisi");
        for (Participant p : List.of(ada, tunde, bisi)) {
            lenient().when(access.require(principalOf(p), space.getId())).thenReturn(membership(p));
        }
        live = session(space, NOW);
        live.start(NOW);
        lenient().when(sessions.findFirstBySpaceIdAndStatus(space.getId(), WatchSession.Status.LIVE)).thenReturn(Optional.of(live));
        lenient().when(sessions.findById(live.getId())).thenReturn(Optional.of(live));
        stmarks = withId(new PaymentLink(LINK, PaymentLink.Provider.PAYAZA));
        lenient().when(links.findByNormalizedUrl(LINK)).thenReturn(Optional.of(stmarks));
        lenient().when(detections.saveAndFlush(any(CueDetection.class))).thenAnswer(i -> withId(i.getArgument(0)));
        lenient().when(detections.findBySessionIdAndStatusAndClosedAtIsNull(any(), any())).thenReturn(List.of());
    }

    private static <T> T withId(T entity) {
        if (ReflectionTestUtils.getField(entity, "id") == null) {
            ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        }
        return entity;
    }

    private CueDetection showing(PaymentLink link, Instant expires) {
        CueDetection d = withId(new CueDetection(live, CueDetection.CueType.QR, link.getNormalizedUrl(), CueDetection.Status.SHOWN));
        d.setPaymentLink(link);
        d.setDetectedAt(expires.minus(Duration.ofMinutes(5)));
        d.setExpiresAt(expires);
        lenient().when(detections.findInSpace(d.getId(), space.getId())).thenReturn(Optional.of(d));
        return d;
    }

    private InternalCueRequest cue(String raw) {
        return new InternalCueRequest(live.getId(), raw, null, new BigDecimal("0.97"), 1800);
    }

    @Nested
    class ByHost {

        @Test
        void aHostPutsUpTheRoomsLinkForEveryone() {
            PromptResponse shown = service.showByHost(principalOf(ada), space.getId(), "  " + LINK + "?utm_source=x ");

            assertThat(shown.url()).isEqualTo(LINK);
            assertThat(shown.source()).isEqualTo(CueDetection.Source.HOST);
            assertThat(shown.sessionId()).isEqualTo(live.getId());
            assertThat(shown.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
            assertThat(shown.notice()).isEqualTo("You're paying the link's owner through Payaza. MyWatchMate never holds your money.");
            ArgumentCaptor<CueDetection> saved = ArgumentCaptor.forClass(CueDetection.class);
            verify(detections).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getShownBy()).isSameAs(ada);
            assertThat(saved.getValue().getCueType()).isEqualTo(CueDetection.CueType.LINK);
            verify(events).publish(space.getId(), SpaceEvent.Type.GIVE_PROMPT, shown);
        }

        @Test
        void aLinkSeenForTheFirstTimeIsRemembered() {
            when(links.findByNormalizedUrl("https://pay.payaza.africa/give/new")).thenReturn(Optional.empty());
            when(links.saveAndFlush(any(PaymentLink.class))).thenAnswer(i -> withId(i.getArgument(0)));

            service.showByHost(principalOf(ada), space.getId(), "https://pay.payaza.africa/give/new");

            ArgumentCaptor<PaymentLink> saved = ArgumentCaptor.forClass(PaymentLink.class);
            verify(links).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getNormalizedUrl()).isEqualTo("https://pay.payaza.africa/give/new");
            assertThat(saved.getValue().getFirstSeenAt()).isEqualTo(NOW);
        }

        @Test
        void aNewPromptReplacesTheOneShowing() {
            CueDetection old = showing(stmarks, NOW.plusSeconds(60));
            when(detections.findBySessionIdAndStatusAndClosedAtIsNull(live.getId(), CueDetection.Status.SHOWN))
                .thenReturn(List.of(old));

            service.showByHost(principalOf(ada), space.getId(), LINK);

            assertThat(old.getClosedAt()).isEqualTo(NOW);
        }

        @Test
        void onlyHostsOnlyPayazaOnlyLiveOnlyUnblocked() {
            assertApiError(() -> service.showByHost(principalOf(tunde), space.getId(), LINK), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertApiError(() -> service.showByHost(principalOf(ada), space.getId(), "https://paystack.com/pay/x"),
                HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_PAYMENT_LINK");

            when(roomBlocks.existsById(new SpaceBlockedLinkId(space.getId(), stmarks.getId()))).thenReturn(true);
            assertApiError(() -> service.showByHost(principalOf(ada), space.getId(), LINK), HttpStatus.CONFLICT, "LINK_BLOCKED");

            when(sessions.findFirstBySpaceIdAndStatus(space.getId(), WatchSession.Status.LIVE)).thenReturn(Optional.empty());
            assertApiError(() -> service.showByHost(principalOf(ada), space.getId(), LINK), HttpStatus.CONFLICT, "NO_LIVE_SESSION");

            verify(detections, never()).saveAndFlush(any());
            verifyNoInteractions(events);
        }

        @Test
        void aLinkBlockedEverywhereIsRefusedToo() {
            stmarks.block("scam");

            assertApiError(() -> service.showByHost(principalOf(ada), space.getId(), LINK), HttpStatus.CONFLICT, "LINK_BLOCKED");
        }
    }

    @Nested
    class FromTheAi {

        @Test
        void aGoodCueIsShownWithItsDetails() {
            CueOutcome outcome = service.fromWorker(cue(LINK));

            assertThat(outcome.status()).isEqualTo(CueDetection.Status.SHOWN);
            assertThat(outcome.detectionId()).isNotNull();
            ArgumentCaptor<CueDetection> saved = ArgumentCaptor.forClass(CueDetection.class);
            verify(detections).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getSource()).isEqualTo(CueDetection.Source.AI);
            assertThat(saved.getValue().getCueType()).isEqualTo(CueDetection.CueType.QR);
            assertThat(saved.getValue().getConfidence()).isEqualByComparingTo("0.97");
            assertThat(saved.getValue().getContentPositionSeconds()).isEqualTo(1800);
            verify(events).publish(eq(space.getId()), eq(SpaceEvent.Type.GIVE_PROMPT), any(PromptResponse.class));
        }

        @Test
        void notAPaymentLinkIsRecordedAsRejectedWithWhy() {
            CueOutcome outcome = service.fromWorker(cue("https://evilpayaza.africa/pay"));

            assertThat(outcome.status()).isEqualTo(CueDetection.Status.REJECTED);
            assertThat(outcome.reason()).isEqualTo("NOT_PAYAZA");
            ArgumentCaptor<CueDetection> saved = ArgumentCaptor.forClass(CueDetection.class);
            verify(detections).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getRejectReason()).isEqualTo("NOT_PAYAZA");
            assertThat(saved.getValue().getPaymentLink()).isNull();
            verifyNoInteractions(events);
        }

        @Test
        void blockedLinksAreRecordedAsBlocked() {
            when(roomBlocks.existsById(new SpaceBlockedLinkId(space.getId(), stmarks.getId()))).thenReturn(true);

            CueOutcome outcome = service.fromWorker(cue(LINK));

            assertThat(outcome.status()).isEqualTo(CueDetection.Status.BLOCKED);
            assertThat(outcome.reason()).isEqualTo("BLOCKED_IN_ROOM");
            verifyNoInteractions(events);
        }

        @Test
        void theSameLinkSoonAfterIsNotShownOrRecordedAgain() {
            when(detections.existsBySessionIdAndPaymentLinkIdAndStatusAndDetectedAtAfter(live.getId(), stmarks.getId(),
                CueDetection.Status.SHOWN, NOW.minus(Duration.ofMinutes(10)))).thenReturn(true);

            CueOutcome outcome = service.fromWorker(cue(LINK));

            assertThat(outcome).isEqualTo(new CueOutcome(null, CueDetection.Status.REJECTED, "RECENTLY_SHOWN"));
            verify(detections, never()).saveAndFlush(any());
        }

        @Test
        void onlyForLiveSessions() {
            WatchSession ended = session(space, NOW);
            when(sessions.findById(ended.getId())).thenReturn(Optional.empty());

            assertApiError(() -> service.fromWorker(new InternalCueRequest(ended.getId(), LINK, null, null, null)),
                HttpStatus.CONFLICT, "SESSION_NOT_LIVE");

            WatchSession scheduled = session(space, NOW);
            when(sessions.findById(scheduled.getId())).thenReturn(Optional.of(scheduled));
            assertApiError(() -> service.fromWorker(new InternalCueRequest(scheduled.getId(), LINK, null, null, null)),
                HttpStatus.CONFLICT, "SESSION_NOT_LIVE");
        }
    }

    @Nested
    class Showing {

        @Test
        void latecomersSeeTheNewestPromptStillUp() {
            CueDetection expired = showing(stmarks, NOW.minusSeconds(1));
            CueDetection older = showing(stmarks, NOW.plusSeconds(60));
            CueDetection newest = showing(stmarks, NOW.plusSeconds(200));
            when(detections.findBySessionIdAndStatusAndClosedAtIsNull(live.getId(), CueDetection.Status.SHOWN))
                .thenReturn(List.of(expired, newest, older));

            assertThat(service.current(principalOf(bisi), space.getId())).hasValueSatisfying(p ->
                assertThat(p.id()).isEqualTo(newest.getId()));
        }

        @Test
        void nothingLiveNothingShowing() {
            when(sessions.findFirstBySpaceIdAndStatus(space.getId(), WatchSession.Status.LIVE)).thenReturn(Optional.empty());
            assertThat(service.current(principalOf(bisi), space.getId())).isEmpty();
        }

        @Test
        void hostsTakeItDownOnce() {
            CueDetection prompt = showing(stmarks, NOW.plusSeconds(60));

            service.close(principalOf(ada), space.getId(), prompt.getId());
            service.close(principalOf(ada), space.getId(), prompt.getId());

            assertThat(prompt.getClosedAt()).isEqualTo(NOW);
            verify(events).publish(space.getId(), SpaceEvent.Type.GIVE_PROMPT_CLOSED, Map.of("promptId", prompt.getId()));
        }

        @Test
        void membersCantTakeItDownAndUnknownPromptsAreNotFound() {
            CueDetection prompt = showing(stmarks, NOW.plusSeconds(60));
            assertApiError(() -> service.close(principalOf(tunde), space.getId(), prompt.getId()),
                HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");

            UUID unknown = UUID.randomUUID();
            when(detections.findInSpace(unknown, space.getId())).thenReturn(Optional.empty());
            assertApiError(() -> service.close(principalOf(ada), space.getId(), unknown), HttpStatus.NOT_FOUND, "PROMPT_NOT_FOUND");
        }

        @Test
        void aRejectedCueIsNotAPrompt() {
            CueDetection rejected = withId(new CueDetection(live, CueDetection.CueType.QR, "x", CueDetection.Status.REJECTED));
            when(detections.findInSpace(rejected.getId(), space.getId())).thenReturn(Optional.of(rejected));

            assertApiError(() -> service.close(principalOf(ada), space.getId(), rejected.getId()),
                HttpStatus.NOT_FOUND, "PROMPT_NOT_FOUND");
        }
    }

    @Nested
    class RoomBlocks {

        @Test
        void blockingTakesTheLinkDownHere() {
            CueDetection up = showing(stmarks, NOW.plusSeconds(60));
            when(roomBlocks.findById(new SpaceBlockedLinkId(space.getId(), stmarks.getId()))).thenReturn(Optional.empty());
            when(roomBlocks.saveAndFlush(any(SpaceBlockedLink.class))).thenAnswer(i -> i.getArgument(0));
            when(detections.findOpenInSpaceForLink(space.getId(), stmarks.getId())).thenReturn(List.of(up));

            BlockedLinkResponse blocked = service.block(principalOf(ada), space.getId(),
                new BlockLinkRequest(LINK, "  Not our account "));

            assertThat(blocked.url()).isEqualTo(LINK);
            assertThat(blocked.reason()).isEqualTo("Not our account");
            assertThat(blocked.blockedBy().displayName()).isEqualTo("Ada");
            assertThat(blocked.blockedAt()).isEqualTo(NOW);
            assertThat(up.getClosedAt()).isEqualTo(NOW);
            verify(events).publish(space.getId(), SpaceEvent.Type.GIVE_PROMPT_CLOSED, Map.of("promptId", up.getId()));
        }

        @Test
        void blockingTwiceKeepsTheFirst() {
            SpaceBlockedLink existing = new SpaceBlockedLink(space, stmarks, ada, "first");
            when(roomBlocks.findById(new SpaceBlockedLinkId(space.getId(), stmarks.getId()))).thenReturn(Optional.of(existing));
            when(detections.findOpenInSpaceForLink(space.getId(), stmarks.getId())).thenReturn(List.of());

            assertThat(service.block(principalOf(ada), space.getId(), new BlockLinkRequest(LINK, "second")).reason())
                .isEqualTo("first");
            verify(roomBlocks, never()).saveAndFlush(any());
        }

        @Test
        void listingAndUnblocking() {
            SpaceBlockedLink existing = new SpaceBlockedLink(space, stmarks, null, null);
            when(roomBlocks.findForSpace(space.getId())).thenReturn(List.of(existing));
            when(roomBlocks.findById(new SpaceBlockedLinkId(space.getId(), stmarks.getId()))).thenReturn(Optional.of(existing));

            assertThat(service.blockedLinks(principalOf(ada), space.getId())).extracting(BlockedLinkResponse::blockedBy)
                .containsExactly((PersonRef) null);
            service.unblock(principalOf(ada), space.getId(), stmarks.getId());
            service.unblock(principalOf(ada), space.getId(), UUID.randomUUID());

            verify(roomBlocks).delete(existing);
        }

        @Test
        void membersCantSeeOrChangeBlocks() {
            assertApiError(() -> service.blockedLinks(principalOf(tunde), space.getId()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertApiError(() -> service.block(principalOf(tunde), space.getId(), new BlockLinkRequest(LINK, null)),
                HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertApiError(() -> service.unblock(principalOf(tunde), space.getId(), stmarks.getId()),
                HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertApiError(() -> service.block(principalOf(ada), space.getId(), new BlockLinkRequest("https://x.com/a", null)),
                HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_PAYMENT_LINK");
        }
    }

    @Nested
    class Reports {

        @Test
        void eachPersonCountsOnceUsersByAccountGuestsByRoom() {
            CueDetection prompt = showing(stmarks, NOW.plusSeconds(60));
            when(links.insertReport(stmarks.getId(), "user:" + tunde.getUser().getId(), prompt.getId(), "Not our church"))
                .thenReturn(1, 0);
            when(links.insertReport(stmarks.getId(), "guest:" + bisi.getId(), prompt.getId(), null)).thenReturn(1);

            service.report(principalOf(tunde), space.getId(), prompt.getId(), " Not our church ");
            service.report(principalOf(tunde), space.getId(), prompt.getId(), "Not our church");
            service.report(principalOf(bisi), space.getId(), prompt.getId(), "  ");

            assertThat(stmarks.getReportCount()).isEqualTo(2);
            assertThat(stmarks.isBlocked()).isFalse();
            verifyNoInteractions(events);
        }

        @Test
        void theThirdReportBlocksTheLinkEverywhereAndTakesItDown() {
            CueDetection prompt = showing(stmarks, NOW.plusSeconds(60));
            stmarks.setReportCount(2);
            when(links.insertReport(eq(stmarks.getId()), anyString(), eq(prompt.getId()), any())).thenReturn(1);
            when(detections.findOpenForLink(stmarks.getId())).thenReturn(List.of(prompt));

            service.report(principalOf(bisi), space.getId(), prompt.getId(), null);

            assertThat(stmarks.isBlocked()).isTrue();
            assertThat(stmarks.getBlockedReason()).isEqualTo(GiveService.REPORTED_REASON);
            assertThat(prompt.getClosedAt()).isEqualTo(NOW);
            verify(events).publish(space.getId(), SpaceEvent.Type.GIVE_PROMPT_CLOSED, Map.of("promptId", prompt.getId()));
        }

        @Test
        void reportsAfterBlockingJustCount() {
            CueDetection prompt = showing(stmarks, NOW.plusSeconds(60));
            stmarks.setReportCount(5);
            stmarks.block("already");
            when(links.insertReport(eq(stmarks.getId()), anyString(), eq(prompt.getId()), any())).thenReturn(1);

            service.report(principalOf(bisi), space.getId(), prompt.getId(), null);

            assertThat(stmarks.getReportCount()).isEqualTo(6);
            assertThat(stmarks.getBlockedReason()).isEqualTo("already");
            verify(detections, never()).findOpenForLink(any());
        }
    }
}

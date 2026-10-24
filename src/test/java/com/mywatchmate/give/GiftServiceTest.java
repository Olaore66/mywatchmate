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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.give.dto.GiftMade;
import com.mywatchmate.give.dto.GiftResponse;
import com.mywatchmate.give.dto.GiftResultRequest;
import com.mywatchmate.give.dto.GiftResultRequest.Outcome;
import com.mywatchmate.give.dto.GiveSummary;
import com.mywatchmate.give.dto.OpenGiftResponse;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GiftServiceTest {

    private static final Instant NOW = MONDAY_MIDNIGHT;

    @Mock GiftRepository gifts;
    @Mock CueDetectionRepository detections;
    @Mock GiveService give;
    @Mock SpaceAccess access;
    @Mock SpaceEvents events;

    private final GiveProperties props = new GiveProperties(List.of("payaza.africa"), Duration.ofMinutes(10),
        Duration.ofMinutes(5), 3, Duration.ofHours(1), "");
    private GiftService service;
    private Space space;
    private Participant ada;
    private Participant tunde;
    private Participant bisi;
    private WatchSession live;
    private PaymentLink stmarks;
    private CueDetection prompt;

    @BeforeEach
    void setUp() {
        service = new GiftService(gifts, detections, give, access, events, props, clockAt(NOW));
        space = space(user("Ada"));
        ada = member(space, space.getOwner(), Participant.Role.OWNER);
        tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
        bisi = guest(space, "Bisi");
        for (Participant p : List.of(ada, tunde, bisi)) {
            lenient().when(access.require(principalOf(p), space.getId())).thenReturn(membership(p));
        }
        live = session(space, NOW);
        live.start(NOW);
        stmarks = withId(new PaymentLink("https://pay.payaza.africa/give/stmarks", PaymentLink.Provider.PAYAZA));
        stmarks.setMerchantName("St Mark's Parish");
        prompt = withId(new CueDetection(live, CueDetection.CueType.QR, "raw", CueDetection.Status.SHOWN));
        prompt.setPaymentLink(stmarks);
        prompt.setExpiresAt(NOW.plusSeconds(300));
        lenient().when(give.promptIn(space.getId(), prompt.getId())).thenReturn(prompt);
        lenient().when(gifts.saveAndFlush(any(Gift.class))).thenAnswer(i -> withId(i.getArgument(0)));
    }

    private static <T> T withId(T entity) {
        if (ReflectionTestUtils.getField(entity, "id") == null) {
            ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        }
        return entity;
    }

    private Gift openedBy(Participant giver, boolean anonymous) {
        Gift gift = withId(new Gift(prompt, live, giver, stmarks, anonymous));
        lenient().when(gifts.findWithGiver(gift.getId())).thenReturn(Optional.of(gift));
        return gift;
    }

    private static GiftResultRequest completed(long amount) {
        return new GiftResultRequest(Outcome.COMPLETED, amount, "NGN", "PZ-" + amount);
    }

    @Nested
    class Opening {

        @Test
        void aViewerTapsGiveAndGetsTheCheckout() {
            OpenGiftResponse opened = service.open(principalOf(bisi), space.getId(), prompt.getId(), true);

            assertThat(opened.checkoutUrl()).isEqualTo("https://pay.payaza.africa/give/stmarks");
            assertThat(opened.merchantName()).isEqualTo("St Mark's Parish");
            assertThat(opened.notice()).isEqualTo("You're paying St Mark's Parish through Payaza. MyWatchMate never holds your money.");
            ArgumentCaptor<Gift> saved = ArgumentCaptor.forClass(Gift.class);
            verify(gifts).saveAndFlush(saved.capture());
            Gift gift = saved.getValue();
            assertThat(gift.getParticipant()).isSameAs(bisi);
            assertThat(gift.isAnonymous()).isTrue();
            assertThat(gift.getStatus()).isEqualTo(Gift.Status.OPENED);
            assertThat(gift.getOpenedAt()).isEqualTo(NOW);
            assertThat(gift.getDetection()).isSameAs(prompt);
            assertThat(opened.giftId()).isEqualTo(gift.getId());
        }

        @Test
        void aPromptTakenDownOrFromAnEndedSessionCantBeUsed() {
            prompt.close(NOW);
            assertApiError(() -> service.open(principalOf(bisi), space.getId(), prompt.getId(), false),
                HttpStatus.CONFLICT, "PROMPT_CLOSED");

            prompt.setClosedAt(null);
            live.end(NOW);
            assertApiError(() -> service.open(principalOf(bisi), space.getId(), prompt.getId(), false),
                HttpStatus.CONFLICT, "PROMPT_CLOSED");
            verify(gifts, never()).saveAndFlush(any());
        }

        @Test
        void aLinkBlockedSinceItWasShownCantBeUsed() {
            when(give.blockReason(space.getId(), stmarks)).thenReturn("BLOCKED_EVERYWHERE");

            assertApiError(() -> service.open(principalOf(bisi), space.getId(), prompt.getId(), false),
                HttpStatus.CONFLICT, "LINK_BLOCKED");
        }

        @Test
        void archivedRoomsTakeNoGifts() {
            space.archive(NOW);
            assertApiError(() -> service.open(principalOf(ada), space.getId(), prompt.getId(), false),
                HttpStatus.CONFLICT, "SPACE_ARCHIVED");
        }
    }

    @Nested
    class Results {

        @Test
        void aCompletedGiftTellsTheRoomTheNameButNeverTheAmount() {
            Gift gift = openedBy(tunde, false);

            GiftResponse response = service.reportResult(principalOf(tunde), gift.getId(), completed(500_000));

            assertThat(response.status()).isEqualTo(Gift.Status.COMPLETED);
            assertThat(response.amountMinor()).isEqualTo(500_000);
            assertThat(response.selfReported()).isTrue();
            assertThat(gift.getProviderTxnRef()).isEqualTo("PZ-500000");
            verify(events).publish(space.getId(), SpaceEvent.Type.GIFT_MADE, new GiftMade("Tunde", false));
        }

        @Test
        void anonymousGiftsAreSomeone() {
            Gift gift = openedBy(bisi, true);

            service.reportResult(principalOf(bisi), gift.getId(), completed(100_000));

            verify(events).publish(space.getId(), SpaceEvent.Type.GIFT_MADE, new GiftMade(null, true));
        }

        @Test
        void failedOrCancelledTellsNobody() {
            Gift gift = openedBy(tunde, false);

            service.reportResult(principalOf(tunde), gift.getId(), new GiftResultRequest(Outcome.CANCELLED, null, null, " "));

            assertThat(gift.getStatus()).isEqualTo(Gift.Status.CANCELLED);
            assertThat(gift.getProviderTxnRef()).as("blank reference stored as none").isNull();
            verifyNoInteractions(events);
        }

        @Test
        void onlyTheGiverAndOnlyOnce() {
            Gift gift = openedBy(tunde, false);

            assertApiError(() -> service.reportResult(principalOf(ada), gift.getId(), completed(1)),
                HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND");
            assertApiError(() -> service.reportResult(principalOf(bisi), gift.getId(), completed(1)),
                HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND");
            service.reportResult(principalOf(tunde), gift.getId(), completed(1));
            assertApiError(() -> service.reportResult(principalOf(tunde), gift.getId(), completed(2)),
                HttpStatus.CONFLICT, "GIFT_ALREADY_REPORTED");
        }

        @Test
        void guestsAreMatchedByTheirRoomIdentity() {
            Gift gift = openedBy(bisi, false);
            AuthPrincipal otherGuest = AuthPrincipal.guest(UUID.randomUUID(), space.getId());

            assertApiError(() -> service.reportResult(otherGuest, gift.getId(), completed(1)),
                HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND");
            assertThat(service.reportResult(principalOf(bisi), gift.getId(), completed(1)).status())
                .isEqualTo(Gift.Status.COMPLETED);
        }

        @Test
        void amountAndCurrencyGoTogether() {
            Gift gift = openedBy(tunde, false);

            assertApiError(() -> service.reportResult(principalOf(tunde), gift.getId(),
                new GiftResultRequest(Outcome.COMPLETED, 5000L, null, null)), HttpStatus.BAD_REQUEST, "AMOUNT_NEEDS_CURRENCY");
            assertApiError(() -> service.reportResult(principalOf(tunde), gift.getId(),
                new GiftResultRequest(Outcome.COMPLETED, null, "NGN", null)), HttpStatus.BAD_REQUEST, "AMOUNT_NEEDS_CURRENCY");
            assertThat(service.reportResult(principalOf(tunde), gift.getId(),
                new GiftResultRequest(Outcome.COMPLETED, null, null, null)).amountMinor()).isNull();
        }

        @Test
        void aPayazaReferenceCanOnlyBelongToOneGift() {
            Gift gift = openedBy(tunde, false);
            Gift other = openedBy(tunde, false);
            when(gifts.findByProviderTxnRef("PZ-9")).thenReturn(Optional.of(other));

            assertApiError(() -> service.reportResult(principalOf(tunde), gift.getId(), completed(9)),
                HttpStatus.CONFLICT, "DUPLICATE_REFERENCE");
        }

        @Test
        void aLateResultAfterGoingUnknownIsStillAccepted() {
            Gift gift = openedBy(tunde, false);
            gift.setStatus(Gift.Status.UNKNOWN);
            when(gifts.findByProviderTxnRef("PZ-7")).thenReturn(Optional.of(gift));

            assertThat(service.reportResult(principalOf(tunde), gift.getId(), completed(7)).status())
                .isEqualTo(Gift.Status.COMPLETED);
        }
    }

    @Nested
    class History {

        @Test
        void myGiftsAcrossRooms() {
            Gift gift = openedBy(tunde, true);
            when(gifts.findForUser(tunde.getUser().getId())).thenReturn(List.of(gift));

            List<GiftResponse> mine = service.mine(tunde.getUser().getId());

            assertThat(mine).hasSize(1);
            assertThat(mine.getFirst().spaceTitle()).isEqualTo("6am Prayer Room");
            assertThat(mine.getFirst().merchantName()).isEqualTo("St Mark's Parish");
            assertThat(mine.getFirst().anonymous()).isTrue();
        }
    }

    @Nested
    class Summary {

        @Test
        void countsAndTotalsPerCurrencyNeverWho() {
            Instant from = NOW.minus(Duration.ofDays(30));
            Gift ngn1 = openedBy(tunde, false);
            ngn1.report(Gift.Status.COMPLETED, 500_000L, "NGN", null, NOW);
            Gift ngn2 = openedBy(bisi, true);
            ngn2.report(Gift.Status.COMPLETED, 250_000L, "NGN", null, NOW);
            Gift gbp = openedBy(ada, false);
            gbp.report(Gift.Status.COMPLETED, 2_000L, "GBP", null, NOW);
            Gift noAmount = openedBy(ada, false);
            noAmount.report(Gift.Status.COMPLETED, null, null, null, NOW);
            Gift failed = openedBy(tunde, false);
            failed.report(Gift.Status.FAILED, null, null, null, NOW);
            Gift open = openedBy(bisi, false);
            Gift unknown = openedBy(bisi, false);
            unknown.setStatus(Gift.Status.UNKNOWN);
            when(gifts.findInSpace(space.getId(), from, NOW))
                .thenReturn(List.of(ngn1, ngn2, gbp, noAmount, failed, open, unknown));
            when(detections.countShownInSpace(space.getId(), from, NOW)).thenReturn(2L);

            GiveSummary summary = service.summary(principalOf(ada), space.getId(), null, null);

            assertThat(summary.promptsShown()).isEqualTo(2);
            assertThat(summary.giveTaps()).isEqualTo(7);
            assertThat(summary.completed()).isEqualTo(4);
            assertThat(summary.notCompleted()).isEqualTo(1);
            assertThat(summary.unreported()).isEqualTo(2);
            assertThat(summary.totals()).containsExactly(
                new GiveSummary.CurrencyTotal("GBP", 2_000, 1), new GiveSummary.CurrencyTotal("NGN", 750_000, 2));
            assertThat(summary.selfReported()).isTrue();
        }

        @Test
        void hostsOnlyAndSensibleRanges() {
            assertApiError(() -> service.summary(principalOf(tunde), space.getId(), null, null),
                HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            assertApiError(() -> service.summary(principalOf(ada), space.getId(), NOW, NOW.minusSeconds(1)),
                HttpStatus.BAD_REQUEST, "INVALID_RANGE");
            assertApiError(() -> service.summary(principalOf(ada), space.getId(), NOW.minus(Duration.ofDays(400)), NOW),
                HttpStatus.BAD_REQUEST, "INVALID_RANGE");
        }
    }

    @Test
    void unreportedGiftsExpireAfterAnHour() {
        when(gifts.expireOpenedBefore(NOW.minus(Duration.ofHours(1)))).thenReturn(3);

        assertThat(service.expireUnreported()).isEqualTo(3);
    }
}

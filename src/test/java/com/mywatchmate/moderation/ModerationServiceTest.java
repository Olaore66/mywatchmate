package com.mywatchmate.moderation;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.GiveProperties;
import com.mywatchmate.give.GiveService;
import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.give.PaymentLink.Status;
import com.mywatchmate.give.PaymentLinkRecognizer;
import com.mywatchmate.give.PaymentLinkRepository;
import com.mywatchmate.identity.User;
import com.mywatchmate.moderation.LinkReview.Decision;
import com.mywatchmate.moderation.dto.LinkDetail;
import com.mywatchmate.moderation.dto.LinkQueuePage;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ModerationServiceTest {

    private static final Instant NOW = MONDAY_MIDNIGHT;
    private static final AuthPrincipal RITA = AuthPrincipal.user(UUID.randomUUID());
    private static final String URL = "https://pay.payaza.africa/give/stmarks";

    @Mock StaffAccess staff;
    @Mock PaymentLinkRepository links;
    @Mock LinkReviewRepository reviews;
    @Mock LinkEvidence evidence;
    @Mock GiveService give;

    private final GiveProperties props = new GiveProperties(List.of("payaza.africa"), Duration.ofMinutes(10),
        Duration.ofMinutes(5), 3, Duration.ofHours(1), "secret");
    private ModerationService service;
    private User rita;

    @BeforeEach
    void setUp() {
        service = new ModerationService(staff, links, reviews, evidence, new PaymentLinkRecognizer(props), give, clockAt(NOW));
        rita = user("Rita");
        rita.setStaffRole(User.StaffRole.MODERATOR);
        lenient().when(staff.require(RITA)).thenReturn(rita);
        lenient().when(evidence.gifts(any())).thenReturn(new LinkDetail.Gifts(0, 0, 0, 0, 0));
        lenient().when(reviews.saveAndFlush(any(LinkReview.class))).thenAnswer(i -> {
            LinkReview review = i.getArgument(0);
            ReflectionTestUtils.setField(review, "id", UUID.randomUUID());
            return review;
        });
    }

    private static PaymentLink link(Status status, int reports, Instant changedAt) {
        PaymentLink link = new PaymentLink("https://pay.payaza.africa/give/" + UUID.randomUUID(), PaymentLink.Provider.PAYAZA);
        ReflectionTestUtils.setField(link, "id", UUID.randomUUID());
        link.changeStatus(status, "Reported by viewers; under review", changedAt);
        link.setReportCount(reports);
        return link;
    }

    private PaymentLink lockable(Status status, int reports) {
        PaymentLink link = link(status, reports, NOW.minusSeconds(3600));
        lenient().when(links.findForUpdate(link.getId())).thenReturn(Optional.of(link));
        return link;
    }

    private LinkReview savedReview() {
        ArgumentCaptor<LinkReview> saved = ArgumentCaptor.forClass(LinkReview.class);
        verify(reviews).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    @Nested
    class Queue {

        @Test
        void heldLinksComeLongestWaitingFirstAPageAtATime() {
            List<PaymentLink> held = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                held.add(link(Status.HELD, 3, NOW.plusSeconds(i)));
            }
            when(links.findHeld(Limit.of(3))).thenReturn(held);
            when(links.countByStatus(Status.HELD)).thenReturn(7L);

            LinkQueuePage first = service.queue(RITA, null, null, 2);

            assertThat(first.links()).extracting(s -> s.id()).containsExactly(held.get(0).getId(), held.get(1).getId());
            assertThat(first.waiting()).isEqualTo(7);
            ReviewCursor cursor = ReviewCursor.decode(first.nextCursor());
            assertThat(cursor).isEqualTo(new ReviewCursor(held.get(1).getStatusChangedAt(), held.get(1).getId()));

            when(links.findHeldAfter(cursor.at(), cursor.id(), Limit.of(3))).thenReturn(List.of(held.get(2)));
            LinkQueuePage second = service.queue(RITA, Status.HELD, first.nextCursor(), 2);
            assertThat(second.links()).hasSize(1);
            assertThat(second.nextCursor()).isNull();
        }

        @Test
        void blockedLinksCanBeListedToo() {
            PaymentLink blocked = link(Status.BLOCKED, 0, NOW);
            when(links.findBlocked(Limit.of(ModerationService.DEFAULT_PAGE + 1))).thenReturn(List.of(blocked));

            assertThat(service.queue(RITA, Status.BLOCKED, " ", null).links()).hasSize(1);

            ReviewCursor cursor = ReviewCursor.of(blocked);
            when(links.findBlockedAfter(cursor.at(), cursor.id(), Limit.of(ModerationService.MAX_PAGE + 1))).thenReturn(List.of());
            assertThat(service.queue(RITA, Status.BLOCKED, cursor.encode(), 5000).links()).isEmpty();
        }

        @Test
        void pageSizesAreKeptSensibleAndAllowedLinksArentAQueue() {
            when(links.findHeld(Limit.of(2))).thenReturn(List.of());
            service.queue(RITA, Status.HELD, null, 0);
            verify(links).findHeld(Limit.of(2));

            assertApiError(() -> service.queue(RITA, Status.ALLOWED, null, null), HttpStatus.BAD_REQUEST, "INVALID_STATUS");
            assertApiError(() -> service.queue(RITA, Status.HELD, "not-a-cursor", null), HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
        }

        @Test
        void nonStaffGetNothing() {
            AuthPrincipal ada = AuthPrincipal.user(UUID.randomUUID());
            when(staff.require(ada)).thenThrow(ApiException.forbidden("STAFF_ONLY", "no"));

            assertApiError(() -> service.queue(ada, null, null, null), HttpStatus.FORBIDDEN, "STAFF_ONLY");
            assertApiError(() -> service.detail(ada, UUID.randomUUID()), HttpStatus.FORBIDDEN, "STAFF_ONLY");
            assertApiError(() -> service.decide(ada, UUID.randomUUID(), Decision.CLEAR, "n", Status.HELD),
                HttpStatus.FORBIDDEN, "STAFF_ONLY");
            assertApiError(() -> service.block(ada, URL, "n"), HttpStatus.FORBIDDEN, "STAFF_ONLY");
            verifyNoInteractions(links, reviews, evidence, give);
        }
    }

    @Nested
    class Detail {

        @Test
        void bringsTogetherTheEvidenceAndHistory() {
            PaymentLink link = link(Status.HELD, 3, NOW);
            when(links.findById(link.getId())).thenReturn(Optional.of(link));
            LinkDetail.Report report = new LinkDetail.Report("Not ours", NOW, "GUEST", "Sunday Service", false);
            when(evidence.reports(link.getId())).thenReturn(List.of(report));
            when(evidence.rooms(link.getId())).thenReturn(List.of());
            LinkReview past = new LinkReview(link, rita, Decision.CLEAR, Status.HELD, Status.ALLOWED, "ok", 3, NOW.minusSeconds(60));
            when(reviews.findByPaymentLinkIdOrderByDecidedAtDesc(link.getId())).thenReturn(List.of(past));

            LinkDetail detail = service.detail(RITA, link.getId());

            assertThat(detail.link().openReports()).isEqualTo(3);
            assertThat(detail.statusReason()).isEqualTo("Reported by viewers; under review");
            assertThat(detail.reports()).containsExactly(report);
            assertThat(detail.history()).singleElement()
                .satisfies(r -> {
                    assertThat(r.reviewerName()).isEqualTo("Rita");
                    assertThat(r.decision()).isEqualTo(Decision.CLEAR);
                });
        }

        @Test
        void anUnknownLinkIsNotFound() {
            UUID id = UUID.randomUUID();
            when(links.findById(id)).thenReturn(Optional.empty());
            when(links.findForUpdate(id)).thenReturn(Optional.empty());

            assertApiError(() -> service.detail(RITA, id), HttpStatus.NOT_FOUND, "LINK_NOT_FOUND");
            assertApiError(() -> service.decide(RITA, id, Decision.CLEAR, "n", Status.HELD), HttpStatus.NOT_FOUND, "LINK_NOT_FOUND");
        }
    }

    @Nested
    class Decisions {

        @ParameterizedTest
        @CsvSource({
            "CONFIRM_SCAM, HELD,    BLOCKED",
            "CONFIRM_SCAM, ALLOWED, BLOCKED",
            "CONFIRM_SCAM, BLOCKED, ",
            "CLEAR,        HELD,    ALLOWED",
            "CLEAR,        BLOCKED, ALLOWED",
            "CLEAR,        ALLOWED, "
        })
        void onlyTheseMovesAreAllowed(Decision decision, Status from, Status to) {
            assertThat(ModerationService.target(decision, from)).isEqualTo(to);
        }

        @Test
        void confirmingBlocksItTakesItDownAndKeepsARecord() {
            PaymentLink link = lockable(Status.HELD, 4);

            LinkDetail detail = service.decide(RITA, link.getId(), Decision.CONFIRM_SCAM, "  Wrong account name ", Status.HELD);

            assertThat(link.getStatus()).isEqualTo(Status.BLOCKED);
            assertThat(link.getBlockedReason()).isEqualTo(ModerationService.CONFIRMED_REASON);
            assertThat(link.getStatusChangedAt()).isEqualTo(NOW);
            assertThat(link.getReportCount()).isZero();
            assertThat(detail.link().status()).isEqualTo(Status.BLOCKED);
            LinkReview review = savedReview();
            assertThat(review.getReviewer()).isSameAs(rita);
            assertThat(review.getReviewerName()).isEqualTo("Rita");
            assertThat(review.getFromStatus()).isEqualTo(Status.HELD);
            assertThat(review.getToStatus()).isEqualTo(Status.BLOCKED);
            assertThat(review.getNote()).isEqualTo("Wrong account name");
            assertThat(review.getReportsReviewed()).isEqualTo(4);
            assertThat(review.getDecidedAt()).isEqualTo(NOW);
            verify(links).markReportsReviewed(link.getId(), review.getId());
            verify(give).takeDownEverywhere(link.getId(), NOW);
        }

        @Test
        void clearingAllowsItAgainAndRetiresItsReports() {
            PaymentLink link = lockable(Status.HELD, 3);

            service.decide(RITA, link.getId(), Decision.CLEAR, "Checked with the church", Status.HELD);

            assertThat(link.getStatus()).isEqualTo(Status.ALLOWED);
            assertThat(link.getBlockedReason()).isNull();
            assertThat(link.getReportCount()).isZero();
            UUID reviewId = savedReview().getId();
            verify(links).markReportsReviewed(link.getId(), reviewId);
            verify(give, never()).takeDownEverywhere(any(), any());
        }

        @Test
        void aStatusThatChangedMeanwhileIsNotOverruled() {
            PaymentLink link = lockable(Status.BLOCKED, 0);

            assertApiError(() -> service.decide(RITA, link.getId(), Decision.CLEAR, "n", Status.HELD),
                HttpStatus.CONFLICT, "LINK_STATUS_CHANGED");

            assertThat(link.getStatus()).isEqualTo(Status.BLOCKED);
            verifyNoInteractions(reviews);
        }

        @Test
        void aMoveThatMakesNoSenseIsRefused() {
            PaymentLink link = lockable(Status.ALLOWED, 1);

            assertApiError(() -> service.decide(RITA, link.getId(), Decision.CLEAR, "n", Status.ALLOWED),
                HttpStatus.CONFLICT, "DECISION_NOT_ALLOWED");

            assertThat(link.getReportCount()).isEqualTo(1);
            verifyNoInteractions(reviews);
        }
    }

    @Nested
    class BlockingByUrl {

        @Test
        void aLinkNobodyHasSeenIsCreatedAndBlocked() {
            when(links.findByNormalizedUrl(URL)).thenReturn(Optional.empty());
            when(links.saveAndFlush(any(PaymentLink.class))).thenAnswer(i -> {
                PaymentLink created = i.getArgument(0);
                ReflectionTestUtils.setField(created, "id", UUID.randomUUID());
                when(links.findForUpdate(created.getId())).thenReturn(Optional.of(created));
                return created;
            });

            LinkDetail detail = service.block(RITA, URL + "?utm_source=wa", " Impersonating St Mark's ");

            assertThat(detail.link().url()).isEqualTo(URL);
            assertThat(detail.link().status()).isEqualTo(Status.BLOCKED);
            assertThat(detail.link().firstSeenAt()).isEqualTo(NOW);
            LinkReview review = savedReview();
            assertThat(review.getFromStatus()).isEqualTo(Status.ALLOWED);
            assertThat(review.getNote()).isEqualTo("Impersonating St Mark's");
            verify(give).takeDownEverywhere(detail.link().id(), NOW);
        }

        @Test
        void aKnownLinkIsBlockedAndAnAlreadyBlockedOneIsLeftAsItIs() {
            PaymentLink held = lockable(Status.HELD, 3);
            when(links.findByNormalizedUrl(held.getNormalizedUrl())).thenReturn(Optional.of(held));
            service.block(RITA, held.getNormalizedUrl(), "Scam");
            assertThat(held.getStatus()).isEqualTo(Status.BLOCKED);
            assertThat(savedReview().getFromStatus()).isEqualTo(Status.HELD);

            PaymentLink blocked = lockable(Status.BLOCKED, 0);
            when(links.findByNormalizedUrl(blocked.getNormalizedUrl())).thenReturn(Optional.of(blocked));
            assertThat(service.block(RITA, blocked.getNormalizedUrl(), "Again").link().status()).isEqualTo(Status.BLOCKED);
            verify(reviews).saveAndFlush(any());
        }

        @Test
        void onlyPayazaLinksCanBeBlocked() {
            assertApiError(() -> service.block(RITA, "https://paystack.com/pay/x", "n"),
                HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_PAYMENT_LINK");
            verifyNoInteractions(links, reviews);
        }
    }
}

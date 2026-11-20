package com.mywatchmate.church;

import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.church.dto.ChangeDecisionRequest;
import com.mywatchmate.church.dto.ChurchApplicationRequest;
import com.mywatchmate.church.dto.ChurchChangeRequest;
import com.mywatchmate.church.dto.ChurchChangeResponse;
import com.mywatchmate.church.dto.ChurchDecisionRequest;
import com.mywatchmate.church.dto.ChurchDetail;
import com.mywatchmate.church.dto.ChurchResponse;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.give.GiveService;
import com.mywatchmate.give.PayazaAccount;
import com.mywatchmate.give.PayazaClient;
import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.give.dto.InRoomCheckout;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.moderation.StaffAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;

/** The registry's services on their own, for the refusals and rarer paths the HTTP flow doesn't walk through. */
@ExtendWith(MockitoExtension.class)
class ChurchServicesTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final String KEY = "PZ78-PKLIVE-STREAMSOFJOY";

    @Mock ChurchRepository churches;
    @Mock ChurchChangeRepository changes;
    @Mock ChurchReviewRepository reviews;
    @Mock ChurchLinks links;
    @Mock UserRepository users;
    @Mock ApplicationEventPublisher events;
    @Mock StaffAccess staff;
    @Mock PayazaClient payaza;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private User grace;
    private User rita;
    private AuthPrincipal graceSignedIn;
    private AuthPrincipal ritaSignedIn;

    @BeforeEach
    void people() {
        grace = user("Grace Media");
        rita = user("Rita Staff");
        rita.setStaffRole(User.StaffRole.ADMIN);
        graceSignedIn = AuthPrincipal.user(grace.getId());
        ritaSignedIn = AuthPrincipal.user(rita.getId());
        lenient().when(users.findById(grace.getId())).thenReturn(Optional.of(grace));
        lenient().when(staff.require(ritaSignedIn)).thenReturn(rita);
    }

    private Church church(Church.Status status, User appliedBy) {
        Church church = new Church("Streams of Joy", appliedBy, NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(church, "id", UUID.randomUUID());
        church.setPublicKey(KEY);
        church.setMode(PayazaAccount.Mode.LIVE);
        church.changeStatus(status, NOW.minusSeconds(60));
        lenient().when(churches.findWithLinks(church.getId())).thenReturn(Optional.of(church));
        lenient().when(churches.findForUpdate(church.getId())).thenReturn(Optional.of(church));
        lenient().when(churches.findById(church.getId())).thenReturn(Optional.of(church));
        return church;
    }

    private static PaymentLink link(String slug) {
        PaymentLink link = new PaymentLink("https://business.payaza.africa/pay/" + slug, PaymentLink.Provider.PAYAZA);
        ReflectionTestUtils.setField(link, "id", UUID.randomUUID());
        return link;
    }

    @Nested
    class Applying {

        private ChurchService service;

        @BeforeEach
        void setUp() {
            service = new ChurchService(churches, changes, links, users, events, clock);
            lenient().when(links.check(anyList(), any())).thenReturn(new LinkedHashSet<>(Set.of(link("nsppd"))));
            lenient().when(churches.saveAndFlush(any(Church.class))).thenAnswer(i -> {
                Church c = i.getArgument(0);
                ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
                return c;
            });
        }

        private ChurchApplicationRequest request(String website, String email, String phone, String currency) {
            return new ChurchApplicationRequest(" Streams of Joy ", website, email, phone, " " + KEY + " ",
                PayazaAccount.Mode.LIVE, currency, List.of("https://business.payaza.africa/pay/nsppd"));
        }

        @Test
        void blanksAreLeftOutAndTheCurrencyDefaultsToNaira() {
            ChurchResponse applied = service.apply(graceSignedIn, request("  ", " ", null, null));

            assertThat(applied.name()).isEqualTo("Streams of Joy");
            assertThat(applied.website()).isNull();
            assertThat(applied.officialEmail()).isNull();
            assertThat(applied.officialPhone()).isNull();
            assertThat(applied.currencyCode()).isEqualTo("NGN");
            assertThat(applied.status()).isEqualTo(Church.Status.PENDING);
            verify(events).publishEvent(any(ChurchEvents.Applied.class));
        }

        @Test
        void givenDetailsAreKeptTrimmed() {
            ChurchResponse applied = service.apply(graceSignedIn,
                request(" https://www.streamsofjoy.org ", "info@streamsofjoy.org", " +2348012345678 ", "GHS"));

            assertThat(applied.website()).isEqualTo("https://www.streamsofjoy.org");
            assertThat(applied.officialPhone()).isEqualTo("+2348012345678");
            assertThat(applied.currencyCode()).isEqualTo("GHS");
            assertThat(applied.publicKey()).isEqualTo("PZ78-PKL…FJOY");
        }

        @Test
        void losingARaceForALinkIsAConflictNotAnError() {
            when(churches.saveAndFlush(any(Church.class))).thenThrow(new DataIntegrityViolationException("uq_church_links_link"));

            assertApiError(() -> service.apply(graceSignedIn, request(null, null, null, null)), HttpStatus.CONFLICT, "LINK_CLAIMED");
            verifyNoInteractions(events);
        }

        @Test
        void guestsAndGoneAccountsCantApply() {
            assertApiError(() -> service.apply(AuthPrincipal.guest(UUID.randomUUID(), UUID.randomUUID()), request(null, null, null, null)),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
            UUID gone = UUID.randomUUID();
            when(users.findById(gone)).thenReturn(Optional.empty());
            assertApiError(() -> service.apply(AuthPrincipal.user(gone), request(null, null, null, null)),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }

        @Test
        void onlyWhoeverAppliedCanAskForAChangeAndOnlyOnceVerified() {
            Church theirs = church(Church.Status.VERIFIED, grace);
            Church someoneElses = church(Church.Status.VERIFIED, user("Other"));
            Church noApplicant = church(Church.Status.VERIFIED, null);
            Church pending = church(Church.Status.PENDING, grace);
            ChurchChangeRequest newKey = new ChurchChangeRequest("PZ78-PKLIVE-NEWKEY2026", null, null);

            assertApiError(() -> service.requestChange(graceSignedIn, someoneElses.getId(), newKey), HttpStatus.NOT_FOUND, "CHURCH_NOT_FOUND");
            assertApiError(() -> service.requestChange(graceSignedIn, noApplicant.getId(), newKey), HttpStatus.NOT_FOUND, "CHURCH_NOT_FOUND");
            assertApiError(() -> service.requestChange(graceSignedIn, pending.getId(), newKey), HttpStatus.CONFLICT, "CHURCH_NOT_VERIFIED");
            verify(changes, never()).saveAndFlush(any());

            theirs.changeStatus(Church.Status.SUSPENDED, NOW);
            when(changes.saveAndFlush(any(ChurchChange.class))).thenAnswer(i -> i.getArgument(0));
            assertThat(service.requestChange(graceSignedIn, theirs.getId(), newKey).publicKey()).isEqualTo("PZ78-PKL…2026");
        }

        @Test
        void aNewListOfLinksIsCheckedAndStoredAsTheirTidyForm() {
            Church theirs = church(Church.Status.VERIFIED, grace);
            when(links.check(List.of("https://business.payaza.africa/pay/nsppd2?utm_source=x"), theirs.getId()))
                .thenReturn(new LinkedHashSet<>(Set.of(link("nsppd2"))));
            when(changes.saveAndFlush(any(ChurchChange.class))).thenAnswer(i -> i.getArgument(0));

            ChurchChangeResponse change = service.requestChange(graceSignedIn, theirs.getId(),
                new ChurchChangeRequest(null, PayazaAccount.Mode.TEST, List.of("https://business.payaza.africa/pay/nsppd2?utm_source=x")));

            assertThat(change.paymentLinks()).containsExactly("https://business.payaza.africa/pay/nsppd2");
            assertThat(change.mode()).isEqualTo(PayazaAccount.Mode.TEST);
            assertThat(change.publicKey()).isNull();
            verify(events).publishEvent(new ChurchEvents.ChangeRequested(theirs.getId()));
        }

        @Test
        void anEmptyChangeIsOnlyEmptyWhenNothingIsSent() {
            assertThat(new ChurchChangeRequest(null, null, null).isEmpty()).isTrue();
            assertThat(new ChurchChangeRequest("PZ78-PKLIVE-X1234567", null, null).isEmpty()).isFalse();
            assertThat(new ChurchChangeRequest(null, PayazaAccount.Mode.TEST, null).isEmpty()).isFalse();
            assertThat(new ChurchChangeRequest(null, null, List.of("x")).isEmpty()).isFalse();
        }
    }

    @Nested
    class CheckingLinks {

        @Mock GiveService give;

        @Test
        void heldOrBlockedLinksAreRefusedAndAChurchKeepsItsOwn() {
            ChurchLinks checker = new ChurchLinks(give, churches);
            PaymentLink held = link("held");
            held.changeStatus(PaymentLink.Status.HELD, "reported", NOW);
            PaymentLink blocked = link("blocked");
            blocked.changeStatus(PaymentLink.Status.BLOCKED, "scam", NOW);
            PaymentLink ours = link("ours");
            when(give.knownLink("held")).thenReturn(held);
            when(give.knownLink("blocked")).thenReturn(blocked);
            when(give.knownLink("ours")).thenReturn(ours);
            Church owner = church(Church.Status.VERIFIED, grace);
            when(churches.findByLink(ours.getId())).thenReturn(Optional.of(owner));

            ApiException heldError = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> checker.check(List.of("held"), null));
            assertThat(heldError.getCode()).isEqualTo("LINK_BLOCKED");
            assertThat(heldError.getMessage()).contains("reviewing");
            assertThat(org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> checker.check(List.of("blocked"), null)).getMessage()).contains("blocked on MyWatchMate");

            assertThat(checker.check(List.of("ours"), owner.getId())).containsExactly(ours);
            assertApiError(() -> checker.check(List.of("ours"), UUID.randomUUID()), HttpStatus.CONFLICT, "LINK_CLAIMED");
        }
    }

    @Nested
    class Reviewing {

        private ChurchReviewService service;

        @BeforeEach
        void setUp() {
            JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
            service = new ChurchReviewService(staff, churches, changes, reviews, links, payaza, jdbc, clock);
        }

        @Test
        void theListDefaultsToWhatsWaiting() {
            when(churches.findByStatusOldestFirst(eq(Church.Status.PENDING), any(Limit.class))).thenReturn(List.of());
            Church suspended = church(Church.Status.SUSPENDED, grace);
            when(churches.findByStatusOldestFirst(eq(Church.Status.SUSPENDED), any(Limit.class))).thenReturn(List.of(suspended));
            when(changes.countByStatus(ChurchChange.Status.PENDING)).thenReturn(2L);

            assertThat(service.list(ritaSignedIn, null).churches()).isEmpty();
            assertThat(service.list(ritaSignedIn, Church.Status.SUSPENDED).churches()).hasSize(1);
            assertThat(service.list(ritaSignedIn, null).pendingChanges()).isEqualTo(2);
        }

        @Test
        void anUnknownChurchIsNotFoundAndAChurchWithNoApplicantStillShows() {
            UUID unknown = UUID.randomUUID();
            when(churches.findWithLinks(unknown)).thenReturn(Optional.empty());
            assertApiError(() -> service.detail(ritaSignedIn, unknown), HttpStatus.NOT_FOUND, "CHURCH_NOT_FOUND");

            Church staffAdded = church(Church.Status.PENDING, null);
            ChurchDetail detail = service.detail(ritaSignedIn, staffAdded.getId());
            assertThat(detail.appliedBy()).isNull();
            assertThat(detail.checklist()).hasSize(4);
        }

        @Test
        void approvingRecordsWhoAndWhenAndRejectingFreesTheLinks() {
            Church pending = church(Church.Status.PENDING, grace);
            pending.getLinks().add(link("nsppd"));

            service.decide(ritaSignedIn, pending.getId(),
                new ChurchDecisionRequest(ChurchDecisionRequest.Decision.REJECT, " Impostor ", Church.Status.PENDING));

            assertThat(pending.getStatus()).isEqualTo(Church.Status.REJECTED);
            assertThat(pending.getLinks()).isEmpty();
            assertThat(pending.getVerifiedBy()).isNull();
            ArgumentCaptor<ChurchReview> saved = ArgumentCaptor.forClass(ChurchReview.class);
            verify(reviews).save(saved.capture());
            assertThat(saved.getValue().getNote()).isEqualTo("Impostor");
            assertThat(saved.getValue().getReviewerName()).isEqualTo("Rita Staff");

            Church another = church(Church.Status.PENDING, grace);
            service.decide(ritaSignedIn, another.getId(),
                new ChurchDecisionRequest(ChurchDecisionRequest.Decision.APPROVE, "Checked", Church.Status.PENDING));
            assertThat(another.getVerifiedBy()).isSameAs(rita);
            assertThat(another.getVerifiedAt()).isEqualTo(NOW);
        }

        @Test
        void aRejectedChangeLeavesEverythingAsItWas() {
            Church verified = church(Church.Status.VERIFIED, grace);
            ChurchChange change = new ChurchChange(verified, "PZ78-PKLIVE-NEWKEY2026", PayazaAccount.Mode.TEST, null, grace, NOW);
            when(changes.findForUpdate(change.getId())).thenReturn(Optional.of(change));

            service.decideChange(ritaSignedIn, change.getId(), new ChangeDecisionRequest(ChangeDecisionRequest.Decision.REJECT, "Not theirs"));

            assertThat(verified.getPublicKey()).isEqualTo(KEY);
            assertThat(verified.getMode()).isEqualTo(PayazaAccount.Mode.LIVE);
            assertThat(change.getStatus()).isEqualTo(ChurchChange.Status.REJECTED);
            assertThat(change.getDecidedAt()).isEqualTo(NOW);
            ArgumentCaptor<ChurchReview> saved = ArgumentCaptor.forClass(ChurchReview.class);
            verify(reviews).save(saved.capture());
            assertThat(saved.getValue().getDecision()).isEqualTo(ChurchReview.Decision.REJECT_CHANGE);
        }

        @Test
        void anApprovedChangeSwapsTheModeAndLinksAndLeavesAKeyItDidntMention() {
            Church verified = church(Church.Status.VERIFIED, grace);
            PaymentLink old = link("old");
            verified.getLinks().add(old);
            PaymentLink replacement = link("new");
            ChurchChange change = new ChurchChange(verified, null, PayazaAccount.Mode.TEST,
                List.of("https://business.payaza.africa/pay/new"), grace, NOW);
            when(changes.findForUpdate(change.getId())).thenReturn(Optional.of(change));
            when(links.check(List.of("https://business.payaza.africa/pay/new"), verified.getId()))
                .thenReturn(new LinkedHashSet<>(Set.of(replacement)));

            service.decideChange(ritaSignedIn, change.getId(), new ChangeDecisionRequest(ChangeDecisionRequest.Decision.APPROVE, "Tested"));

            assertThat(verified.getPublicKey()).isEqualTo(KEY);
            assertThat(verified.getMode()).isEqualTo(PayazaAccount.Mode.TEST);
            assertThat(verified.getLinks()).containsExactly(replacement);
            assertThat(change.getStatus()).isEqualTo(ChurchChange.Status.APPROVED);
        }

        @Test
        void aChangeThatIsntWaitingIsNotFound() {
            Church verified = church(Church.Status.VERIFIED, grace);
            ChurchChange decided = new ChurchChange(verified, KEY, null, null, grace, NOW);
            decided.setStatus(ChurchChange.Status.APPROVED);
            when(changes.findForUpdate(decided.getId())).thenReturn(Optional.of(decided));

            assertApiError(() -> service.decideChange(ritaSignedIn, decided.getId(),
                new ChangeDecisionRequest(ChangeDecisionRequest.Decision.APPROVE, "x")), HttpStatus.NOT_FOUND, "CHANGE_NOT_FOUND");
        }

        @Test
        void aTestPaymentUsesTheChurchsKeyOrThePendingOneFillingGapsFromTheCurrent() {
            Church verified = church(Church.Status.VERIFIED, grace);
            rita.setDisplayName("Rita");

            InRoomCheckout current = service.testCheckout(ritaSignedIn, verified.getId(), false);
            assertThat(current.merchantKey()).isEqualTo(KEY);
            assertThat(current.connectionMode()).isEqualTo("Live");
            assertThat(current.transactionReference()).matches("MWMTEST[0-9A-F]{28}");
            assertThat(current.firstName()).isEqualTo("Rita");
            assertThat(current.lastName()).isNull();

            assertApiError(() -> service.testCheckout(ritaSignedIn, verified.getId(), true), HttpStatus.NOT_FOUND, "CHANGE_NOT_FOUND");

            ChurchChange modeOnly = new ChurchChange(verified, null, PayazaAccount.Mode.TEST, null, grace, NOW);
            when(changes.findByChurchIdAndStatus(verified.getId(), ChurchChange.Status.PENDING)).thenReturn(Optional.of(modeOnly));
            InRoomCheckout pending = service.testCheckout(ritaSignedIn, verified.getId(), true);
            assertThat(pending.merchantKey()).as("the key it didn't change").isEqualTo(KEY);
            assertThat(pending.connectionMode()).isEqualTo("Test");

            ChurchChange keyOnly = new ChurchChange(verified, "PZ78-PKLIVE-NEWKEY2026", null, null, grace, NOW);
            when(changes.findByChurchIdAndStatus(verified.getId(), ChurchChange.Status.PENDING)).thenReturn(Optional.of(keyOnly));
            when(payaza.status("PZ78-PKLIVE-NEWKEY2026", PayazaAccount.Mode.LIVE, "MWMTEST" + "A".repeat(28)))
                .thenReturn(new PayazaClient.Payment(PayazaClient.Outcome.COMPLETED, 10_000L, "NGN", "PZ-1"));
            assertThat(service.testCheckoutResult(ritaSignedIn, verified.getId(), "MWMTEST" + "A".repeat(28), true).outcome())
                .isEqualTo(PayazaClient.Outcome.COMPLETED);
        }

        @Test
        void onlyReferencesWeHandedOutCanBeLookedUp() {
            Church verified = church(Church.Status.VERIFIED, grace);

            for (String forged : new String[] {"MWM" + "A".repeat(32), "MWMTEST" + "a".repeat(28), "anything"}) {
                assertApiError(() -> service.testCheckoutResult(ritaSignedIn, verified.getId(), forged, false),
                    HttpStatus.BAD_REQUEST, "INVALID_REFERENCE");
            }
            verifyNoInteractions(payaza);
        }

        @Test
        void nonStaffGetNothing() {
            AuthPrincipal grace = graceSignedIn;
            when(staff.require(grace)).thenThrow(ApiException.forbidden("STAFF_ONLY", "no"));

            assertApiError(() -> service.list(grace, null), HttpStatus.FORBIDDEN, "STAFF_ONLY");
            assertApiError(() -> service.testCheckout(grace, UUID.randomUUID(), false), HttpStatus.FORBIDDEN, "STAFF_ONLY");
            assertApiError(() -> service.decide(grace, UUID.randomUUID(),
                new ChurchDecisionRequest(ChurchDecisionRequest.Decision.APPROVE, "x", Church.Status.PENDING)),
                HttpStatus.FORBIDDEN, "STAFF_ONLY");
            verifyNoInteractions(churches, reviews, payaza);
        }
    }

    @Test
    void aChangeShowsItsKeyShortenedOrNothingWhenTheKeyStays() {
        Church verified = church(Church.Status.VERIFIED, grace);

        assertThat(ChurchChangeResponse.from(new ChurchChange(verified, null, PayazaAccount.Mode.TEST, null, grace, NOW)).publicKey())
            .isNull();
        assertThat(ChurchChangeResponse.from(new ChurchChange(verified, "PZ78-PKLIVE-NEWKEY2026", null, null, grace, NOW)).publicKey())
            .isEqualTo("PZ78-PKL…2026");
    }
}

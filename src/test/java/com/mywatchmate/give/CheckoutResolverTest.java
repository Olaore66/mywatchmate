package com.mywatchmate.give;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CheckoutResolverTest {

    @Mock PayazaAccountRepository rooms;
    @Mock VerifiedChurches churches;

    private final UUID room = UUID.randomUUID();
    private PaymentLink link;
    private PayazaAccount roomAccount;
    private CheckoutResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CheckoutResolver(rooms, churches);
        link = new PaymentLink("https://business.payaza.africa/pay/nsppd", PaymentLink.Provider.PAYAZA);
        ReflectionTestUtils.setField(link, "id", UUID.randomUUID());
        roomAccount = new PayazaAccount(room);
        roomAccount.setPublicKey("PZ78-PKTEST-ROOMROOM");
        roomAccount.setMode(PayazaAccount.Mode.TEST);
        roomAccount.setMerchantName("Our fellowship");
        lenient().when(rooms.findForLink(room, link.getId())).thenReturn(Optional.of(roomAccount));
    }

    @Test
    void aVerifiedChurchsLinkPaysTheChurchWhateverTheRoomRegistered() {
        CheckoutAccount church = new CheckoutAccount("PZ78-PKLIVE-CHURCH01", PayazaAccount.Mode.LIVE, "Streams of Joy",
            "NGN", UUID.randomUUID());
        when(churches.claims(link.getId())).thenReturn(true);
        when(churches.verifiedFor(link.getId())).thenReturn(Optional.of(church));

        assertThat(resolver.resolve(room, link)).contains(church);
        assertThat(church.verifiedChurch()).isTrue();
    }

    @Test
    void aClaimedButUnverifiedChurchLinkOpensInANewTabEvenIfARoomRegisteredIt() {
        when(churches.claims(link.getId())).thenReturn(true);
        when(churches.verifiedFor(link.getId())).thenReturn(Optional.empty());

        assertThat(resolver.resolve(room, link)).isEmpty();
    }

    @Test
    void otherwiseTheRoomsOwnRegistrationThenNothing() {
        assertThat(resolver.resolve(room, link)).hasValueSatisfying(account -> {
            assertThat(account.publicKey()).isEqualTo("PZ78-PKTEST-ROOMROOM");
            assertThat(account.verifiedChurch()).isFalse();
        });
        assertThat(resolver.resolve(UUID.randomUUID(), link)).isEmpty();
    }
}

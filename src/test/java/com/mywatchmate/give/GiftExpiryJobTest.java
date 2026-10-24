package com.mywatchmate.give;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class GiftExpiryJobTest {

    @Mock GiftService gifts;
    @Mock PlatformTransactionManager txManager;

    @Test
    void expiresInATransaction() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(gifts.expireUnreported()).thenReturn(2);
        GiftExpiryJob job = new GiftExpiryJob(gifts, new TransactionTemplate(txManager));

        job.run();

        verify(gifts).expireUnreported();
        verify(txManager).commit(any());
        assertThat(job.expire()).isEqualTo(2);
    }

    @Test
    void nothingReturnedCountsAsNone() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenReturn(null);

        assertThat(new GiftExpiryJob(gifts, tx).expire()).isZero();
    }
}

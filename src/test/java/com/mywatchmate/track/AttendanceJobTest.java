package com.mywatchmate.track;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class AttendanceJobTest {

    @Mock WatchSessionRepository sessions;
    @Mock AttendanceService attendance;
    @Mock PlatformTransactionManager txManager;

    private AttendanceJob job;

    @BeforeEach
    void setUp() {
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        job = new AttendanceJob(sessions, attendance, new TransactionTemplate(txManager), clockAt(MONDAY_MIDNIGHT));
    }

    @Test
    void creditsEveryLiveSessionAtTheSameMoment() {
        Space space = space(user("Ada"));
        WatchSession morning = session(space, MONDAY_MIDNIGHT);
        WatchSession other = session(space(user("Kemi")), MONDAY_MIDNIGHT);
        when(sessions.lockLive()).thenReturn(List.of(morning, other));

        assertThat(job.tick()).isEqualTo(2);

        verify(attendance).credit(morning, MONDAY_MIDNIGHT);
        verify(attendance).credit(other, MONDAY_MIDNIGHT);
        verify(txManager).commit(any());
    }

    @Test
    void aTransactionThatReturnsNothingCountsAsNone() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenReturn(null);

        assertThat(new AttendanceJob(sessions, attendance, tx, clockAt(MONDAY_MIDNIGHT)).tick()).isZero();
    }

    @Test
    void nothingLiveNothingToDo() {
        when(sessions.lockLive()).thenReturn(List.of());

        job.run();

        verifyNoInteractions(attendance);
    }
}

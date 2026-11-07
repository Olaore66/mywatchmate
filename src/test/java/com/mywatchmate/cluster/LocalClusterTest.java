package com.mywatchmate.cluster;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mywatchmate.common.LocalRateLimiters;
import com.mywatchmate.common.RateLimiter;
import com.mywatchmate.live.LocalRoomPresence;
import com.mywatchmate.live.PresenceTracker;
import com.mywatchmate.live.dto.OnlineViewer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

/** LOCAL mode: one copy of the app, everything in memory. */
class LocalClusterTest {

    record Ping(int n) {
    }

    @Test
    void theBusDeliversToEveryHandlerOnItsChannelInOrder() {
        LocalClusterBus bus = new LocalClusterBus();
        List<String> heard = new ArrayList<>();
        bus.subscribe("a", Ping.class, p -> heard.add("first " + p.n()));
        bus.subscribe("a", Ping.class, p -> heard.add("second " + p.n()));
        bus.subscribe("b", Ping.class, p -> heard.add("other " + p.n()));

        bus.publish("a", new Ping(1));
        bus.publish("a", new Ping(2));
        bus.publish("nobody-listens", new Ping(3));

        assertThat(heard).containsExactly("first 1", "second 1", "first 2", "second 2");
    }

    @Test
    void oneFailingHandlerDoesntStopTheOthersOrThePublisher() {
        LocalClusterBus bus = new LocalClusterBus();
        List<Integer> heard = new ArrayList<>();
        bus.subscribe("a", Ping.class, p -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe("a", Ping.class, p -> heard.add(p.n()));

        bus.publish("a", new Ping(7));

        assertThat(heard).containsExactly(7);
    }

    @Test
    void broadcastsGoStraightToThisCopysBroker() {
        SimpMessageSendingOperations broker = mock(SimpMessageSendingOperations.class);

        new LocalRealtimeBroadcast(broker).send("/topic/x", "hello");

        verify(broker).convertAndSend("/topic/x", (Object) "hello");
    }

    @Test
    void rateLimitsAreInMemorySlidingWindows() {
        RateLimiter limiter = new LocalRateLimiters(clockAt(MONDAY_MIDNIGHT)).limiter("x", 2, Duration.ofSeconds(1));
        UUID ada = UUID.randomUUID();

        assertThat(limiter.tryAcquire(ada)).isTrue();
        assertThat(limiter.tryAcquire(ada)).isTrue();
        assertThat(limiter.tryAcquire(ada)).isFalse();
        assertThat(limiter.tryAcquire(UUID.randomUUID())).isTrue();
    }

    @Test
    void presenceIsThisCopysConnectionsAndEveryChangeIsWorthTelling() {
        PresenceTracker tracker = new PresenceTracker();
        LocalRoomPresence presence = new LocalRoomPresence(tracker);
        UUID room = UUID.randomUUID();
        OnlineViewer bisi = new OnlineViewer(UUID.randomUUID(), "Bisi", true);

        tracker.subscribed("conn", "sub", room, bisi);

        assertThat(presence.online(room)).containsExactly(bisi);
        assertThat(presence.onlineCount(room)).isEqualTo(1);
        assertThat(presence.localChanged(room)).isTrue();
        assertThat(tracker.rooms()).containsExactly(room);
    }

    @Test
    void settingsDefaultToOneCopyWithAUniqueName() {
        ClusterProperties defaults = new ClusterProperties(null, " ", null);
        ClusterProperties another = new ClusterProperties(null, null, null);

        assertThat(defaults.mode()).isEqualTo(ClusterProperties.Mode.LOCAL);
        assertThat(defaults.presenceTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.nodeId()).isNotBlank().isNotEqualTo(another.nodeId());
        assertThat(new ClusterProperties(ClusterProperties.Mode.REDIS, " app-2 ", Duration.ofSeconds(9)).nodeId())
            .isEqualTo("app-2");
    }
}

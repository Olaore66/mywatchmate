package com.mywatchmate.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.live.dto.OnlineViewer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PresenceTrackerTest {

    private final PresenceTracker presence = new PresenceTracker();
    private final UUID room = UUID.randomUUID();
    private final UUID otherRoom = UUID.randomUUID();
    private final OnlineViewer ada = new OnlineViewer(UUID.randomUUID(), "Ada", false);
    private final OnlineViewer bisi = new OnlineViewer(UUID.randomUUID(), "Bisi", true);

    @Test
    void subscribingToARoomPutsYouOnlineThere() {
        assertThat(presence.subscribed("conn-1", "sub-1", room, ada)).isTrue();

        assertThat(presence.online(room)).containsExactly(ada);
        assertThat(presence.onlineCount(room)).isEqualTo(1);
        assertThat(presence.online(otherRoom)).isEmpty();
        assertThat(presence.onlineCount(otherRoom)).isZero();
    }

    @Test
    void peopleAreListedInTheOrderTheyCameOnline() {
        presence.subscribed("conn-2", "sub-1", room, bisi);
        presence.subscribed("conn-1", "sub-1", room, ada);

        assertThat(presence.online(room)).containsExactly(bisi, ada);
    }

    @Test
    void twoTabsCountOnceAndClosingOneKeepsYouOnline() {
        assertThat(presence.subscribed("phone", "sub-1", room, bisi)).isTrue();
        assertThat(presence.subscribed("laptop", "sub-1", room, bisi)).as("already online").isFalse();
        assertThat(presence.subscribed("laptop", "sub-2", room, bisi)).as("a second topic in the same room").isFalse();
        assertThat(presence.onlineCount(room)).isEqualTo(1);

        assertThat(presence.disconnected("phone")).isEmpty();
        assertThat(presence.unsubscribed("laptop", "sub-1")).isNull();
        assertThat(presence.online(room)).containsExactly(bisi);

        assertThat(presence.unsubscribed("laptop", "sub-2")).isEqualTo(room);
        assertThat(presence.online(room)).isEmpty();
    }

    @Test
    void disconnectingTakesYouOfflineEverywhereYouWere() {
        presence.subscribed("conn-1", "sub-1", room, ada);
        presence.subscribed("conn-1", "sub-2", otherRoom, ada);
        presence.subscribed("conn-2", "sub-1", room, bisi);

        assertThat(presence.disconnected("conn-1")).containsExactlyInAnyOrder(room, otherRoom);

        assertThat(presence.online(room)).containsExactly(bisi);
        assertThat(presence.online(otherRoom)).isEmpty();
    }

    @Test
    void unknownConnectionsAndSubscriptionsAreHarmless() {
        assertThat(presence.unsubscribed("nobody", "sub-1")).isNull();
        assertThat(presence.disconnected("nobody")).isEmpty();
        presence.subscribed("conn-1", "sub-1", room, ada);
        assertThat(presence.unsubscribed("conn-1", "sub-9")).isNull();
        assertThat(presence.online(room)).containsExactly(ada);
    }

    @Test
    void findsTheConnectionsSomeoneHasOnARoom() {
        presence.subscribed("phone", "sub-1", room, bisi);
        presence.subscribed("laptop", "sub-1", room, bisi);
        presence.subscribed("laptop", "sub-2", otherRoom, ada);
        presence.subscribed("tablet", "sub-1", otherRoom, bisi);

        assertThat(presence.connectionsOf(room, bisi.participantId())).containsExactlyInAnyOrder("phone", "laptop");
        assertThat(presence.connectionsOf(room, ada.participantId())).isEmpty();
    }

    @Test
    void theListIsACopy() {
        presence.subscribed("conn-1", "sub-1", room, ada);
        var snapshot = presence.online(room);
        presence.subscribed("conn-2", "sub-1", room, bisi);

        assertThat(snapshot).containsExactly(ada);
    }
}

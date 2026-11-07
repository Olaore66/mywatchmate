package com.mywatchmate.live;

import com.mywatchmate.live.dto.OnlineViewer;
import java.util.List;
import java.util.UUID;

/** One copy of the app: its own connections are everyone's. */
public class LocalRoomPresence implements RoomPresence {

    private final PresenceTracker tracker;

    public LocalRoomPresence(PresenceTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public List<OnlineViewer> online(UUID spaceId) {
        return tracker.online(spaceId);
    }

    @Override
    public int onlineCount(UUID spaceId) {
        return tracker.onlineCount(spaceId);
    }

    @Override
    public boolean localChanged(UUID spaceId) {
        return true;
    }
}

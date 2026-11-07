package com.mywatchmate.live;

import com.mywatchmate.live.dto.OnlineViewer;
import java.util.List;
import java.util.UUID;

/**
 * Who has a room open, across every copy of the app. Each copy tracks its own connections exactly
 * ({@link PresenceTracker}); this adds them up. LOCAL mode: there's only this copy. REDIS mode: each copy
 * keeps its list for each room in Redis, refreshed while it's alive, so a copy that dies drops off by itself.
 */
public interface RoomPresence {

    /** Everyone online in the room, each once however many devices or copies they're connected through. */
    List<OnlineViewer> online(UUID spaceId);

    int onlineCount(UUID spaceId);

    /**
     * This copy's own list for the room just changed (someone connected or left here).
     *
     * @return true if the room's overall list changed, i.e. it's worth telling the room
     */
    boolean localChanged(UUID spaceId);
}

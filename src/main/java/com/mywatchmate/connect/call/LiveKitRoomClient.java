package com.mywatchmate.connect.call;

import com.mywatchmate.connect.call.dto.CallParticipant;
import java.util.List;

/** The parts of LiveKit's server API we use. */
public interface LiveKitRoomClient {

    /** Everyone currently in the room's call (empty when the room doesn't exist yet). */
    List<CallParticipant> listParticipants(String room);

    /** Disconnects someone from the call; nothing happens if they aren't in it. */
    void removeParticipant(String room, String identity);
}

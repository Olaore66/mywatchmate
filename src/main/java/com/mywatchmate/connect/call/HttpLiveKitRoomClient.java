package com.mywatchmate.connect.call;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.connect.call.dto.CallParticipant;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * LiveKit's server API (Twirp: JSON POSTs to /twirp/livekit.RoomService/…), authenticated with a short
 * admin token for the one room concerned.
 */
@Slf4j
@Component
public class HttpLiveKitRoomClient implements LiveKitRoomClient {

    static final String SERVICE = "/twirp/livekit.RoomService/";

    private final LiveKitProperties props;
    private final LiveKitTokens tokens;
    private final Clock clock;
    private final RestClient http;

    @Autowired
    public HttpLiveKitRoomClient(LiveKitProperties props, LiveKitTokens tokens, Clock clock) {
        this(props, tokens, clock, defaultRestClient());
    }

    HttpLiveKitRoomClient(LiveKitProperties props, LiveKitTokens tokens, Clock clock, RestClient http) {
        this.props = props;
        this.tokens = tokens;
        this.clock = clock;
        this.http = http;
    }

    @Override
    public List<CallParticipant> listParticipants(String room) {
        Map<?, ?> body;
        try {
            body = call("ListParticipants", room, Map.of("room", room));
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        }
        List<CallParticipant> found = new ArrayList<>();
        if (body != null && body.get("participants") instanceof List<?> participants) {
            for (Object p : participants) {
                if (p instanceof Map<?, ?> participant) {
                    toParticipant(participant).ifPresent(found::add);
                }
            }
        }
        return found;
    }

    @Override
    public void removeParticipant(String room, String identity) {
        try {
            call("RemoveParticipant", room, Map.of("room", room, "identity", identity));
        } catch (HttpClientErrorException.NotFound e) {
            // Not in the call: nothing to do
        }
    }

    private Map<?, ?> call(String method, String room, Map<String, Object> request) {
        try {
            return http.post()
                .uri(props.httpUrl() + SERVICE + method)
                .header("Authorization", "Bearer " + tokens.adminToken(room, clock.instant()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Map.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("LiveKit {} for {} failed: {}", method, room, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "CALL_SERVICE_UNAVAILABLE",
                "The call service isn't answering right now. Try again in a moment.");
        }
    }

    /** One participant from LiveKit's JSON; people whose identity isn't one of our participant ids are left out. */
    static Optional<CallParticipant> toParticipant(Map<?, ?> p) {
        UUID id;
        try {
            id = UUID.fromString(String.valueOf(p.get("identity")));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        boolean camera = false;
        boolean microphone = false;
        boolean screen = false;
        if (p.get("tracks") instanceof List<?> tracks) {
            for (Object t : tracks) {
                if (!(t instanceof Map<?, ?> track) || Boolean.TRUE.equals(track.get("muted"))) {
                    continue;
                }
                String source = enumName(track.get("source"), List.of("UNKNOWN", "CAMERA", "MICROPHONE", "SCREEN_SHARE",
                    "SCREEN_SHARE_AUDIO"));
                // AUDIO is the default (0), so LiveKit may leave "type" out for audio tracks
                String type = enumName(track.get("type"), List.of("AUDIO", "VIDEO", "DATA"));
                switch (source) {
                    case "CAMERA" -> camera = true;
                    case "MICROPHONE" -> microphone = true;
                    case "SCREEN_SHARE", "SCREEN_SHARE_AUDIO" -> screen = true;
                    default -> {
                        if ("VIDEO".equals(type)) {
                            camera = true;
                        } else if ("AUDIO".equals(type)) {
                            microphone = true;
                        }
                    }
                }
            }
        }
        Object name = p.get("name");
        return Optional.of(new CallParticipant(id, name == null ? null : name.toString(), joinedAt(p),
            camera, microphone, screen));
    }

    /** Enums arrive as names ("CAMERA") or numbers (1); missing means the first value. */
    private static String enumName(Object value, List<String> names) {
        if (value == null) {
            return names.getFirst();
        }
        if (value instanceof Number n && n.intValue() >= 0 && n.intValue() < names.size()) {
            return names.get(n.intValue());
        }
        return value.toString().toUpperCase(Locale.ROOT);
    }

    /** Unix seconds, as a number or a string ("joinedAt" or "joined_at"). */
    private static Instant joinedAt(Map<?, ?> p) {
        Object value = p.get("joinedAt") != null ? p.get("joinedAt") : p.get("joined_at");
        if (value == null) {
            return null;
        }
        try {
            long seconds = value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
            return seconds > 0 ? Instant.ofEpochSecond(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static RestClient defaultRestClient() {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }
}

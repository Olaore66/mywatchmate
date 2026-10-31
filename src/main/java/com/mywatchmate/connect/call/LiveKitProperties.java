package com.mywatchmate.connect.call;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LiveKit (the video call service). With no URL, key or secret, calls are switched off (503).
 *
 * @param url       the project's wss://….livekit.cloud address (apps connect there directly)
 * @param apiKey    identifies our backend to LiveKit
 * @param apiSecret signs join tokens and LiveKit's webhooks; never leaves the backend
 * @param tokenTtl  how long a join token can be used to connect
 */
@ConfigurationProperties("mywatchmate.livekit")
public record LiveKitProperties(String url, String apiKey, String apiSecret, Duration tokenTtl) {

    public boolean configured() {
        return url != null && !url.isBlank() && apiKey != null && !apiKey.isBlank()
            && apiSecret != null && !apiSecret.isBlank();
    }

    /** The same server over https, for LiveKit's server API (wss://x → https://x). */
    public String httpUrl() {
        String base = url.strip().replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://");
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}

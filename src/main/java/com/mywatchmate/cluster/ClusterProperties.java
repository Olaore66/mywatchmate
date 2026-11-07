package com.mywatchmate.cluster;

import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How this copy of the app shares state with other copies.
 *
 * @param mode        LOCAL (default): one copy; live events, presence and rate limits stay in memory, nothing
 *                    else to run. REDIS: any number of copies share them through Redis. The frontend sees no
 *                    difference: same WebSocket topics, same messages, same endpoints.
 * @param nodeId      this copy's name in Redis; blank picks hostname + random suffix (unique per start)
 * @param presenceTtl REDIS mode: a copy that stops refreshing (crashed, cut off) drops off the online lists
 *                    after this
 */
@ConfigurationProperties("mywatchmate.cluster")
public record ClusterProperties(Mode mode, String nodeId, Duration presenceTtl) {

    public enum Mode { LOCAL, REDIS }

    public ClusterProperties {
        mode = mode == null ? Mode.LOCAL : mode;
        nodeId = nodeId == null || nodeId.isBlank() ? defaultNodeId() : nodeId.strip();
        presenceTtl = presenceTtl == null ? Duration.ofSeconds(30) : presenceTtl;
    }

    private static String defaultNodeId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "node";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}

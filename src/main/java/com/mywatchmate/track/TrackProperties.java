package com.mywatchmate.track;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param countedShare share of a session someone must watch for it to count (0.5 = half)...
 * @param countedCap   ...but never more than this, so long movies need 20 minutes, not an hour
 * @param tickCap      most watch time credited between two checks; a server pause isn't watching
 */
@ConfigurationProperties("mywatchmate.track")
public record TrackProperties(double countedShare, Duration countedCap, Duration tickCap) {

    /** How long someone must have watched a session that ran for {@code length} for it to count. */
    public int requiredSeconds(Duration length) {
        long share = (long) (length.toSeconds() * countedShare);
        return (int) Math.min(share, countedCap.toSeconds());
    }
}

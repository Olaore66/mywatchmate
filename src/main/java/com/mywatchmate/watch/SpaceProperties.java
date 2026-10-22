package com.mywatchmate.watch;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxMembers  how many people (users + guests) can be in one room at a time
 * @param frontendUrl where invite links point, e.g. https://app.mywatchmate.com → /join/{token}
 */
@ConfigurationProperties("mywatchmate.spaces")
public record SpaceProperties(int maxMembers, String frontendUrl) {

    public SpaceProperties {
        frontendUrl = frontendUrl == null ? "" : frontendUrl.replaceAll("/+$", "");
    }
}

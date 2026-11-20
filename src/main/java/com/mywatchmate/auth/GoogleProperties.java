package com.mywatchmate.auth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param clientIds our OAuth client ids from Google Cloud (web now; iOS and Android later). A Google ID token is
 *                  only accepted if it was issued to one of them. Empty: Google sign-in is off.
 */
@ConfigurationProperties("mywatchmate.google")
public record GoogleProperties(List<String> clientIds) {

    public GoogleProperties {
        clientIds = clientIds == null ? List.of()
            : clientIds.stream().map(String::strip).filter(id -> !id.isEmpty()).toList();
    }

    public boolean enabled() {
        return !clientIds.isEmpty();
    }
}

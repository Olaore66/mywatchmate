package com.mywatchmate.give;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiBase    Payaza's API (one address for test and live; the key and X-TenantID pick which)
 * @param timeout    how long to wait for Payaza when confirming a payment
 * @param verifyFor  how long after opening we keep checking an unfinished in-room payment with Payaza
 */
@ConfigurationProperties("mywatchmate.payaza")
public record PayazaProperties(String apiBase, Duration timeout, Duration verifyFor) {

    public PayazaProperties {
        apiBase = apiBase == null || apiBase.isBlank() ? "https://api.payaza.africa/live" : apiBase.replaceAll("/+$", "");
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        verifyFor = verifyFor == null ? Duration.ofHours(2) : verifyFor;
    }
}

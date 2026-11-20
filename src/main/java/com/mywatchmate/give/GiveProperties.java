package com.mywatchmate.give;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param payazaLinks     the shapes Payaza payment links come in, each "https://host/path/" + one slug, e.g.
 *                        https://business.payaza.africa/pay/ (from Payaza's docs); a link must match one exactly
 * @param repromptWindow  the AI seeing the same link again within this, in the same session, doesn't re-prompt
 * @param promptLifetime  how long a Give prompt stays up unless closed
 * @param reportThreshold "looks like a scam" reports from different people that block a link everywhere
 * @param giftExpiry      an opened gift with no result reported after this becomes UNKNOWN
 * @param internalToken   what the AI worker sends as X-Service-Token; empty turns /internal off
 */
@ConfigurationProperties("mywatchmate.give")
public record GiveProperties(List<String> payazaLinks, Duration repromptWindow, Duration promptLifetime,
                             int reportThreshold, Duration giftExpiry, String internalToken) {
}

package com.mywatchmate.abuse;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits on the public endpoints that bots go for (sign-in, sign-up, guest join, emails, the WebSocket
 * handshake). Settings, so they can be tuned without a release.
 *
 * @param enabled off in tests that sign up many users from one address
 * @param rules   per-IP limits by endpoint
 * @param login   failed sign-ins: per account and IP, and per account from anywhere
 */
@ConfigurationProperties("mywatchmate.abuse")
public record AbuseProperties(boolean enabled, List<Rule> rules, Login login) {

    public AbuseProperties {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * @param name   names the limit (in logs and Redis keys)
     * @param method HTTP method, or null for any
     * @param paths  path patterns, e.g. {@code /api/v1/auth/login}
     * @param limits all must allow the request, e.g. 5 an hour and 20 a day
     */
    public record Rule(String name, String method, List<String> paths, List<Limit> limits) {

        public Rule {
            paths = paths == null ? List.of() : List.copyOf(paths);
            limits = limits == null ? List.of() : List.copyOf(limits);
        }
    }

    public record Limit(int max, Duration window) {
    }

    /**
     * @param perAccountAndIp failed sign-ins for one account from one address: stops one attacker quickly; a
     *                        success from that address clears it
     * @param perAccount      failed sign-ins for one account from any address: slows attackers who switch
     *                        addresses, without letting anyone lock the owner out for long
     */
    public record Login(Limit perAccountAndIp, Limit perAccount) {
    }
}

package com.mywatchmate.abuse;

import com.mywatchmate.common.FailureCounter;
import com.mywatchmate.common.FailureCounters;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Failed sign-ins. Checked before the password is (so a blocked attacker learns nothing and costs no hashing):
 * <ul>
 *   <li>per account and IP: a few wrong passwords from one address block that address for that account;
 *       signing in successfully from there clears it;</li>
 *   <li>per account from anywhere: a higher limit that slows attackers who keep changing address. No hard
 *       lockout: it frees itself as old failures leave the window, so nobody can lock the owner out for long.</li>
 * </ul>
 * Unknown emails count the same as known ones, so blocking doesn't reveal who has an account. Emails are
 * only kept hashed, never as typed.
 */
@Slf4j
@Component
public class LoginAttempts {

    private final boolean enabled;
    private final FailureCounter perAccountAndIp;
    private final FailureCounter perAccount;

    public LoginAttempts(AbuseProperties props, FailureCounters counters) {
        this.enabled = props.enabled() && props.login() != null;
        AbuseProperties.Login login = props.login() != null ? props.login()
            : new AbuseProperties.Login(new AbuseProperties.Limit(5, Duration.ofMinutes(15)),
                new AbuseProperties.Limit(20, Duration.ofHours(1)));
        this.perAccountAndIp = counters.counter("login-account-ip", login.perAccountAndIp().max(), login.perAccountAndIp().window());
        this.perAccount = counters.counter("login-account", login.perAccount().max(), login.perAccount().window());
    }

    /** Throws 429 TOO_MANY_ATTEMPTS if this account is blocked for this address (or everywhere). */
    public void check(String email, String ip) {
        if (!enabled) {
            return;
        }
        String account = account(email);
        Duration here = perAccountAndIp.blockedFor(account + "|" + ip);
        Duration anywhere = perAccount.blockedFor(account);
        Duration wait = here.compareTo(anywhere) >= 0 ? here : anywhere;
        if (!wait.isZero()) {
            log.info("Sign-in blocked for account {} from {} ({})", account.substring(0, 12), ip,
                here.isZero() ? "too many failures from many places" : "too many failures from here");
            throw new TooManyAttempts(wait);
        }
    }

    public void failed(String email, String ip) {
        if (!enabled) {
            return;
        }
        String account = account(email);
        perAccountAndIp.failed(account + "|" + ip);
        perAccount.failed(account);
    }

    public void succeeded(String email, String ip) {
        if (enabled) {
            perAccountAndIp.cleared(account(email) + "|" + ip);
        }
    }

    /** SHA-256 of the email as the app stores it (trimmed, lower case). */
    static String account(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

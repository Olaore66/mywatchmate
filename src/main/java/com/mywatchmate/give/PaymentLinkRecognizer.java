package com.mywatchmate.give;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Decides whether a decoded QR code (or pasted link) is a Payaza payment link we're willing to show, and
 * writes it one canonical way so the same link is always recognised as the same. Deliberately strict:
 * a wrong "yes" puts a scammer's link in front of a whole congregation.
 */
@Component
@RequiredArgsConstructor
public class PaymentLinkRecognizer {

    static final int MAX_LENGTH = 2048;
    private static final Set<String> TRACKING_PARAMS = Set.of("fbclid", "gclid", "igshid", "mc_cid", "mc_eid", "ref", "si");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final GiveProperties props;

    /** @param reason why it isn't acceptable (null when it is) */
    public record Result(String normalizedUrl, PaymentLink.Provider provider, String reason) {

        static Result accepted(String url) {
            return new Result(url, PaymentLink.Provider.PAYAZA, null);
        }

        static Result rejected(String reason) {
            return new Result(null, null, reason);
        }

        public boolean ok() {
            return reason == null;
        }
    }

    public Result recognise(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.rejected("EMPTY");
        }
        String value = raw.strip();
        if (value.length() > MAX_LENGTH) {
            return Result.rejected("TOO_LONG");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return Result.rejected("NOT_A_LINK");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return Result.rejected("NOT_HTTPS");
        }
        // Non-ASCII (look-alike) hostnames don't parse into a host; nor do some odd forms
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null) {
            return Result.rejected("BAD_HOST");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.startsWith("[") || IPV4.matcher(host).matches() || host.startsWith("xn--") || host.contains(".xn--")) {
            return Result.rejected("BAD_HOST");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return Result.rejected("BAD_PORT");
        }
        if (!isPayazaHost(host)) {
            return Result.rejected("NOT_PAYAZA");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return Result.rejected("NOT_A_PAYMENT_LINK");
        }
        String query = cleanQuery(uri.getRawQuery());
        return Result.accepted("https://" + host + path + (query.isEmpty() ? "" : "?" + query));
    }

    private boolean isPayazaHost(String host) {
        for (String allowed : props.payazaHosts()) {
            String domain = allowed.toLowerCase(Locale.ROOT).strip();
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    /** Keeps the link's own parameters (it may need them), drops tracking ones. */
    private static String cleanQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String name = pair.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (!name.startsWith("utm_") && !TRACKING_PARAMS.contains(name)) {
                kept.add(pair);
            }
        }
        return String.join("&", kept);
    }
}

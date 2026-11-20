package com.mywatchmate.give;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Decides whether a decoded QR code (or pasted link) is a Payaza payment link we're willing to show, and
 * writes it one canonical way so the same link is always recognised as the same. Deliberately strict:
 * a wrong "yes" puts a scammer's link in front of a whole congregation.
 */
@Component
public class PaymentLinkRecognizer {

    static final int MAX_LENGTH = 2048;
    private static final Set<String> TRACKING_PARAMS = Set.of("fbclid", "gclid", "igshid", "mc_cid", "mc_eid", "ref", "si");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    // A payment link's own name: one path segment, plain characters only
    private static final Pattern SLUG = Pattern.compile("^[A-Za-z0-9._~-]{1,200}$");

    /** "https://business.payaza.africa/pay/" as host + path. */
    private record Shape(String host, String pathPrefix) {
    }

    private final List<Shape> shapes;

    public PaymentLinkRecognizer(GiveProperties props) {
        this.shapes = props.payazaLinks().stream()
            .map(String::strip)
            .filter(link -> !link.isEmpty())
            .map(PaymentLinkRecognizer::shapeOf)
            .toList();
    }

    private static Shape shapeOf(String link) {
        URI uri = URI.create(link);
        String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
        return new Shape(uri.getHost().toLowerCase(Locale.ROOT), path.endsWith("/") ? path : path + "/");
    }

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
        String finalHost = host;
        List<Shape> onThisHost = shapes.stream().filter(shape -> shape.host().equals(finalHost)).toList();
        if (onThisHost.isEmpty()) {
            return Result.rejected("NOT_PAYAZA");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String linkPath = path;
        if (onThisHost.stream().noneMatch(shape -> isLinkShape(shape, linkPath))) {
            return Result.rejected("NOT_A_PAYMENT_LINK");
        }
        String query = cleanQuery(uri.getRawQuery());
        return Result.accepted("https://" + host + path + (query.isEmpty() ? "" : "?" + query));
    }

    /** The path is the shape's prefix followed by exactly one slug. */
    private static boolean isLinkShape(Shape shape, String path) {
        return path.startsWith(shape.pathPrefix()) && SLUG.matcher(path.substring(shape.pathPrefix().length())).matches();
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

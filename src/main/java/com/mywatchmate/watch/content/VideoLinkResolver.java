package com.mywatchmate.watch.content;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.watch.ContentItem;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Works out what a pasted link is without fetching it: YouTube, Vimeo, or a direct video file.
 * Anything else (Facebook, TikTok, web pages…) is rejected because its player can't be
 * controlled from code, so a room couldn't stay in sync.
 */
@Component
public class VideoLinkResolver {

    static final String UNSUPPORTED_MESSAGE =
        "We can only play YouTube and Vimeo links, or direct video files (.mp4, .webm, .m3u8).";

    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern VIMEO_HASH = Pattern.compile("[0-9a-f]{6,}");
    private static final List<String> FILE_EXTENSIONS = List.of(".mp4", ".webm", ".m4v", ".ogv", ".m3u8");

    public ResolvedLink resolve(String raw) {
        URI uri = parse(raw);
        String host = stripPrefixes(uri.getHost().toLowerCase(Locale.ROOT));
        List<String> segments = segments(uri.getRawPath());

        return switch (host) {
            case "youtube.com", "youtube-nocookie.com" -> youtube(youtubeIdFromPath(segments, query(uri)), isLivePath(segments));
            case "youtu.be" -> youtube(segments.isEmpty() ? null : segments.get(0), false);
            case "vimeo.com" -> vimeoFromSite(segments);
            case "player.vimeo.com" -> vimeoFromPlayer(segments, query(uri));
            default -> directFile(uri);
        };
    }

    // ---- YouTube ---------------------------------------------------------------

    private static String youtubeIdFromPath(List<String> segments, Map<String, String> query) {
        if (segments.size() == 1 && segments.get(0).equals("watch")) {
            return query.get("v");
        }
        if (segments.size() >= 2 && List.of("shorts", "live", "embed", "v").contains(segments.get(0))) {
            return segments.get(1);
        }
        return null;
    }

    private static boolean isLivePath(List<String> segments) {
        return !segments.isEmpty() && segments.get(0).equals("live");
    }

    private static ResolvedLink youtube(String id, boolean live) {
        if (id == null || !YOUTUBE_ID.matcher(id).matches()) {
            throw unsupported("That YouTube link doesn't point to a video.");
        }
        return new ResolvedLink(ContentItem.Source.YOUTUBE, "https://www.youtube.com/watch?v=" + id, id, live);
    }

    // ---- Vimeo -----------------------------------------------------------------

    /** vimeo.com/123, vimeo.com/123/abcdef12 (unlisted), vimeo.com/channels/x/123, .../videos/123 */
    private static ResolvedLink vimeoFromSite(List<String> segments) {
        for (int i = 0; i < segments.size(); i++) {
            if (DIGITS.matcher(segments.get(i)).matches()) {
                String hash = i == 0 && segments.size() > 1 && VIMEO_HASH.matcher(segments.get(1)).matches()
                    ? segments.get(1) : null;
                return vimeo(segments.get(i), hash);
            }
        }
        throw unsupported("That Vimeo link doesn't point to a video.");
    }

    /** player.vimeo.com/video/123?h=abcdef12 */
    private static ResolvedLink vimeoFromPlayer(List<String> segments, Map<String, String> query) {
        if (segments.size() >= 2 && segments.get(0).equals("video") && DIGITS.matcher(segments.get(1)).matches()) {
            String hash = query.get("h");
            return vimeo(segments.get(1), hash != null && VIMEO_HASH.matcher(hash).matches() ? hash : null);
        }
        throw unsupported("That Vimeo link doesn't point to a video.");
    }

    private static ResolvedLink vimeo(String id, String unlistedHash) {
        String url = "https://vimeo.com/" + id + (unlistedHash == null ? "" : "/" + unlistedHash);
        return new ResolvedLink(ContentItem.Source.VIMEO, url, id, false);
    }

    // ---- Direct files ----------------------------------------------------------

    private static ResolvedLink directFile(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (FILE_EXTENSIONS.stream().noneMatch(path::endsWith)) {
            throw unsupported(UNSUPPORTED_MESSAGE);
        }
        if (!"https".equals(uri.getScheme())) {
            // Browsers block http video inside an https app
            throw unsupported("Direct video links must start with https://");
        }
        return new ResolvedLink(ContentItem.Source.URL, uri.normalize().toString(), null, false);
    }

    // ---- Parsing ---------------------------------------------------------------

    private static URI parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw unsupported(UNSUPPORTED_MESSAGE);
        }
        String text = raw.trim();
        if (!text.contains("://")) {
            text = "https://" + text; // people often paste "youtu.be/abc"
        }
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean web = scheme.equals("http") || scheme.equals("https");
            if (!web || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw unsupported(UNSUPPORTED_MESSAGE);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw unsupported("That doesn't look like a link.");
        }
    }

    private static String stripPrefixes(String host) {
        for (String prefix : List.of("www.", "m.", "music.")) {
            if (host.startsWith(prefix)) {
                return host.substring(prefix.length());
            }
        }
        return host;
    }

    private static List<String> segments(String rawPath) {
        if (rawPath == null) {
            return List.of();
        }
        return Arrays.stream(rawPath.split("/")).filter(s -> !s.isEmpty()).toList();
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> params = new HashMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    params.putIfAbsent(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
                }
            }
        }
        return params;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static ApiException unsupported(String message) {
        return ApiException.unprocessable("UNSUPPORTED_LINK", message);
    }
}

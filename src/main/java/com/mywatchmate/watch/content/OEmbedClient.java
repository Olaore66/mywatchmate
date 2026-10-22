package com.mywatchmate.watch.content;

import com.mywatchmate.common.ApiException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fetches video titles and thumbnails from YouTube's and Vimeo's public oEmbed endpoints.
 * We only ever call these two fixed addresses, never the link a user pasted (that would let
 * someone make our server fetch internal addresses: SSRF). Redirects are not followed.
 */
@Slf4j
@Component
public class OEmbedClient implements VideoMetadataClient {

    static final String YOUTUBE_ENDPOINT = "https://www.youtube.com/oembed?format=json&url={url}";
    static final String VIMEO_ENDPOINT = "https://vimeo.com/api/oembed.json?url={url}";

    private final RestClient http;

    @Autowired
    public OEmbedClient() {
        this(defaultRestClient());
    }

    OEmbedClient(RestClient http) {
        this.http = http;
    }

    @Override
    public VideoMetadata fetch(ResolvedLink link) {
        return switch (link.source()) {
            case YOUTUBE -> lookup(YOUTUBE_ENDPOINT, link);
            case VIMEO -> lookup(VIMEO_ENDPOINT, link);
            default -> new VideoMetadata(fileTitle(link.canonicalUrl()), null, null);
        };
    }

    private VideoMetadata lookup(String endpoint, ResolvedLink link) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = http.get().uri(endpoint, link.canonicalUrl()).retrieve().body(Map.class);
            if (body == null) {
                return VideoMetadata.UNKNOWN;
            }
            return new VideoMetadata(text(body.get("title")), text(body.get("thumbnail_url")), seconds(body.get("duration")));
        } catch (HttpClientErrorException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status == HttpStatus.NOT_FOUND || status == HttpStatus.BAD_REQUEST) {
                throw ApiException.unprocessable("VIDEO_NOT_FOUND", "We couldn't find that video. It may be private or deleted.");
            }
            if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
                throw ApiException.unprocessable("VIDEO_NOT_EMBEDDABLE",
                    "This video's owner doesn't allow it to be played outside their site.");
            }
            log.warn("oEmbed lookup for {} failed with {}", link.canonicalUrl(), e.getStatusCode());
            return VideoMetadata.UNKNOWN;
        } catch (RestClientException e) {
            // Provider slow or down: still let people add the video, just without a title yet
            log.warn("oEmbed lookup for {} failed: {}", link.canonicalUrl(), e.getMessage());
            return VideoMetadata.UNKNOWN;
        }
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static Integer seconds(Object value) {
        return value instanceof Number n && n.intValue() > 0 ? n.intValue() : null;
    }

    /** "https://cdn.example.com/videos/Day%2012.mp4" → "Day 12" */
    static String fileTitle(String url) {
        String path = URI.create(url).getRawPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        String name = URLDecoder.decode(dot > 0 ? last.substring(0, dot) : last, StandardCharsets.UTF_8).trim();
        return name.isEmpty() ? null : name;
    }

    private static RestClient defaultRestClient() {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(4));
        return RestClient.builder().requestFactory(factory).build();
    }
}

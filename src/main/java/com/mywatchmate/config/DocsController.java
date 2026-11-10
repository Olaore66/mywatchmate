package com.mywatchmate.config;

import io.swagger.v3.oas.annotations.Hidden;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The WebSocket events guide (docs/realtime-events.md, packaged into the jar at build time), served next to
 * Swagger: Swagger can't describe WebSocket traffic, and this way the frontend always reads the version that's
 * actually deployed. Public, like Swagger.
 */
@Hidden
@RestController
public class DocsController {

    public static final String PATH = "/docs/realtime-events.md";
    public static final String SYNC_PATH = "/docs/watch-sync.js";
    static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);
    static final MediaType JAVASCRIPT = new MediaType("text", "javascript", StandardCharsets.UTF_8);

    private final String guide;
    private final String syncClient;

    public DocsController() throws IOException {
        this.guide = read("docs/realtime-events.md", "# Real-time events\n\nThis build doesn't include the guide.\n");
        this.syncClient = read("docs/watch-sync.js", "// This build doesn't include the sync client.\n");
    }

    private static String read(String path, String missing) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        return resource.exists() ? resource.getContentAsString(StandardCharsets.UTF_8) : missing;
    }

    @GetMapping(PATH)
    public ResponseEntity<String> realtimeEvents() {
        return ResponseEntity.ok().contentType(MARKDOWN).cacheControl(CacheControl.noCache()).body(guide);
    }

    /** The reference player-sync code the guide describes. */
    @GetMapping(SYNC_PATH)
    public ResponseEntity<String> syncClient() {
        return ResponseEntity.ok().contentType(JAVASCRIPT).cacheControl(CacheControl.noCache()).body(syncClient);
    }
}

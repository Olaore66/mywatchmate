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
    static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final String guide;

    public DocsController() throws IOException {
        this(new ClassPathResource("docs/realtime-events.md"));
    }

    DocsController(ClassPathResource resource) throws IOException {
        this.guide = resource.exists()
            ? resource.getContentAsString(StandardCharsets.UTF_8)
            : "# Real-time events\n\nThis build doesn't include the guide.\n";
    }

    @GetMapping(PATH)
    public ResponseEntity<String> realtimeEvents() {
        return ResponseEntity.ok().contentType(MARKDOWN).cacheControl(CacheControl.noCache()).body(guide);
    }
}

package com.mywatchmate.connect.call;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where LiveKit reports call activity (set in the LiveKit project: Settings → Webhooks). Not for the apps,
 * so it's left out of Swagger. Authenticated by LiveKit's signature, not a user token.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class LiveKitWebhookController {

    public static final String PATH = "/api/v1/webhooks/livekit";

    private final CallService calls;

    @PostMapping(PATH)
    public void receive(@RequestHeader(value = "Authorization", required = false) String authorization,
                        @RequestBody String body) {
        calls.onWebhook(authorization, body);
    }
}

package com.mywatchmate.watch;

import com.mywatchmate.watch.dto.InviteResponse;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Unguessable invite tokens (96 random bits → 16 URL-safe characters) and the links built from them. */
@Component
@RequiredArgsConstructor
public class InviteTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SpaceProperties props;

    public String newToken() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public InviteResponse inviteFor(Space space) {
        return new InviteResponse(space.getInviteToken(), props.frontendUrl() + "/join/" + space.getInviteToken());
    }
}

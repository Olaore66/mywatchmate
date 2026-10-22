package com.mywatchmate.watch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InviteTokensTest {

    @Test
    void tokensAreShortUrlSafeAndUnique() {
        InviteTokens tokens = new InviteTokens(new SpaceProperties(50, "http://localhost:3000"));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String token = tokens.newToken();
            assertThat(token).hasSize(16).matches("[A-Za-z0-9_-]+");
            seen.add(token);
        }
        assertThat(seen).hasSize(2000);
    }

    @Test
    void linkPointsAtTheFrontendJoinPage() {
        InviteTokens tokens = new InviteTokens(new SpaceProperties(50, "https://app.mywatchmate.com///"));
        Space space = new Space(null, "Room", "k7Pq2rXvB9mT4wZa", Space.AudienceType.FRIENDS);

        assertThat(tokens.inviteFor(space).inviteUrl()).isEqualTo("https://app.mywatchmate.com/join/k7Pq2rXvB9mT4wZa");
        assertThat(tokens.inviteFor(space).inviteToken()).isEqualTo("k7Pq2rXvB9mT4wZa");
    }
}

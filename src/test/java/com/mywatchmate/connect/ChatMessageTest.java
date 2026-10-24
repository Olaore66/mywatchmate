package com.mywatchmate.connect;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.guest;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChatMessageTest {

    private final Space space = space(user("Ada"));
    private final Participant bisi = guest(space, "Bisi");
    private final Participant ada = member(space, space.getOwner(), Participant.Role.OWNER);

    @Test
    void aNewMessageHasItsOwnIdAndATimeTheDatabaseCanStoreExactly() {
        ChatMessage message = new ChatMessage(space, null, bisi, "Amen", Instant.parse("2026-11-02T05:00:00.123456789Z"));

        assertThat(message.getId()).isNotNull();
        assertThat(message.getCreatedAt()).isEqualTo(Instant.parse("2026-11-02T05:00:00.123456Z"));
        assertThat(message.isFrom(bisi)).isTrue();
        assertThat(message.isFrom(ada)).isFalse();
        assertThat(message.isDeleted()).isFalse();
    }

    @Test
    void deletingKeepsTheFirstDeletion() {
        ChatMessage message = new ChatMessage(space, null, bisi, "oops", MONDAY_MIDNIGHT);

        message.delete(MONDAY_MIDNIGHT.plusSeconds(5), ada);
        message.delete(MONDAY_MIDNIGHT.plusSeconds(9), bisi);

        assertThat(message.isDeleted()).isTrue();
        assertThat(message.getDeletedAt()).isEqualTo(MONDAY_MIDNIGHT.plusSeconds(5));
        assertThat(message.getDeletedBy()).isSameAs(ada);
        assertThat(message.getBody()).as("kept for moderation").isEqualTo("oops");
    }
}

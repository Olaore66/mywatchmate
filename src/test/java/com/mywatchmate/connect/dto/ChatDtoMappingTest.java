package com.mywatchmate.connect.dto;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.guest;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.connect.ChatMessage;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.WatchSession;
import org.junit.jupiter.api.Test;

/** What the frontend receives for a chat message. */
class ChatDtoMappingTest {

    private final Space space = space(user("Ada"));
    private final Participant ada = member(space, space.getOwner(), Participant.Role.OWNER);
    private final Participant bisi = guest(space, "Bisi");

    @Test
    void aMessageShowsItsSenderRoomAndSession() {
        WatchSession live = session(space, MONDAY_MIDNIGHT);
        ChatMessage m = new ChatMessage(space, live, bisi, "Amen", MONDAY_MIDNIGHT);

        ChatMessageResponse shown = ChatMessageResponse.from(m);

        assertThat(shown.id()).isEqualTo(m.getId());
        assertThat(shown.spaceId()).isEqualTo(space.getId());
        assertThat(shown.sessionId()).isEqualTo(live.getId());
        assertThat(shown.sender().id()).isEqualTo(bisi.getId());
        assertThat(shown.sender().displayName()).isEqualTo("Bisi");
        assertThat(shown.senderGuest()).isTrue();
        assertThat(shown.body()).isEqualTo("Amen");
        assertThat(shown.deleted()).isFalse();
        assertThat(shown.deletedByHost()).isFalse();
        assertThat(shown.createdAt()).isEqualTo(MONDAY_MIDNIGHT);
    }

    @Test
    void aDeletedMessageHidesItsTextAndSaysWhoRemovedIt() {
        ChatMessage selfDeleted = new ChatMessage(space, null, bisi, "oops", MONDAY_MIDNIGHT);
        selfDeleted.delete(MONDAY_MIDNIGHT, bisi);
        ChatMessage moderated = new ChatMessage(space, null, bisi, "rude", MONDAY_MIDNIGHT);
        moderated.delete(MONDAY_MIDNIGHT, ada);

        assertThat(ChatMessageResponse.from(selfDeleted).body()).isNull();
        assertThat(ChatMessageResponse.from(selfDeleted).deletedByHost()).isFalse();
        assertThat(ChatMessageResponse.from(moderated).deleted()).isTrue();
        assertThat(ChatMessageResponse.from(moderated).deletedByHost()).isTrue();
        assertThat(ChatMessageResponse.from(moderated).sessionId()).isNull();
    }

    @Test
    void aMessageDeletedWithoutARecordOfWhoIsNotCountedAsAHostDeletion() {
        // e.g. removed directly in the database for support reasons
        ChatMessage m = new ChatMessage(space, null, bisi, "old", MONDAY_MIDNIGHT);
        m.setDeletedAt(MONDAY_MIDNIGHT);

        ChatMessageResponse shown = ChatMessageResponse.from(m);

        assertThat(shown.deleted()).isTrue();
        assertThat(shown.body()).isNull();
        assertThat(shown.deletedByHost()).isFalse();
    }
}

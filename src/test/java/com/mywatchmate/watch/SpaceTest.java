package com.mywatchmate.watch;

import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.identity.User;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SpaceTest {

    private final Space space = new Space(new User("ada@example.com", "Ada"), "Room", "old-token", Space.AudienceType.FRIENDS);

    @Test
    void archivingKeepsTheFirstTimeAndRestoringClearsIt() {
        Instant first = Instant.parse("2026-10-22T06:00:00Z");
        space.archive(first);
        space.archive(first.plusSeconds(60));

        assertThat(space.isArchived()).isTrue();
        assertThat(space.getArchivedAt()).isEqualTo(first);

        space.restore();
        assertThat(space.isArchived()).isFalse();
    }

    @Test
    void rotatingReplacesTheInviteToken() {
        space.rotateInvite("new-token");
        assertThat(space.getInviteToken()).isEqualTo("new-token");
    }

    @Test
    void nowPlayingCanBeSetAndStopped() {
        ContentItem video = new ContentItem(space, ContentItem.Source.VIMEO, "https://vimeo.com/1");
        space.playNow(video);
        assertThat(space.getCurrentContent()).isSameAs(video);

        space.stopPlaying();
        assertThat(space.getCurrentContent()).isNull();
    }

    @Test
    void newRoomsAllowGuestsButNotMemberUploads() {
        assertThat(space.isAllowGuests()).isTrue();
        assertThat(space.isMembersCanAddContent()).isFalse();
    }
}

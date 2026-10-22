package com.mywatchmate.watch;

import static com.mywatchmate.watch.WatchFixtures.guest;
import static com.mywatchmate.watch.WatchFixtures.member;
import static com.mywatchmate.watch.WatchFixtures.membership;
import static com.mywatchmate.watch.WatchFixtures.space;
import static com.mywatchmate.watch.WatchFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mywatchmate.identity.User;
import com.mywatchmate.watch.dto.SpaceResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SpaceViewsTest {

    @Mock ParticipantRepository participants;

    private SpaceViews views;
    private User ada;
    private Space space;

    @BeforeEach
    void setUp() {
        views = new SpaceViews(participants, new SpaceProperties(50, "http://localhost:3000"));
        ada = user("Ada");
        space = space(ada);
        space.setDescription("Daily devotion");
        space.setCreatedAt(Instant.parse("2026-10-01T06:00:00Z"));
        when(participants.countBySpaceIdAndLeftAtIsNull(space.getId())).thenReturn(4L);
    }

    @Test
    void ownerSeesTheRoomWithTheirRoleAndWhatsPlaying() {
        Participant owner = member(space, ada, Participant.Role.OWNER);
        ContentItem video = new ContentItem(space, ContentItem.Source.YOUTUBE, "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        video.setId(UUID.randomUUID());
        video.setTitle("Morning Prayer");
        space.playNow(video);

        SpaceResponse response = views.of(membership(owner));

        assertThat(response.id()).isEqualTo(space.getId());
        assertThat(response.title()).isEqualTo("6am Prayer Room");
        assertThat(response.description()).isEqualTo("Daily devotion");
        assertThat(response.owner().id()).isEqualTo(ada.getId());
        assertThat(response.owner().displayName()).isEqualTo("Ada");
        assertThat(response.myRole()).isEqualTo("OWNER");
        assertThat(response.myParticipantId()).isEqualTo(owner.getId());
        assertThat(response.canAddContent()).isTrue();
        assertThat(response.memberCount()).isEqualTo(4);
        assertThat(response.maxMembers()).isEqualTo(50);
        assertThat(response.nowPlaying().title()).isEqualTo("Morning Prayer");
        assertThat(response.archivedAt()).isNull();
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-10-01T06:00:00Z"));
    }

    @Test
    void guestSeesGuestRoleCantAddAndNothingPlaying() {
        SpaceResponse response = views.of(membership(guest(space, "Bisi")));

        assertThat(response.myRole()).isEqualTo("GUEST");
        assertThat(response.canAddContent()).isFalse();
        assertThat(response.nowPlaying()).isNull();
    }

    @Test
    void memberCanAddOnlyWhenTheRoomAllowsIt() {
        Participant tunde = member(space, user("Tunde"), Participant.Role.MEMBER);

        assertThat(views.of(membership(tunde)).canAddContent()).isFalse();
        space.setMembersCanAddContent(true);
        assertThat(views.of(membership(tunde)).canAddContent()).isTrue();
    }
}

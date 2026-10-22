package com.mywatchmate.watch.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.identity.User;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.content.PreparedVideo;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoMetadata;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The mappers that shape what the frontend receives. */
class WatchDtoMappingTest {

    private final User ada = new User("ada@example.com", "Ada");
    private final Space space = new Space(ada, "6am Prayer Room", "tok", Space.AudienceType.DIASPORA);

    @Test
    void contentResponseCarriesEverythingThePlayerNeeds() {
        Participant adder = Participant.member(space, ada, Participant.Role.OWNER);
        adder.setId(UUID.randomUUID());
        ContentItem item = new ContentItem(space, ContentItem.Source.YOUTUBE, "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        item.setId(UUID.randomUUID());
        item.setExternalId("dQw4w9WgXcQ");
        item.setTitle("Morning Prayer");
        item.setThumbnailUrl("https://i.ytimg.com/x.jpg");
        item.setDurationSeconds(600);
        item.setLive(true);
        item.setAddedBy(adder);
        item.setCreatedAt(Instant.parse("2026-10-22T06:00:00Z"));

        ContentResponse response = ContentResponse.from(item);

        assertThat(response.id()).isEqualTo(item.getId());
        assertThat(response.source()).isEqualTo(ContentItem.Source.YOUTUBE);
        assertThat(response.url()).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(response.externalId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(response.title()).isEqualTo("Morning Prayer");
        assertThat(response.thumbnailUrl()).isEqualTo("https://i.ytimg.com/x.jpg");
        assertThat(response.durationSeconds()).isEqualTo(600);
        assertThat(response.live()).isTrue();
        assertThat(response.addedBy()).isEqualTo(new PersonRef(adder.getId(), "Ada"));
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-10-22T06:00:00Z"));
    }

    @Test
    void contentWhoseAdderWasDeletedHasNoAddedBy() {
        ContentItem item = new ContentItem(space, ContentItem.Source.URL, "https://cdn.example.com/a.mp4");

        assertThat(ContentResponse.from(item).addedBy()).isNull();
    }

    @Test
    void participantResponseForUsersAndGuests() {
        Participant tunde = Participant.member(space, new User("t@example.com", "Tunde"), Participant.Role.ADMIN);
        Participant bisi = Participant.guest(space, "Bisi", "hash");

        assertThat(ParticipantResponse.from(tunde).displayName()).isEqualTo("Tunde");
        assertThat(ParticipantResponse.from(tunde).role()).isEqualTo(Participant.Role.ADMIN);
        assertThat(ParticipantResponse.from(tunde).guest()).isFalse();
        assertThat(ParticipantResponse.from(bisi).displayName()).isEqualTo("Bisi");
        assertThat(ParticipantResponse.from(bisi).guest()).isTrue();
        assertThat(ParticipantResponse.from(bisi).joinedAt()).isNotNull();
    }

    @Test
    void spaceInfoReflectsSettingsAndArchiveState() {
        space.setMembersCanAddContent(true);
        space.setAllowGuests(false);

        assertThat(SpaceInfo.from(space).archived()).isFalse();
        assertThat(SpaceInfo.from(space).membersCanAddContent()).isTrue();
        assertThat(SpaceInfo.from(space).allowGuests()).isFalse();

        space.archive(Instant.now());
        assertThat(SpaceInfo.from(space).archived()).isTrue();
    }

    @Test
    void linkPreviewCombinesTheLinkAndItsMetadata() {
        LinkPreviewResponse preview = LinkPreviewResponse.from(new PreparedVideo(
            new ResolvedLink(ContentItem.Source.VIMEO, "https://vimeo.com/1084537", "1084537", false),
            new VideoMetadata("Big Buck Bunny", "https://i.vimeocdn.com/1.jpg", 597)));

        assertThat(preview).isEqualTo(new LinkPreviewResponse(ContentItem.Source.VIMEO, "https://vimeo.com/1084537",
            "1084537", "Big Buck Bunny", "https://i.vimeocdn.com/1.jpg", 597, false));
    }
}

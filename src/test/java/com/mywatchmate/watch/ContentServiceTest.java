package com.mywatchmate.watch;

import static com.mywatchmate.watch.WatchFixtures.assertApiError;
import static com.mywatchmate.watch.WatchFixtures.guest;
import static com.mywatchmate.watch.WatchFixtures.member;
import static com.mywatchmate.watch.WatchFixtures.membership;
import static com.mywatchmate.watch.WatchFixtures.principalOf;
import static com.mywatchmate.watch.WatchFixtures.space;
import static com.mywatchmate.watch.WatchFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.watch.SpaceEvent.Type;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoLinkResolver;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import com.mywatchmate.watch.dto.AddContentRequest;
import com.mywatchmate.watch.dto.ContentResponse;
import com.mywatchmate.watch.dto.LinkPreviewResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class ContentServiceTest {

    private static final String LINK = "https://youtu.be/dQw4w9WgXcQ?si=tracking";
    private static final String CANONICAL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    @Mock VideoMetadataClient metadata;
    @Mock ContentItemRepository contentItems;
    @Mock SpaceAccess access;
    @Mock SpaceEvents events;

    private ContentService service;
    private Space space;
    private Participant owner;
    private Participant tunde;
    private Participant bisi;

    @BeforeEach
    void setUp() {
        service = new ContentService(new VideoLinkResolver(), metadata, contentItems, access, events,
            new TransactionTemplate(mock(PlatformTransactionManager.class)));
        space = space(user("Ada"));
        owner = member(space, space.getOwner(), Participant.Role.OWNER);
        tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
        bisi = guest(space, "Bisi");
        for (Participant p : List.of(owner, tunde, bisi)) {
            lenient().when(access.require(principalOf(p), space.getId())).thenReturn(membership(p));
        }
        lenient().when(contentItems.saveAndFlush(any(ContentItem.class))).thenAnswer(i -> {
            ContentItem item = i.getArgument(0);
            item.setId(UUID.randomUUID());
            item.setCreatedAt(Instant.now());
            return item;
        });
    }

    @Test
    void previewRecognisesTheLinkAndLooksItUp() {
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("Morning Prayer", "https://i.ytimg.com/x.jpg", null));

        LinkPreviewResponse preview = service.preview(LINK);

        assertThat(preview.source()).isEqualTo(ContentItem.Source.YOUTUBE);
        assertThat(preview.url()).isEqualTo(CANONICAL);
        assertThat(preview.externalId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(preview.title()).isEqualTo("Morning Prayer");
    }

    @Test
    void pastingALinkSavesItAndPlaysItForEveryone() {
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("Morning Prayer", "https://i.ytimg.com/x.jpg", null));

        ContentResponse added = service.add(principalOf(owner), space.getId(), new AddContentRequest(LINK, null));

        assertThat(added.url()).isEqualTo(CANONICAL);
        assertThat(added.title()).isEqualTo("Morning Prayer");
        assertThat(added.thumbnailUrl()).isEqualTo("https://i.ytimg.com/x.jpg");
        assertThat(added.addedBy().displayName()).isEqualTo("Ada");
        assertThat(space.getCurrentContent()).isNotNull();
        assertThat(space.getCurrentContent().getExternalUrl()).isEqualTo(CANONICAL);
        verify(events).publish(space.getId(), Type.CONTENT_ADDED, added);
        verify(events).publish(space.getId(), Type.NOW_PLAYING_CHANGED, added);
    }

    @Test
    void playNowFalseOnlyAddsItToTheList() {
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(VideoMetadata.UNKNOWN);

        service.add(principalOf(owner), space.getId(), new AddContentRequest(LINK, false));

        assertThat(space.getCurrentContent()).isNull();
        verify(events).publish(eq(space.getId()), eq(Type.CONTENT_ADDED), any());
        verify(events, never()).publish(eq(space.getId()), eq(Type.NOW_PLAYING_CHANGED), any());
    }

    @Test
    void permissionIsCheckedBeforeCallingYoutube() {
        assertApiError(() -> service.add(principalOf(tunde), space.getId(), new AddContentRequest(LINK, null)),
            HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        verifyNoInteractions(metadata);
        verify(contentItems, never()).saveAndFlush(any());
    }

    @Test
    void membersCanAddWhenTheRoomAllowsIt() {
        space.setMembersCanAddContent(true);
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(VideoMetadata.UNKNOWN);

        ContentResponse added = service.add(principalOf(tunde), space.getId(), new AddContentRequest(LINK, null));

        assertThat(added.addedBy().displayName()).isEqualTo("Tunde");
    }

    @Test
    void guestsNeverAddVideos() {
        space.setMembersCanAddContent(true);
        assertApiError(() -> service.add(principalOf(bisi), space.getId(), new AddContentRequest(LINK, null)),
            HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
    }

    @Test
    void unsupportedLinkIsRejectedAndNothingIsSaved() {
        assertApiError(() -> service.add(principalOf(owner), space.getId(),
            new AddContentRequest("https://www.tiktok.com/@a/video/1", null)), HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_LINK");
        verify(contentItems, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void veryLongTitlesAreTrimmedToFit() {
        when(metadata.fetch(any(ResolvedLink.class))).thenReturn(new VideoMetadata("x".repeat(500), null, null));

        assertThat(service.add(principalOf(owner), space.getId(), new AddContentRequest(LINK, null)).title()).hasSize(300);
    }

    @Test
    void switchingToAnExistingVideo() {
        ContentItem older = video(owner);
        when(contentItems.findByIdAndSpaceId(older.getId(), space.getId())).thenReturn(Optional.of(older));

        service.playNow(principalOf(owner), space.getId(), older.getId());

        assertThat(space.getCurrentContent()).isSameAs(older);
        verify(events).publish(eq(space.getId()), eq(Type.NOW_PLAYING_CHANGED), any(ContentResponse.class));
    }

    @Test
    void switchingToAVideoFromAnotherRoomIsNotFound() {
        UUID elsewhere = UUID.randomUUID();
        when(contentItems.findByIdAndSpaceId(elsewhere, space.getId())).thenReturn(Optional.empty());

        assertApiError(() -> service.playNow(principalOf(owner), space.getId(), elsewhere), HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND");
    }

    @Test
    void stoppingTellsEveryoneOnlyIfSomethingWasPlaying() {
        service.stopPlaying(principalOf(owner), space.getId());
        verifyNoInteractions(events);

        space.playNow(video(owner));
        service.stopPlaying(principalOf(owner), space.getId());

        assertThat(space.getCurrentContent()).isNull();
        verify(events).publish(eq(space.getId()), eq(Type.NOW_PLAYING_CHANGED), isNull());
    }

    @Test
    void whoeverAddedAVideoCanRemoveItAndPlaybackStopsIfItWasPlaying() {
        ContentItem tundes = video(tunde);
        space.playNow(tundes);
        when(contentItems.findByIdAndSpaceId(tundes.getId(), space.getId())).thenReturn(Optional.of(tundes));

        service.remove(principalOf(tunde), space.getId(), tundes.getId());

        assertThat(space.getCurrentContent()).isNull();
        verify(contentItems).delete(tundes);
        verify(events).publish(eq(space.getId()), eq(Type.NOW_PLAYING_CHANGED), isNull());
        verify(events).publish(space.getId(), Type.CONTENT_REMOVED, Map.of("contentId", tundes.getId()));
    }

    @Test
    void membersCantRemoveOtherPeoplesVideosButTheOwnerCan() {
        ContentItem owners = video(owner);
        when(contentItems.findByIdAndSpaceId(owners.getId(), space.getId())).thenReturn(Optional.of(owners));

        assertApiError(() -> service.remove(principalOf(tunde), space.getId(), owners.getId()), HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        verify(contentItems, never()).delete(any());

        ContentItem tundes = video(tunde);
        when(contentItems.findByIdAndSpaceId(tundes.getId(), space.getId())).thenReturn(Optional.of(tundes));
        service.remove(principalOf(owner), space.getId(), tundes.getId());
        verify(contentItems).delete(tundes);
    }

    @Test
    void listingIsForMembersAndNewestFirstFromTheRepository() {
        when(contentItems.findForSpace(space.getId())).thenReturn(List.of(video(owner), video(tunde)));

        assertThat(service.list(principalOf(bisi), space.getId())).hasSize(2);
        verify(access).require(principalOf(bisi), space.getId());
    }

    private ContentItem video(Participant addedBy) {
        ContentItem item = new ContentItem(space, ContentItem.Source.YOUTUBE, CANONICAL);
        item.setId(UUID.randomUUID());
        item.setAddedBy(addedBy);
        item.setCreatedAt(Instant.now());
        return item;
    }
}

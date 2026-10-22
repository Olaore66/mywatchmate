package com.mywatchmate.watch;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.watch.SpaceAccess.Level;
import com.mywatchmate.watch.SpaceAccess.Membership;
import com.mywatchmate.watch.content.PreparedVideo;
import com.mywatchmate.watch.content.ResolvedLink;
import com.mywatchmate.watch.content.VideoLinkResolver;
import com.mywatchmate.watch.content.VideoMetadata;
import com.mywatchmate.watch.content.VideoMetadataClient;
import com.mywatchmate.watch.dto.AddContentRequest;
import com.mywatchmate.watch.dto.ContentResponse;
import com.mywatchmate.watch.dto.LinkPreviewResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Videos in rooms. Pasting a link (by default) makes it "now playing" for everyone.
 * Looking a video up calls YouTube/Vimeo, so that happens outside database transactions.
 */
@Service
@RequiredArgsConstructor
public class ContentService {

    private final VideoLinkResolver resolver;
    private final VideoMetadataClient metadata;
    private final ContentItemRepository contentItems;
    private final SpaceAccess access;
    private final SpaceEvents events;
    private final TransactionTemplate tx;

    /** Recognise the link and look up title/thumbnail. Network call: never inside a transaction. */
    public PreparedVideo prepare(String url) {
        ResolvedLink link = resolver.resolve(url);
        VideoMetadata found = metadata.fetch(link);
        return new PreparedVideo(link, found);
    }

    public LinkPreviewResponse preview(String url) {
        return LinkPreviewResponse.from(prepare(url));
    }

    public ContentResponse add(AuthPrincipal principal, UUID spaceId, AddContentRequest request) {
        // Check permission first, so outsiders can't make us call YouTube on their behalf
        tx.executeWithoutResult(status -> requireCanAdd(principal, spaceId));
        PreparedVideo video = prepare(request.url());
        return tx.execute(status -> {
            Membership membership = requireCanAdd(principal, spaceId);
            ContentItem item = save(membership, video);
            ContentResponse response = ContentResponse.from(item);
            events.publish(spaceId, SpaceEvent.Type.CONTENT_ADDED, response);
            if (request.playNow() == null || request.playNow()) {
                membership.space().playNow(item);
                events.publish(spaceId, SpaceEvent.Type.NOW_PLAYING_CHANGED, response);
            }
            return response;
        });
    }

    /** Saves a prepared video into the room. Call inside a transaction. */
    public ContentItem save(Membership membership, PreparedVideo video) {
        ContentItem item = new ContentItem(membership.space(), video.link().source(), video.link().canonicalUrl());
        item.setExternalId(video.link().externalId());
        item.setLive(video.link().live());
        item.setTitle(truncate(video.metadata().title(), 300));
        item.setThumbnailUrl(video.metadata().thumbnailUrl());
        item.setDurationSeconds(video.metadata().durationSeconds());
        item.setAddedBy(membership.participant());
        return contentItems.saveAndFlush(item);
    }

    @Transactional(readOnly = true)
    public List<ContentResponse> list(AuthPrincipal principal, UUID spaceId) {
        access.require(principal, spaceId);
        return contentItems.findForSpace(spaceId).stream().map(ContentResponse::from).toList();
    }

    @Transactional
    public ContentResponse playNow(AuthPrincipal principal, UUID spaceId, UUID contentId) {
        Membership membership = requireCanAdd(principal, spaceId);
        ContentItem item = find(spaceId, contentId);
        membership.space().playNow(item);
        ContentResponse response = ContentResponse.from(item);
        events.publish(spaceId, SpaceEvent.Type.NOW_PLAYING_CHANGED, response);
        return response;
    }

    @Transactional
    public void stopPlaying(AuthPrincipal principal, UUID spaceId) {
        Membership membership = requireCanAdd(principal, spaceId);
        if (membership.space().getCurrentContent() != null) {
            membership.space().stopPlaying();
            events.publish(spaceId, SpaceEvent.Type.NOW_PLAYING_CHANGED, null);
        }
    }

    /** The person who added it, or the owner/admins, can remove a video. */
    @Transactional
    public void remove(AuthPrincipal principal, UUID spaceId, UUID contentId) {
        Membership membership = access.require(principal, spaceId).requireNotArchived();
        ContentItem item = find(spaceId, contentId);
        boolean addedIt = item.getAddedBy() != null && item.getAddedBy().getId().equals(membership.participant().getId());
        if (!membership.isOwnerOrAdmin() && !addedIt) {
            throw ApiException.forbidden("FORBIDDEN_ROLE",
                "Only the person who added this video, the owner or admins can remove it.");
        }
        Space space = membership.space();
        if (space.getCurrentContent() != null && space.getCurrentContent().getId().equals(item.getId())) {
            space.stopPlaying();
            events.publish(spaceId, SpaceEvent.Type.NOW_PLAYING_CHANGED, null);
        }
        contentItems.delete(item);
        events.publish(spaceId, SpaceEvent.Type.CONTENT_REMOVED, Map.of("contentId", contentId));
    }

    private Membership requireCanAdd(AuthPrincipal principal, UUID spaceId) {
        Membership membership = access.require(principal, spaceId).requireNotArchived();
        if (!membership.canAddContent()) {
            throw ApiException.forbidden("FORBIDDEN_ROLE", membership.level() == Level.GUEST
                ? "Guests can't add videos. Create an account to continue."
                : "Only the owner or admins can add videos in this room.");
        }
        return membership;
    }

    private ContentItem find(UUID spaceId, UUID contentId) {
        return contentItems.findByIdAndSpaceId(contentId, spaceId)
            .orElseThrow(() -> ApiException.notFound("CONTENT_NOT_FOUND", "That video isn't in this room."));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

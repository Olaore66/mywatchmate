package com.mywatchmate.watch.content;

/**
 * Looks up title/thumbnail/duration for a resolved link.
 * Throws {@link com.mywatchmate.common.ApiException} when the provider says the video can't be played
 * (VIDEO_NOT_FOUND, VIDEO_NOT_EMBEDDABLE); returns {@link VideoMetadata#UNKNOWN} when it just can't be reached.
 */
public interface VideoMetadataClient {

    VideoMetadata fetch(ResolvedLink link);
}

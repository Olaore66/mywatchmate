package com.mywatchmate.watch.content;

/** What we could learn about a video; any field may be null. */
public record VideoMetadata(String title, String thumbnailUrl, Integer durationSeconds) {

    public static final VideoMetadata UNKNOWN = new VideoMetadata(null, null, null);
}

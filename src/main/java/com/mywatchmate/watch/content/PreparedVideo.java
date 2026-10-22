package com.mywatchmate.watch.content;

/** A link that has been recognised and looked up, ready to save. */
public record PreparedVideo(ResolvedLink link, VideoMetadata metadata) {
}

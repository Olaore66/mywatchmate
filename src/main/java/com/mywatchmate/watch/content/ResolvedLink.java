package com.mywatchmate.watch.content;

import com.mywatchmate.watch.ContentItem;

/**
 * A pasted link we know how to play.
 *
 * @param canonicalUrl one stable form per video (tracking params etc. stripped)
 * @param externalId   the provider's video id; null for direct files
 * @param live         a live stream (only knowable from the link for YouTube /live/ URLs)
 */
public record ResolvedLink(ContentItem.Source source, String canonicalUrl, String externalId, boolean live) {
}

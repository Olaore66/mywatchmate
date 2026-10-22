package com.mywatchmate.watch.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.watch.ContentItem.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class VideoLinkResolverTest {

    private final VideoLinkResolver resolver = new VideoLinkResolver();

    @ParameterizedTest
    @ValueSource(strings = {
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://youtube.com/watch?v=dQw4w9WgXcQ",
        "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s",
        "https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&list=PLx&index=3",
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=AbCdEfGh123",
        "https://youtu.be/dQw4w9WgXcQ",
        "https://youtu.be/dQw4w9WgXcQ?si=tracking&t=10",
        "https://www.youtube.com/shorts/dQw4w9WgXcQ",
        "https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1",
        "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://www.youtube.com/v/dQw4w9WgXcQ",
        "  https://youtu.be/dQw4w9WgXcQ  ",
        "youtu.be/dQw4w9WgXcQ",
        "www.youtube.com/watch?v=dQw4w9WgXcQ",
        "HTTPS://WWW.YOUTUBE.COM/watch?v=dQw4w9WgXcQ"
    })
    void recognisesEveryCommonYoutubeLinkAsOneCanonicalVideo(String link) {
        ResolvedLink resolved = resolver.resolve(link);

        assertThat(resolved.source()).isEqualTo(Source.YOUTUBE);
        assertThat(resolved.externalId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(resolved.canonicalUrl()).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(resolved.live()).isFalse();
    }

    @Test
    void youtubeLiveLinksAreMarkedLive() {
        ResolvedLink resolved = resolver.resolve("https://www.youtube.com/live/abcDEF12345?si=x");

        assertThat(resolved.externalId()).isEqualTo("abcDEF12345");
        assertThat(resolved.live()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://www.youtube.com/",
        "https://www.youtube.com/@SomeChurch",
        "https://www.youtube.com/channel/UC1234567890",
        "https://www.youtube.com/playlist?list=PL123",
        "https://www.youtube.com/watch",
        "https://www.youtube.com/watch?v=short",
        "https://www.youtube.com/watch?v=has%20spaces!",
        "https://youtu.be/"
    })
    void youtubeLinksThatAreNotOneVideoAreRejected(String link) {
        assertRejected(link, "doesn't point to a video");
    }

    @Test
    void vimeoPublicVideo() {
        ResolvedLink resolved = resolver.resolve("https://vimeo.com/76979871");

        assertThat(resolved.source()).isEqualTo(Source.VIMEO);
        assertThat(resolved.externalId()).isEqualTo("76979871");
        assertThat(resolved.canonicalUrl()).isEqualTo("https://vimeo.com/76979871");
    }

    @Test
    void vimeoUnlistedVideoKeepsItsPrivacyHash() {
        assertThat(resolver.resolve("https://vimeo.com/76979871/a1b2c3d4e5").canonicalUrl())
            .isEqualTo("https://vimeo.com/76979871/a1b2c3d4e5");
        assertThat(resolver.resolve("https://player.vimeo.com/video/76979871?h=a1b2c3d4e5&autoplay=1").canonicalUrl())
            .isEqualTo("https://vimeo.com/76979871/a1b2c3d4e5");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://vimeo.com/channels/staffpicks/76979871",
        "https://vimeo.com/groups/shortfilms/videos/76979871",
        "https://player.vimeo.com/video/76979871",
        "https://www.vimeo.com/76979871?share=copy"
    })
    void otherVimeoFormats(String link) {
        ResolvedLink resolved = resolver.resolve(link);

        assertThat(resolved.source()).isEqualTo(Source.VIMEO);
        assertThat(resolved.canonicalUrl()).isEqualTo("https://vimeo.com/76979871");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://vimeo.com/", "https://vimeo.com/about", "https://player.vimeo.com/api/player.js"})
    void vimeoLinksThatAreNotAVideoAreRejected(String link) {
        assertRejected(link, "doesn't point to a video");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://cdn.example.com/videos/day-12.mp4",
        "https://cdn.example.com/videos/day-12.MP4?token=abc",
        "https://cdn.example.com/a.webm",
        "https://cdn.example.com/live/stream.m3u8"
    })
    void directHttpsVideoFiles(String link) {
        ResolvedLink resolved = resolver.resolve(link);

        assertThat(resolved.source()).isEqualTo(Source.URL);
        assertThat(resolved.externalId()).isNull();
        assertThat(resolved.canonicalUrl()).isEqualTo(link);
    }

    @Test
    void directFilesMustBeHttps() {
        assertRejected("http://cdn.example.com/videos/day-12.mp4", "https://");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://www.facebook.com/watch/?v=123456",
        "https://www.instagram.com/reel/Cabc123/",
        "https://www.tiktok.com/@user/video/1234567890",
        "https://x.com/user/status/1234567890",
        "https://example.com/sermon",
        "https://example.com/sermon.pdf"
    })
    void unsyncablePlatformsAndPagesAreRejected(String link) {
        assertRejected(link, "YouTube and Vimeo");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "   ",
        "ftp://example.com/video.mp4",
        "javascript:alert(1)",
        "file:///C:/videos/a.mp4",
        "https://user:pass@www.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://exa mple.com/a.mp4",
        "not a link at all"
    })
    void garbageAndDangerousInputIsRejected(String link) {
        assertThatThrownBy(() -> resolver.resolve(link))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getCode()).isEqualTo("UNSUPPORTED_LINK");
            });
    }

    private void assertRejected(String link, String messagePart) {
        assertThatThrownBy(() -> resolver.resolve(link))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getCode()).isEqualTo("UNSUPPORTED_LINK");
                assertThat(e.getMessage()).contains(messagePart);
            });
    }
}

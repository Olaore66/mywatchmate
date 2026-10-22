package com.mywatchmate.watch.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.watch.ContentItem.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OEmbedClientTest {

    private static final ResolvedLink YOUTUBE =
        new ResolvedLink(Source.YOUTUBE, "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ", false);
    private static final ResolvedLink VIMEO = new ResolvedLink(Source.VIMEO, "https://vimeo.com/76979871", "76979871", false);
    private static final String YOUTUBE_LOOKUP =
        "https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DdQw4w9WgXcQ";

    private MockRestServiceServer server;
    private OEmbedClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OEmbedClient(builder.build());
    }

    @Test
    void youtubeTitleAndThumbnailComeFromYoutubesOwnEndpoint() {
        server.expect(requestTo(YOUTUBE_LOOKUP)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {"title":"Morning Prayer, Day 12","thumbnail_url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                 "author_name":"Some Church"}""", MediaType.APPLICATION_JSON));

        VideoMetadata metadata = client.fetch(YOUTUBE);

        assertThat(metadata.title()).isEqualTo("Morning Prayer, Day 12");
        assertThat(metadata.thumbnailUrl()).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
        assertThat(metadata.durationSeconds()).isNull(); // YouTube's oEmbed has no duration
        server.verify();
    }

    @Test
    void vimeoIncludesDuration() {
        server.expect(requestTo("https://vimeo.com/api/oembed.json?url=https%3A%2F%2Fvimeo.com%2F76979871"))
            .andRespond(withSuccess("""
                {"title":"The New Vimeo Player","thumbnail_url":"https://i.vimeocdn.com/video/1.jpg","duration":62}""",
                MediaType.APPLICATION_JSON));

        VideoMetadata metadata = client.fetch(VIMEO);

        assertThat(metadata.title()).isEqualTo("The New Vimeo Player");
        assertThat(metadata.durationSeconds()).isEqualTo(62);
    }

    @Test
    void missingVideoIsReported() {
        server.expect(requestTo(YOUTUBE_LOOKUP)).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.fetch(YOUTUBE))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("VIDEO_NOT_FOUND"));
    }

    @Test
    void videoThatCantBeEmbeddedIsReported() {
        server.expect(requestTo(YOUTUBE_LOOKUP)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.fetch(YOUTUBE))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("VIDEO_NOT_EMBEDDABLE"));
    }

    @Test
    void providerOutageStillLetsTheVideoBeAdded() {
        server.expect(requestTo(YOUTUBE_LOOKUP)).andRespond(withServerError());

        assertThat(client.fetch(YOUTUBE)).isEqualTo(VideoMetadata.UNKNOWN);
    }

    @Test
    void blankFieldsBecomeNull() {
        server.expect(requestTo(YOUTUBE_LOOKUP))
            .andRespond(withSuccess("{\"title\":\"  \",\"duration\":0}", MediaType.APPLICATION_JSON));

        assertThat(client.fetch(YOUTUBE)).isEqualTo(VideoMetadata.UNKNOWN);
    }

    @Test
    void rateLimitingOrOtherClientErrorsDontBlockAddingTheVideo() {
        server.expect(requestTo(YOUTUBE_LOOKUP)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(client.fetch(YOUTUBE)).isEqualTo(VideoMetadata.UNKNOWN);
    }

    @Test
    void forbiddenIsTreatedAsNotEmbeddableAndBadRequestAsNotFound() {
        server.expect(requestTo(YOUTUBE_LOOKUP)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> client.fetch(YOUTUBE))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("VIDEO_NOT_EMBEDDABLE"));

        server.reset();
        server.expect(requestTo(YOUTUBE_LOOKUP)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> client.fetch(YOUTUBE))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("VIDEO_NOT_FOUND"));
    }

    @Test
    void wrongTypesInTheResponseAreIgnored() {
        server.expect(requestTo(YOUTUBE_LOOKUP))
            .andRespond(withSuccess("{\"title\":42,\"thumbnail_url\":[\"x\"],\"duration\":\"long\"}", MediaType.APPLICATION_JSON));

        assertThat(client.fetch(YOUTUBE)).isEqualTo(VideoMetadata.UNKNOWN);
    }

    @Test
    void fileTitlesFromAwkwardNames() {
        assertThat(OEmbedClient.fileTitle("https://cdn.example.com/a/sermon.final.v2.mp4")).isEqualTo("sermon.final.v2");
        assertThat(OEmbedClient.fileTitle("https://cdn.example.com/a/noextension")).isEqualTo("noextension");
        assertThat(OEmbedClient.fileTitle("https://cdn.example.com/a/%20.mp4")).isNull();
    }

    @Test
    void productionClientCanBeBuilt() {
        assertThat(new OEmbedClient()).isNotNull();
    }

    @Test
    void directFilesAreNeverFetchedAndGetATitleFromTheFileName() {
        // No expectations: any HTTP call would fail the test
        VideoMetadata metadata = client.fetch(
            new ResolvedLink(Source.URL, "https://cdn.example.com/videos/Day%2012%20Prayer.mp4", null, false));

        assertThat(metadata.title()).isEqualTo("Day 12 Prayer");
        assertThat(metadata.thumbnailUrl()).isNull();
        server.verify();
    }
}

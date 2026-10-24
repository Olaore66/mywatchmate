package com.mywatchmate.live.dto;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static com.mywatchmate.testsupport.Fixtures.video;
import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.live.PlaybackState;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The shapes the frontend receives for live sync. */
class LiveDtoMappingTest {

    private final Space space = space(user("Ada"));

    @Test
    void playbackIsReportedAsOfTheGivenMoment() {
        ContentItem movie = video(space);
        movie.setDurationSeconds(600);
        Participant tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
        PlaybackState state = PlaybackState.initial(space.getId(), null, MONDAY_MIDNIGHT);
        state.load(movie, MONDAY_MIDNIGHT);
        state.seek(60_000, MONDAY_MIDNIGHT, tunde);
        Instant later = MONDAY_MIDNIGHT.plusSeconds(30);

        PlaybackResponse response = PlaybackResponse.of(state, later);

        assertThat(response.contentId()).isEqualTo(movie.getId());
        assertThat(response.status()).isEqualTo(PlaybackState.Status.PLAYING);
        assertThat(response.positionMs()).isEqualTo(90_000);
        assertThat(response.serverTimeMs()).isEqualTo(later.toEpochMilli());
        assertThat(response.durationMs()).isEqualTo(600_000);
        assertThat(response.live()).isFalse();
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.updatedBy().id()).isEqualTo(tunde.getId());
        assertThat(response.updatedBy().displayName()).isEqualTo("Tunde");
    }

    @Test
    void anEmptyRoomHasNoVideoAndNobodyPressedAnything() {
        PlaybackResponse response = PlaybackResponse.of(PlaybackState.initial(space.getId(), null, MONDAY_MIDNIGHT),
            MONDAY_MIDNIGHT);

        assertThat(response.contentId()).isNull();
        assertThat(response.status()).isEqualTo(PlaybackState.Status.STOPPED);
        assertThat(response.durationMs()).isNull();
        assertThat(response.live()).isFalse();
        assertThat(response.updatedBy()).isNull();
    }

    @Test
    void liveStreamsAreFlaggedAndHaveNoLength() {
        ContentItem stream = video(space);
        stream.setLive(true);
        stream.setDurationSeconds(600);

        PlaybackResponse response = PlaybackResponse.of(PlaybackState.initial(space.getId(), stream, MONDAY_MIDNIGHT),
            MONDAY_MIDNIGHT);

        assertThat(response.live()).isTrue();
        assertThat(response.durationMs()).isNull();
    }

    @Test
    void presenceCountsWhoIsInTheList() {
        List<OnlineViewer> online = List.of(new OnlineViewer(UUID.randomUUID(), "Ada", false),
            new OnlineViewer(UUID.randomUUID(), "Bisi", true));

        assertThat(PresenceResponse.of(online).onlineCount()).isEqualTo(2);
        assertThat(PresenceResponse.of(online).online()).isEqualTo(online);
        assertThat(PresenceResponse.of(List.of()).onlineCount()).isZero();
    }

    @Test
    void timeIsGivenInBothForms() {
        TimeResponse time = TimeResponse.at(MONDAY_MIDNIGHT);

        assertThat(time.serverTimeMs()).isEqualTo(MONDAY_MIDNIGHT.toEpochMilli());
        assertThat(time.serverTime()).isEqualTo(MONDAY_MIDNIGHT);
    }
}

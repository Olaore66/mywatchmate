package com.mywatchmate.live;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static com.mywatchmate.testsupport.Fixtures.video;
import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.live.PlaybackState.Status;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaybackStateTest {

    private static final Instant T0 = MONDAY_MIDNIGHT;

    private Space space;
    private ContentItem tenMinutes;
    private Participant tunde;

    @BeforeEach
    void setUp() {
        space = space(user("Ada"));
        tenMinutes = video(space);
        tenMinutes.setDurationSeconds(600);
        tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
    }

    private static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    private PlaybackState playingFromStart() {
        PlaybackState state = PlaybackState.initial(space.getId(), null, T0);
        state.load(tenMinutes, T0);
        return state;
    }

    @Test
    void aRoomStartsStoppedOrPausedAtTheBeginning() {
        PlaybackState empty = PlaybackState.initial(space.getId(), null, T0);
        assertThat(empty.getStatus()).isEqualTo(Status.STOPPED);
        assertThat(empty.getVersion()).isZero();

        PlaybackState withVideo = PlaybackState.initial(space.getId(), tenMinutes, T0);
        assertThat(withVideo.getStatus()).isEqualTo(Status.PAUSED);
        assertThat(withVideo.positionAt(at(30))).isZero();
    }

    @Test
    void aNewVideoPlaysFromTheStartForEveryone() {
        PlaybackState state = playingFromStart();

        assertThat(state.getStatus()).isEqualTo(Status.PLAYING);
        assertThat(state.getContent()).isSameAs(tenMinutes);
        assertThat(state.getVersion()).isEqualTo(1);
        assertThat(state.getUpdatedBy()).isNull();
    }

    @Test
    void whilePlayingThePositionMovesWithTheClock() {
        PlaybackState state = playingFromStart();

        assertThat(state.positionAt(at(0))).isZero();
        assertThat(state.positionAt(at(90))).isEqualTo(90_000);
        assertThat(state.positionAt(T0.plusMillis(1_250))).isEqualTo(1_250);
    }

    @Test
    void aClockReadingFromBeforeTheChangeDoesntGoBackwards() {
        PlaybackState state = playingFromStart();
        state.seek(60_000, at(10), tunde);

        assertThat(state.positionAt(at(5))).isEqualTo(60_000);
    }

    @Test
    void pauseFreezesTheMomentItWasPressed() {
        PlaybackState state = playingFromStart();
        state.pause(at(42), tunde);

        assertThat(state.getStatus()).isEqualTo(Status.PAUSED);
        assertThat(state.positionAt(at(42))).isEqualTo(42_000);
        assertThat(state.positionAt(at(500))).as("paused: stays put").isEqualTo(42_000);
        assertThat(state.getUpdatedBy()).isSameAs(tunde);
    }

    @Test
    void playCarriesOnFromWhereItWasPaused() {
        PlaybackState state = playingFromStart();
        state.pause(at(42), tunde);
        state.play(at(100), tunde);

        assertThat(state.positionAt(at(100))).isEqualTo(42_000);
        assertThat(state.positionAt(at(110))).isEqualTo(52_000);
    }

    @Test
    void seekKeepsPlayingIfItWasPlayingAndPausedIfPaused() {
        PlaybackState playing = playingFromStart();
        playing.seek(300_000, at(10), tunde);
        assertThat(playing.getStatus()).isEqualTo(Status.PLAYING);
        assertThat(playing.positionAt(at(15))).isEqualTo(305_000);

        PlaybackState paused = playingFromStart();
        paused.pause(at(10), tunde);
        paused.seek(300_000, at(20), tunde);
        assertThat(paused.getStatus()).isEqualTo(Status.PAUSED);
        assertThat(paused.positionAt(at(99))).isEqualTo(300_000);
    }

    @Test
    void aFinishedVideoIsShownPausedAtItsEnd() {
        PlaybackState state = playingFromStart();

        assertThat(state.hasEnded(at(599))).isFalse();
        assertThat(state.hasEnded(at(600))).isTrue();
        assertThat(state.positionAt(at(900))).isEqualTo(600_000);
        assertThat(state.statusAt(at(900))).isEqualTo(Status.PAUSED);
        assertThat(state.statusAt(at(300))).isEqualTo(Status.PLAYING);
    }

    @Test
    void pressingPlayOnAFinishedVideoStartsItAgain() {
        PlaybackState state = playingFromStart();
        state.play(at(900), tunde);

        assertThat(state.positionAt(at(900))).isZero();
        assertThat(state.statusAt(at(910))).isEqualTo(Status.PLAYING);

        PlaybackState pausedAtTheEnd = playingFromStart();
        pausedAtTheEnd.seek(600_000, at(1), tunde);
        pausedAtTheEnd.pause(at(2), tunde);
        pausedAtTheEnd.play(at(3), tunde);
        assertThat(pausedAtTheEnd.positionAt(at(3))).isZero();
    }

    @Test
    void seekingAFinishedVideoLeavesItPausedAtTheNewSpot() {
        PlaybackState state = playingFromStart();
        state.seek(120_000, at(900), tunde);

        assertThat(state.getStatus()).isEqualTo(Status.PAUSED);
        assertThat(state.positionAt(at(950))).isEqualTo(120_000);
    }

    @Test
    void unknownLengthAndLiveStreamsNeverEnd() {
        tenMinutes.setDurationSeconds(null);
        PlaybackState unknown = playingFromStart();
        assertThat(unknown.lengthMs()).isNull();
        assertThat(unknown.hasEnded(at(100_000))).isFalse();
        assertThat(unknown.positionAt(at(100_000))).isEqualTo(100_000_000);

        tenMinutes.setDurationSeconds(600);
        tenMinutes.setLive(true);
        assertThat(playingFromStart().lengthMs()).as("a live stream's length means nothing").isNull();
    }

    @Test
    void anEmptyRoomHasNoLengthAndNeverEnds() {
        PlaybackState empty = PlaybackState.initial(space.getId(), null, T0);

        assertThat(empty.lengthMs()).isNull();
        assertThat(empty.hasEnded(at(1_000))).isFalse();
        assertThat(empty.statusAt(at(1_000))).isEqualTo(Status.STOPPED);
    }

    @Test
    void everyChangeBumpsTheVersionAndStoppingClearsTheVideo() {
        PlaybackState state = playingFromStart();
        state.pause(at(1), tunde);
        state.play(at(2), tunde);
        state.seek(5_000, at(3), tunde);
        assertThat(state.getVersion()).isEqualTo(4);

        state.load(null, at(4));
        assertThat(state.getVersion()).isEqualTo(5);
        assertThat(state.getStatus()).isEqualTo(Status.STOPPED);
        assertThat(state.getContent()).isNull();
        assertThat(state.positionAt(at(10))).isZero();
        assertThat(state.getUpdatedBy()).isNull();
    }
}

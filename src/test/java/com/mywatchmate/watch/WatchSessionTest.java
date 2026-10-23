package com.mywatchmate.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mywatchmate.common.ApiException;
import com.mywatchmate.identity.User;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WatchSessionTest {

    private static final Instant SIX_AM_LAGOS = Instant.parse("2026-11-02T05:00:00Z");
    private static final Duration MAX = Duration.ofHours(3);
    private static final Duration GRACE = Duration.ofMinutes(15);

    private final Space space = new Space(new User("ada@example.com", "Ada"), "Room", "tok", Space.AudienceType.DIASPORA);

    @Test
    void scheduledLiveEnded() {
        WatchSession session = new WatchSession(space, null, SIX_AM_LAGOS);
        assertThat(session.isScheduled()).isTrue();

        session.start(SIX_AM_LAGOS);
        assertThat(session.isLive()).isTrue();
        assertThat(session.getStartedAt()).isEqualTo(SIX_AM_LAGOS);

        session.end(SIX_AM_LAGOS.plusSeconds(3600));
        assertThat(session.getStatus()).isEqualTo(WatchSession.Status.ENDED);
        assertThat(session.getEndedAt()).isEqualTo(SIX_AM_LAGOS.plusSeconds(3600));
    }

    @Test
    void cancelledOrMissedOnlyFromScheduled() {
        WatchSession cancelled = new WatchSession(space, null, SIX_AM_LAGOS);
        cancelled.cancel();
        assertThat(cancelled.getStatus()).isEqualTo(WatchSession.Status.CANCELLED);

        WatchSession missed = new WatchSession(space, null, SIX_AM_LAGOS);
        missed.markMissed(SIX_AM_LAGOS.plusSeconds(4 * 3600));
        assertThat(missed.getStatus()).isEqualTo(WatchSession.Status.ENDED);
        assertThat(missed.getStartedAt()).as("never started").isNull();
    }

    @Test
    void wrongTransitionsAreAConflict() {
        WatchSession session = new WatchSession(space, null, SIX_AM_LAGOS);
        assertWrongStatus(() -> session.end(SIX_AM_LAGOS));

        session.start(SIX_AM_LAGOS);
        assertWrongStatus(() -> session.start(SIX_AM_LAGOS));
        assertWrongStatus(session::cancel);

        session.end(SIX_AM_LAGOS);
        assertWrongStatus(() -> session.end(SIX_AM_LAGOS));
        assertWrongStatus(() -> session.markMissed(SIX_AM_LAGOS));
    }

    @Test
    void endsWhenAKnownLengthVideoIsOverPlusGrace() {
        ContentItem video = new ContentItem(space, ContentItem.Source.VIMEO, "https://vimeo.com/1");
        video.setDurationSeconds(600);
        WatchSession session = new WatchSession(space, video, SIX_AM_LAGOS);
        session.start(SIX_AM_LAGOS);

        assertThat(session.autoEndAt(MAX, GRACE)).isEqualTo(SIX_AM_LAGOS.plusSeconds(600).plus(GRACE));
    }

    @Test
    void endsAfterTheMaximumWhenLengthIsUnknownOrLive() {
        ContentItem youtube = new ContentItem(space, ContentItem.Source.YOUTUBE, "https://www.youtube.com/watch?v=x");
        WatchSession unknown = new WatchSession(space, youtube, SIX_AM_LAGOS);
        unknown.start(SIX_AM_LAGOS);

        ContentItem stream = new ContentItem(space, ContentItem.Source.YOUTUBE, "https://www.youtube.com/watch?v=y");
        stream.setLive(true);
        stream.setDurationSeconds(600);
        WatchSession live = new WatchSession(space, stream, SIX_AM_LAGOS);
        live.start(SIX_AM_LAGOS);

        WatchSession nothing = new WatchSession(space, null, SIX_AM_LAGOS);
        nothing.start(SIX_AM_LAGOS);

        assertThat(unknown.autoEndAt(MAX, GRACE)).isEqualTo(SIX_AM_LAGOS.plus(MAX));
        assertThat(live.autoEndAt(MAX, GRACE)).isEqualTo(SIX_AM_LAGOS.plus(MAX));
        assertThat(nothing.autoEndAt(MAX, GRACE)).isEqualTo(SIX_AM_LAGOS.plus(MAX));
    }

    @Test
    void sessionsFromASchedulePickUpItsTitleAndVideo() {
        ContentItem video = new ContentItem(space, ContentItem.Source.YOUTUBE, "https://www.youtube.com/watch?v=x");
        Schedule schedule = new Schedule(space, "FREQ=WEEKLY;BYDAY=MO;BYHOUR=6;BYMINUTE=0", "Africa/Lagos", LocalDate.of(2026, 11, 1));
        schedule.setTitle("Morning Devotion");
        schedule.setContentItem(video);

        WatchSession session = WatchSession.fromSchedule(schedule, SIX_AM_LAGOS);

        assertThat(session.getSchedule()).isSameAs(schedule);
        assertThat(session.getSpace()).isSameAs(space);
        assertThat(session.getTitle()).isEqualTo("Morning Devotion");
        assertThat(session.getContentItem()).isSameAs(video);
        assertThat(session.getScheduledAt()).isEqualTo(SIX_AM_LAGOS);
    }

    private static void assertWrongStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getCode()).isEqualTo("SESSION_WRONG_STATUS"));
    }
}

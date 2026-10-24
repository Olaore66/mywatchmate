package com.mywatchmate.track.dto;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.guest;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.track.Attendance;
import com.mywatchmate.track.Note;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.WatchSession;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The shapes the frontend receives for attendance and notes. */
class TrackDtoMappingTest {

    private final Space space = space(user("Ada"));
    private final Participant bisi = guest(space, "Bisi");

    private Attendance watched(WatchSession s, int seconds) {
        Attendance a = new Attendance(s, bisi, MONDAY_MIDNIGHT);
        a.seen(MONDAY_MIDNIGHT.plusSeconds(seconds), Duration.ofHours(1));
        return a;
    }

    @Test
    void anAttendeeShowsCountedOnlyOnceDecided() {
        WatchSession s = session(space, MONDAY_MIDNIGHT);
        Attendance a = watched(s, 1200);
        a.finish(900);

        AttendeeResponse live = AttendeeResponse.from(a, false);
        AttendeeResponse ended = AttendeeResponse.from(a, true);

        assertThat(live.counted()).isNull();
        assertThat(ended.counted()).isTrue();
        assertThat(ended.participantId()).isEqualTo(bisi.getId());
        assertThat(ended.displayName()).isEqualTo("Bisi");
        assertThat(ended.guest()).isTrue();
        assertThat(ended.watchedSeconds()).isEqualTo(1200);
        assertThat(ended.lastSeenAt()).isEqualTo(MONDAY_MIDNIGHT.plusSeconds(1200));
    }

    @Test
    void myAttendanceNamesTheRoomAndSession() {
        WatchSession s = session(space, MONDAY_MIDNIGHT);
        s.setTitle("Morning Devotion");
        s.start(MONDAY_MIDNIGHT);
        Attendance a = watched(s, 600);

        assertThat(MyAttendanceResponse.from(a).counted()).as("still live").isNull();

        s.end(MONDAY_MIDNIGHT.plusSeconds(1800));
        a.finish(900);
        MyAttendanceResponse mine = MyAttendanceResponse.from(a);
        assertThat(mine.counted()).isFalse();
        assertThat(mine.sessionTitle()).isEqualTo("Morning Devotion");
        assertThat(mine.spaceId()).isEqualTo(space.getId());
        assertThat(mine.spaceTitle()).isEqualTo("6am Prayer Room");
        assertThat(mine.endedAt()).isEqualTo(MONDAY_MIDNIGHT.plusSeconds(1800));
    }

    @Test
    void aNoteShowsItsAuthorSessionAndMoment() {
        Participant tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
        Note note = new Note(space, tunde, "Verse 4");
        ReflectionTestUtils.setField(note, "id", UUID.randomUUID());
        note.setPositionSeconds(750);
        note.setVisibility(Note.Visibility.SHARED);

        NoteResponse general = NoteResponse.from(note);
        assertThat(general.sessionId()).isNull();
        assertThat(general.author().id()).isEqualTo(tunde.getId());
        assertThat(general.author().displayName()).isEqualTo("Tunde");
        assertThat(general.positionSeconds()).isEqualTo(750);
        assertThat(general.visibility()).isEqualTo(Note.Visibility.SHARED);
        assertThat(general.spaceId()).isEqualTo(space.getId());

        WatchSession s = session(space, MONDAY_MIDNIGHT);
        note.setSession(s);
        assertThat(NoteResponse.from(note).sessionId()).isEqualTo(s.getId());
    }
}

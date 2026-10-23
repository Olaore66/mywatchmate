package com.mywatchmate.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.identity.User;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Schedule;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.WatchSession;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.http.HttpStatus;

/** Entities with ids and a fixed clock, for unit tests. No database. */
public final class Fixtures {

    /** Mon 2 Nov 2026, 00:00 UTC: a convenient "now". */
    public static final Instant MONDAY_MIDNIGHT = Instant.parse("2026-11-02T00:00:00Z");

    private Fixtures() {
    }

    public static Clock clockAt(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    public static User user(String name) {
        User user = new User(name.toLowerCase() + "@example.com", name);
        user.setId(UUID.randomUUID());
        return user;
    }

    public static Space space(User owner) {
        Space space = new Space(owner, "6am Prayer Room", "invite-token-1", Space.AudienceType.DIASPORA);
        space.setId(UUID.randomUUID());
        return space;
    }

    public static Participant member(Space space, User user, Participant.Role role) {
        Participant participant = Participant.member(space, user, role);
        participant.setId(UUID.randomUUID());
        return participant;
    }

    public static Participant guest(Space space, String name) {
        Participant participant = Participant.guest(space, name, "hash-" + name);
        participant.setId(UUID.randomUUID());
        return participant;
    }

    public static SpaceAccess.Membership membership(Participant participant) {
        return new SpaceAccess.Membership(participant.getSpace(), participant, SpaceAccess.levelOf(participant));
    }

    public static AuthPrincipal principalOf(Participant participant) {
        return participant.isGuest()
            ? AuthPrincipal.guest(participant.getId(), participant.getSpace().getId())
            : AuthPrincipal.user(participant.getUser().getId());
    }

    public static Schedule weekdaySchedule(Space space) {
        Schedule schedule = new Schedule(space, "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;BYHOUR=6;BYMINUTE=0",
            "Africa/Lagos", LocalDate.of(2026, 11, 1));
        schedule.setId(UUID.randomUUID());
        schedule.setTitle("Morning Devotion");
        return schedule;
    }

    public static WatchSession session(Space space, Instant at) {
        WatchSession session = new WatchSession(space, null, at);
        session.setId(UUID.randomUUID());
        return session;
    }

    public static ContentItem video(Space space) {
        ContentItem video = new ContentItem(space, ContentItem.Source.YOUTUBE, "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        video.setId(UUID.randomUUID());
        video.setTitle("Morning Prayer");
        video.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z"));
        return video;
    }

    public static void assertApiError(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }
}

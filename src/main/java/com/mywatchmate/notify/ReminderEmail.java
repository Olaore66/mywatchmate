package com.mywatchmate.notify;

import com.mywatchmate.identity.User;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.WatchSession;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Writes the reminder email, with the start time in the reader's own timezone. */
public final class ReminderEmail {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);

    private ReminderEmail() {
    }

    public static EmailMessage render(User user, WatchSession session, String frontendUrl, Instant now) {
        Space space = session.getSpace();
        String what = session.getTitle() == null ? space.getTitle() : session.getTitle() + "\" in \"" + space.getTitle();
        ZoneId zone = ZoneId.of(user.getTimezone());
        String when = session.getScheduledAt().atZone(zone).format(WHEN) + " (" + zone.getId() + ")";
        String link = frontendUrl + "/spaces/" + space.getId();

        String subject = session.isLive()
            ? "Happening now: " + space.getTitle()
            : "Starting " + startsIn(Duration.between(now, session.getScheduledAt())) + ": " + space.getTitle();
        String text = """
            Hi %s,

            "%s" %s.

            When: %s
            Join: %s

            You're getting this because reminders are on for this room. You can change that in the room's settings.
            """.formatted(user.getDisplayName(), what,
            session.isLive() ? "has started" : "starts " + startsIn(Duration.between(now, session.getScheduledAt())),
            when, link);
        return new EmailMessage(user.getEmail(), subject, text);
    }

    /** "now", "in 1 minute", "in 10 minutes", "in 2 hours". */
    static String startsIn(Duration until) {
        long minutes = Math.max(0, Math.round(until.getSeconds() / 60.0));
        if (minutes == 0) {
            return "now";
        }
        if (minutes < 90) {
            return "in " + minutes + (minutes == 1 ? " minute" : " minutes");
        }
        long hours = Math.round(minutes / 60.0);
        return "in " + hours + (hours == 1 ? " hour" : " hours");
    }
}

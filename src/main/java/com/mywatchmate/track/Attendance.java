package com.mywatchmate.track;

import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.WatchSession;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "attendance")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attendance {

    @EmbeddedId
    private AttendanceId id;

    @MapsId("sessionId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private WatchSession session;

    @MapsId("participantId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id")
    private Participant participant;

    // First seen in the live session
    @Column(nullable = false)
    private Instant joinedAt = Instant.now();

    // Set to the last time they were seen when the session ends
    private Instant leftAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    @Column(nullable = false)
    private int watchedSeconds;

    // Watched enough of the session to count (decided when it ends)
    @Column(nullable = false)
    private boolean counted;

    public Attendance(WatchSession session, Participant participant, Instant now) {
        this.id = new AttendanceId(session.getId(), participant.getId());
        this.session = session;
        this.participant = participant;
        this.joinedAt = now;
        this.lastSeenAt = now;
    }

    /**
     * Seen again at {@code now}: credit the time since they were last seen, but never more than
     * {@code cap}, so a server pause or a long gap away isn't counted as watching.
     */
    public void seen(Instant now, Duration cap) {
        if (now.isAfter(lastSeenAt)) {
            Duration gap = Duration.between(lastSeenAt, now);
            watchedSeconds += (int) (gap.compareTo(cap) > 0 ? cap : gap).toSeconds();
            lastSeenAt = now;
        }
    }

    /** The session ended: did they watch enough of it? */
    public void finish(int requiredSeconds) {
        leftAt = lastSeenAt;
        counted = watchedSeconds > 0 && watchedSeconds >= requiredSeconds;
    }
}

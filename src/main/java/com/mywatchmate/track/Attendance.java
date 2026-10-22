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

    @Column(nullable = false)
    private Instant joinedAt = Instant.now();

    private Instant leftAt;

    @Column(nullable = false)
    private int watchedSeconds;

    public Attendance(WatchSession session, Participant participant) {
        this.id = new AttendanceId(session.getId(), participant.getId());
        this.session = session;
        this.participant = participant;
    }
}

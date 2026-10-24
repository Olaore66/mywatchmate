package com.mywatchmate.connect;

import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.WatchSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A message in a room's chat, kept permanently. Deleting only sets {@code deletedAt}: the app shows
 * "message deleted" and never the text. Tagged with the session that was live when it was sent, if any.
 */
@Entity
@Table(name = "chat_messages")
@IdClass(ChatMessageId.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    @Id
    private UUID id;

    @Id
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id")
    private Space space;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private WatchSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id")
    private Participant participant;

    @Column(nullable = false)
    private String body;

    private Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private Participant deletedBy;

    public ChatMessage(Space space, WatchSession session, Participant participant, String body, Instant now) {
        this.id = UUID.randomUUID();
        // Postgres stores microseconds; truncate so the in-memory key matches the stored one
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.space = space;
        this.session = session;
        this.participant = participant;
        this.body = body;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isFrom(Participant someone) {
        return participant.getId().equals(someone.getId());
    }

    /** Deleting twice keeps the first deletion. */
    public void delete(Instant now, Participant by) {
        if (deletedAt == null) {
            deletedAt = now;
            deletedBy = by;
        }
    }
}

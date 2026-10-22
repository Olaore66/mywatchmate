package com.mywatchmate.connect;

import com.mywatchmate.watch.Participant;
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

/** Kept permanently; a sender deleting a message only sets {@code deletedAt}. */
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
    @JoinColumn(name = "session_id")
    private WatchSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id")
    private Participant participant;

    @Column(nullable = false)
    private String body;

    private Instant deletedAt;

    public ChatMessage(WatchSession session, Participant participant, String body) {
        this.id = UUID.randomUUID();
        // Postgres stores microseconds; truncate so the in-memory key matches the stored one
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.session = session;
        this.participant = participant;
        this.body = body;
    }
}

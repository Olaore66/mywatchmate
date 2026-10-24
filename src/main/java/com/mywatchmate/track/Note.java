package com.mywatchmate.track;

import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.WatchSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "notes")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Note {

    public enum Visibility { PRIVATE, SHARED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id")
    private Space space;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private WatchSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id")
    private Participant author;

    // Where in the video it was written, e.g. 750 for "at 12:30"
    private Integer positionSeconds;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Visibility visibility = Visibility.PRIVATE;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public Note(Space space, Participant author, String body) {
        this.space = space;
        this.author = author;
        this.body = body;
    }

    public boolean isShared() {
        return visibility == Visibility.SHARED;
    }

    public boolean isBy(Participant participant) {
        return author.getId().equals(participant.getId());
    }
}

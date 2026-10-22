package com.mywatchmate.watch;

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

/** A pointer to content hosted elsewhere; WatchWithMe never hosts video. */
@Entity
@Table(name = "content_items")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentItem {

    public enum Source { YOUTUBE, LIVESTREAM, VIMEO, URL }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id")
    private Space space;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by")
    private Participant addedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Column(nullable = false)
    private String externalUrl;

    private String externalId;

    @Column(length = 300)
    private String title;

    // Null for live streams, and for YouTube until a player reports it
    private Integer durationSeconds;

    private String thumbnailUrl;

    @Column(name = "is_live", nullable = false)
    private boolean live;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public ContentItem(Space space, Source source, String externalUrl) {
        this.space = space;
        this.source = source;
        this.externalUrl = externalUrl;
    }
}

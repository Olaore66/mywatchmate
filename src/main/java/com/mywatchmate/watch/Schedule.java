package com.mywatchmate.watch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A recurring plan, e.g. "every weekday at 06:00 Africa/Lagos". */
@Entity
@Table(name = "schedules")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id")
    private Space space;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_item_id")
    private ContentItem contentItem;

    // RFC 5545 recurrence rule
    @Column(nullable = false, length = 500)
    private String rrule;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(nullable = false)
    private LocalDate startsOn;

    private LocalDate endsOn;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public Schedule(Space space, String rrule, String timezone, LocalDate startsOn) {
        this.space = space;
        this.rrule = rrule;
        this.timezone = timezone;
        this.startsOn = startsOn;
    }
}

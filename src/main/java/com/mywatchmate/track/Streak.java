package com.mywatchmate.track;

import com.mywatchmate.identity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/** One streak per user across all spaces, recomputed from attendance. Guests have none. */
@Entity
@Table(name = "streaks")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Streak {

    @Id
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private int currentCount;

    @Column(nullable = false)
    private int longestCount;

    // In the user's own timezone
    private LocalDate lastAttendedOn;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public Streak(User user) {
        this.user = user;
    }
}

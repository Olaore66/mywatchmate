package com.mywatchmate.watch;

import com.mywatchmate.identity.User;
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
@Table(name = "spaces")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Space {

    public enum AudienceType { DIASPORA, COUPLE, FRIENDS, WORK, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(nullable = false, length = 150)
    private String title;

    private String description;

    // The shareable link
    @Column(nullable = false, length = 64)
    private String inviteToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AudienceType audienceType;

    @Column(nullable = false)
    private boolean allowGuests = true;

    // When false, only the owner and admins can add videos
    @Column(nullable = false)
    private boolean membersCanAddContent;

    // When false, only the owner and admins can play, pause and seek for everyone
    @Column(nullable = false)
    private boolean membersCanControlPlayback;

    // What everyone in the room is watching
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_content_id")
    private ContentItem currentContent;

    private Instant archivedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public Space(User owner, String title, String inviteToken, AudienceType audienceType) {
        this.owner = owner;
        this.title = title;
        this.inviteToken = inviteToken;
        this.audienceType = audienceType;
    }

    /** Couples and friends are equals; diaspora, work and other rooms are led by their hosts. */
    public static boolean membersControlPlaybackByDefault(AudienceType type) {
        return type == AudienceType.COUPLE || type == AudienceType.FRIENDS;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void archive(Instant now) {
        if (archivedAt == null) {
            archivedAt = now;
        }
    }

    public void restore() {
        archivedAt = null;
    }

    /** The old link stops working immediately. */
    public void rotateInvite(String newInviteToken) {
        this.inviteToken = newInviteToken;
    }

    public void playNow(ContentItem content) {
        this.currentContent = content;
    }

    public void stopPlaying() {
        this.currentContent = null;
    }
}

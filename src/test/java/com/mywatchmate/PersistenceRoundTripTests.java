package com.mywatchmate;

import static org.assertj.core.api.Assertions.assertThat;

import com.mywatchmate.connect.ChatMessage;
import com.mywatchmate.connect.ChatMessageRepository;
import com.mywatchmate.give.CueDetection;
import com.mywatchmate.give.CueDetectionRepository;
import com.mywatchmate.give.Gift;
import com.mywatchmate.give.GiftRepository;
import com.mywatchmate.give.PaymentLink;
import com.mywatchmate.give.PaymentLinkRepository;
import com.mywatchmate.identity.User;
import com.mywatchmate.identity.UserRepository;
import com.mywatchmate.track.Attendance;
import com.mywatchmate.track.AttendanceRepository;
import com.mywatchmate.track.Streak;
import com.mywatchmate.track.StreakRepository;
import com.mywatchmate.watch.ContentItem;
import com.mywatchmate.watch.ContentItemRepository;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.ParticipantRepository;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceRepository;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/** Writes one row through every area against the real schema; rolled back afterwards. */
@SpringBootTest
@Transactional
class PersistenceRoundTripTests {

    @Autowired UserRepository users;
    @Autowired SpaceRepository spaces;
    @Autowired ParticipantRepository participants;
    @Autowired ContentItemRepository contentItems;
    @Autowired WatchSessionRepository sessions;
    @Autowired AttendanceRepository attendance;
    @Autowired StreakRepository streaks;
    @Autowired ChatMessageRepository chat;
    @Autowired PaymentLinkRepository paymentLinks;
    @Autowired CueDetectionRepository detections;
    @Autowired GiftRepository gifts;

    @Test
    void guestCanWatchChatAndGiveAnonymously() {
        User owner = users.save(new User("owner-" + UUID.randomUUID() + "@example.com", "Owner"));
        Space space = spaces.save(new Space(owner, "6am prayer", "tok-" + UUID.randomUUID(), Space.AudienceType.DIASPORA));
        Participant host = participants.save(Participant.member(space, owner, Participant.Role.OWNER));
        Participant guest = participants.save(Participant.guest(space, "Visitor", "hash-" + UUID.randomUUID()));

        ContentItem stream = contentItems.save(new ContentItem(space, ContentItem.Source.YOUTUBE, "https://youtube.com/watch?v=abc"));
        WatchSession session = sessions.save(new WatchSession(space, stream, Instant.now()));

        attendance.save(new Attendance(session, host));
        attendance.save(new Attendance(session, guest));
        streaks.save(new Streak(owner));

        chat.saveAndFlush(new ChatMessage(session, guest, "Amen!"));

        PaymentLink link = paymentLinks.save(new PaymentLink("https://payaza.example/pay/xyz", PaymentLink.Provider.PAYAZA));
        CueDetection cue = new CueDetection(session, CueDetection.CueType.QR, "https://payaza.example/pay/xyz", CueDetection.Status.SHOWN);
        cue.setPaymentLink(link);
        detections.save(cue);

        Gift gift = new Gift(cue, session, guest, link, true);
        gift.setAmountMinor(500_000L);
        gift.setCurrency("NGN");
        gift.setStatus(Gift.Status.COMPLETED);
        gifts.saveAndFlush(gift);

        assertThat(attendance.findBySessionId(session.getId())).hasSize(2);
        assertThat(chat.findBySessionIdOrderByCreatedAtDesc(session.getId(), PageRequest.of(0, 10)))
            .extracting(ChatMessage::getBody).containsExactly("Amen!");
        assertThat(gifts.findBySessionIdOrderByOpenedAtDesc(session.getId()))
            .singleElement().satisfies(g -> {
                assertThat(g.isAnonymous()).isTrue();
                assertThat(g.getParticipant().isGuest()).isTrue();
            });
    }
}

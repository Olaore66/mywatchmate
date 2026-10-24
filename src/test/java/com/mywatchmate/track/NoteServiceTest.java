package com.mywatchmate.track;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.guest;
import static com.mywatchmate.testsupport.Fixtures.member;
import static com.mywatchmate.testsupport.Fixtures.membership;
import static com.mywatchmate.testsupport.Fixtures.principalOf;
import static com.mywatchmate.testsupport.Fixtures.session;
import static com.mywatchmate.testsupport.Fixtures.space;
import static com.mywatchmate.testsupport.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mywatchmate.track.dto.NoteRequest;
import com.mywatchmate.track.dto.NoteResponse;
import com.mywatchmate.track.dto.UpdateNoteRequest;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.Space;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NoteServiceTest {

    @Mock NoteRepository notes;
    @Mock WatchSessionRepository sessions;
    @Mock SpaceAccess access;
    @Mock SpaceEvents events;

    private NoteService service;
    private Space space;
    private Participant ada;
    private Participant tunde;
    private Participant bisi;

    @BeforeEach
    void setUp() {
        service = new NoteService(notes, sessions, access, events);
        space = space(user("Ada"));
        ada = member(space, space.getOwner(), Participant.Role.OWNER);
        tunde = member(space, user("Tunde"), Participant.Role.MEMBER);
        bisi = guest(space, "Bisi");
        for (Participant p : List.of(ada, tunde, bisi)) {
            lenient().when(access.require(principalOf(p), space.getId())).thenReturn(membership(p));
        }
        lenient().when(notes.saveAndFlush(any(Note.class))).thenAnswer(i -> withId(i.getArgument(0)));
    }

    private static Note withId(Note note) {
        if (note.getId() == null) {
            ReflectionTestUtils.setField(note, "id", UUID.randomUUID());
        }
        return note;
    }

    private Note note(Participant author, Note.Visibility visibility) {
        Note note = withId(new Note(space, author, "Verse 4"));
        note.setVisibility(visibility);
        lenient().when(notes.findByIdAndSpaceId(note.getId(), space.getId())).thenReturn(Optional.of(note));
        return note;
    }

    @Nested
    class Writing {

        @Test
        void privateByDefaultAndTheRoomHearsNothing() {
            NoteResponse response = service.create(principalOf(tunde), space.getId(),
                new NoteRequest("  Come back to verse 4  ", null, null, 750));

            assertThat(response.body()).isEqualTo("Come back to verse 4");
            assertThat(response.visibility()).isEqualTo(Note.Visibility.PRIVATE);
            assertThat(response.positionSeconds()).isEqualTo(750);
            assertThat(response.author().displayName()).isEqualTo("Tunde");
            verifyNoInteractions(events);
        }

        @Test
        void sharedNotesAppearForEveryoneLive() {
            NoteResponse response = service.create(principalOf(bisi), space.getId(),
                new NoteRequest("Amen", Note.Visibility.SHARED, null, null));

            verify(events).publish(space.getId(), SpaceEvent.Type.NOTE_ADDED, response);
            assertThat(response.author().displayName()).isEqualTo("Bisi");
        }

        @Test
        void aNoteCanBeAboutOneOfTheRoomsSessions() {
            WatchSession sitting = session(space, MONDAY_MIDNIGHT);
            when(sessions.findByIdAndSpaceId(sitting.getId(), space.getId())).thenReturn(Optional.of(sitting));

            NoteResponse response = service.create(principalOf(tunde), space.getId(),
                new NoteRequest("Good one", null, sitting.getId(), null));

            assertThat(response.sessionId()).isEqualTo(sitting.getId());
        }

        @Test
        void butNotAboutAnotherRoomsSession() {
            UUID elsewhere = UUID.randomUUID();
            when(sessions.findByIdAndSpaceId(elsewhere, space.getId())).thenReturn(Optional.empty());

            assertApiError(() -> service.create(principalOf(tunde), space.getId(),
                new NoteRequest("x", null, elsewhere, null)), HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND");
            verify(notes, never()).saveAndFlush(any());
        }

        @Test
        void archivedRoomsTakeNoNewNotes() {
            space.archive(MONDAY_MIDNIGHT);
            assertApiError(() -> service.create(principalOf(ada), space.getId(), new NoteRequest("x", null, null, null)),
                HttpStatus.CONFLICT, "SPACE_ARCHIVED");
        }
    }

    @Nested
    class Reading {

        @Test
        void sharedNotesPlusMyPrivateOnes() {
            Note shared = note(ada, Note.Visibility.SHARED);
            Note mine = note(tunde, Note.Visibility.PRIVATE);
            when(notes.findVisible(space.getId(), tunde.getId(), Note.Visibility.SHARED)).thenReturn(List.of(mine, shared));

            assertThat(service.list(principalOf(tunde), space.getId(), null, false))
                .extracting(NoteResponse::id).containsExactly(mine.getId(), shared.getId());
        }

        @Test
        void forOneSession() {
            UUID sessionId = UUID.randomUUID();
            when(notes.findVisibleForSession(space.getId(), sessionId, tunde.getId(), Note.Visibility.SHARED))
                .thenReturn(List.of());

            assertThat(service.list(principalOf(tunde), space.getId(), sessionId, false)).isEmpty();
        }

        @Test
        void onlyMineOptionallyForOneSession() {
            WatchSession sitting = session(space, MONDAY_MIDNIGHT);
            Note aboutIt = note(tunde, Note.Visibility.PRIVATE);
            aboutIt.setSession(sitting);
            Note general = note(tunde, Note.Visibility.SHARED);
            Note aboutAnother = note(tunde, Note.Visibility.PRIVATE);
            aboutAnother.setSession(session(space, MONDAY_MIDNIGHT));
            when(notes.findByAuthorIdOrderByCreatedAtDesc(tunde.getId()))
                .thenReturn(List.of(aboutIt, general, aboutAnother));

            assertThat(service.list(principalOf(tunde), space.getId(), null, true)).hasSize(3);
            assertThat(service.list(principalOf(tunde), space.getId(), sitting.getId(), true))
                .extracting(NoteResponse::id).containsExactly(aboutIt.getId());
        }
    }

    @Nested
    class Editing {

        @Test
        void authorsEditTheirSharedNoteAndTheRoomSeesTheChange() {
            Note note = note(tunde, Note.Visibility.SHARED);

            NoteResponse response = service.update(principalOf(tunde), space.getId(), note.getId(),
                new UpdateNoteRequest(" Verse 5 ", null, 800));

            assertThat(note.getBody()).isEqualTo("Verse 5");
            assertThat(note.getPositionSeconds()).isEqualTo(800);
            verify(events).publish(space.getId(), SpaceEvent.Type.NOTE_UPDATED, response);
        }

        @Test
        void sharingAPrivateNoteShowsItToTheRoom() {
            Note note = note(tunde, Note.Visibility.PRIVATE);

            NoteResponse response = service.update(principalOf(tunde), space.getId(), note.getId(),
                new UpdateNoteRequest(null, Note.Visibility.SHARED, null));

            verify(events).publish(space.getId(), SpaceEvent.Type.NOTE_ADDED, response);
        }

        @Test
        void makingASharedNotePrivateTakesItOffEveryoneElsesScreen() {
            Note note = note(tunde, Note.Visibility.SHARED);

            service.update(principalOf(tunde), space.getId(), note.getId(),
                new UpdateNoteRequest(null, Note.Visibility.PRIVATE, null));

            verify(events).publish(space.getId(), SpaceEvent.Type.NOTE_DELETED, Map.of("noteId", note.getId()));
        }

        @Test
        void editingAPrivateNoteTellsNobody() {
            Note note = note(tunde, Note.Visibility.PRIVATE);

            service.update(principalOf(tunde), space.getId(), note.getId(), new UpdateNoteRequest("New", null, null));

            assertThat(note.getBody()).isEqualTo("New");
            verifyNoInteractions(events);
        }

        @Test
        void nobodyElseEditsYourNoteNotEvenTheOwner() {
            Note note = note(tunde, Note.Visibility.SHARED);

            assertApiError(() -> service.update(principalOf(ada), space.getId(), note.getId(),
                new UpdateNoteRequest("Mine now", null, null)), HttpStatus.FORBIDDEN, "NOT_YOUR_NOTE");
            assertThat(note.getBody()).isEqualTo("Verse 4");
        }
    }

    @Nested
    class Privacy {

        @Test
        void someoneElsesPrivateNoteDoesntExistEvenForTheOwner() {
            Note secret = note(tunde, Note.Visibility.PRIVATE);

            assertApiError(() -> service.update(principalOf(ada), space.getId(), secret.getId(),
                new UpdateNoteRequest("x", null, null)), HttpStatus.NOT_FOUND, "NOTE_NOT_FOUND");
            assertApiError(() -> service.delete(principalOf(ada), space.getId(), secret.getId()),
                HttpStatus.NOT_FOUND, "NOTE_NOT_FOUND");
            verify(notes, never()).delete(any());
        }

        @Test
        void aNoteFromAnotherRoomIsNotFound() {
            UUID elsewhere = UUID.randomUUID();
            when(notes.findByIdAndSpaceId(elsewhere, space.getId())).thenReturn(Optional.empty());

            assertApiError(() -> service.delete(principalOf(tunde), space.getId(), elsewhere),
                HttpStatus.NOT_FOUND, "NOTE_NOT_FOUND");
        }
    }

    @Nested
    class Deleting {

        @Test
        void authorsDeleteTheirOwnAndSharedOnesDisappearForEveryone() {
            Note note = note(bisi, Note.Visibility.SHARED);

            service.delete(principalOf(bisi), space.getId(), note.getId());

            verify(notes).delete(note);
            verify(events).publish(space.getId(), SpaceEvent.Type.NOTE_DELETED, Map.of("noteId", note.getId()));
        }

        @Test
        void deletingAPrivateNoteTellsNobody() {
            Note note = note(tunde, Note.Visibility.PRIVATE);

            service.delete(principalOf(tunde), space.getId(), note.getId());

            verify(notes).delete(note);
            verifyNoInteractions(events);
        }

        @Test
        void hostsCanRemoveSharedNotesMembersCant() {
            Note note = note(bisi, Note.Visibility.SHARED);

            assertApiError(() -> service.delete(principalOf(tunde), space.getId(), note.getId()),
                HttpStatus.FORBIDDEN, "NOT_YOUR_NOTE");
            service.delete(principalOf(ada), space.getId(), note.getId());

            ArgumentCaptor<Note> deleted = ArgumentCaptor.forClass(Note.class);
            verify(notes).delete(deleted.capture());
            assertThat(deleted.getValue()).isSameAs(note);
            verify(events).publish(eq(space.getId()), eq(SpaceEvent.Type.NOTE_DELETED), any());
        }
    }
}

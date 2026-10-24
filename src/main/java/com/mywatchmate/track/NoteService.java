package com.mywatchmate.track;

import com.mywatchmate.auth.AuthPrincipal;
import com.mywatchmate.common.ApiException;
import com.mywatchmate.track.dto.NoteRequest;
import com.mywatchmate.track.dto.NoteResponse;
import com.mywatchmate.track.dto.UpdateNoteRequest;
import com.mywatchmate.watch.SpaceAccess;
import com.mywatchmate.watch.SpaceAccess.Membership;
import com.mywatchmate.watch.SpaceEvent;
import com.mywatchmate.watch.SpaceEvents;
import com.mywatchmate.watch.WatchSession;
import com.mywatchmate.watch.WatchSessionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notes in a room. PRIVATE notes are seen by their author only (not even the owner); SHARED notes
 * by the whole room, which hears about them live. Authors edit and delete their own notes;
 * the owner and admins may also delete shared ones.
 */
@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteRepository notes;
    private final WatchSessionRepository sessions;
    private final SpaceAccess access;
    private final SpaceEvents events;

    /** Shared notes plus the caller's private ones, newest first; {@code mine} for the caller's only. */
    @Transactional(readOnly = true)
    public List<NoteResponse> list(AuthPrincipal principal, UUID spaceId, UUID sessionId, boolean mine) {
        Membership membership = access.require(principal, spaceId);
        UUID me = membership.participant().getId();
        List<Note> found;
        if (mine) {
            found = notes.findByAuthorIdOrderByCreatedAtDesc(me).stream()
                .filter(n -> sessionId == null || (n.getSession() != null && n.getSession().getId().equals(sessionId)))
                .toList();
        } else if (sessionId != null) {
            found = notes.findVisibleForSession(spaceId, sessionId, me, Note.Visibility.SHARED);
        } else {
            found = notes.findVisible(spaceId, me, Note.Visibility.SHARED);
        }
        return found.stream().map(NoteResponse::from).toList();
    }

    @Transactional
    public NoteResponse create(AuthPrincipal principal, UUID spaceId, NoteRequest request) {
        Membership membership = access.require(principal, spaceId).requireNotArchived();
        Note note = new Note(membership.space(), membership.participant(), request.body().trim());
        note.setVisibility(request.visibility() != null ? request.visibility() : Note.Visibility.PRIVATE);
        note.setSession(sessionIn(spaceId, request.sessionId()));
        note.setPositionSeconds(request.positionSeconds());
        NoteResponse response = NoteResponse.from(notes.saveAndFlush(note));
        if (note.isShared()) {
            events.publish(spaceId, SpaceEvent.Type.NOTE_ADDED, response);
        }
        return response;
    }

    /** Authors only. Making a note shared or private tells the room it appeared or went away. */
    @Transactional
    public NoteResponse update(AuthPrincipal principal, UUID spaceId, UUID noteId, UpdateNoteRequest request) {
        Membership membership = access.require(principal, spaceId).requireNotArchived();
        Note note = visibleNote(membership, spaceId, noteId);
        if (!note.isBy(membership.participant())) {
            throw ApiException.forbidden("NOT_YOUR_NOTE", "Only the person who wrote this note can edit it.");
        }
        boolean wasShared = note.isShared();
        if (request.body() != null) {
            note.setBody(request.body().trim());
        }
        if (request.visibility() != null) {
            note.setVisibility(request.visibility());
        }
        if (request.positionSeconds() != null) {
            note.setPositionSeconds(request.positionSeconds());
        }
        NoteResponse response = NoteResponse.from(notes.saveAndFlush(note));
        if (wasShared && note.isShared()) {
            events.publish(spaceId, SpaceEvent.Type.NOTE_UPDATED, response);
        } else if (note.isShared()) {
            events.publish(spaceId, SpaceEvent.Type.NOTE_ADDED, response);
        } else if (wasShared) {
            events.publish(spaceId, SpaceEvent.Type.NOTE_DELETED, Map.of("noteId", noteId));
        }
        return response;
    }

    /** The author, or the owner/admins for a shared note (moderation). */
    @Transactional
    public void delete(AuthPrincipal principal, UUID spaceId, UUID noteId) {
        Membership membership = access.require(principal, spaceId);
        Note note = visibleNote(membership, spaceId, noteId);
        if (!note.isBy(membership.participant()) && !membership.isOwnerOrAdmin()) {
            throw ApiException.forbidden("NOT_YOUR_NOTE",
                "Only the person who wrote this note, the owner or admins can delete it.");
        }
        notes.delete(note);
        if (note.isShared()) {
            events.publish(spaceId, SpaceEvent.Type.NOTE_DELETED, Map.of("noteId", noteId));
        }
    }

    /** Someone else's private note doesn't exist as far as the caller can tell. */
    private Note visibleNote(Membership membership, UUID spaceId, UUID noteId) {
        return notes.findByIdAndSpaceId(noteId, spaceId)
            .filter(n -> n.isShared() || n.isBy(membership.participant()))
            .orElseThrow(() -> ApiException.notFound("NOTE_NOT_FOUND", "That note doesn't exist or isn't yours."));
    }

    private WatchSession sessionIn(UUID spaceId, UUID sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessions.findByIdAndSpaceId(sessionId, spaceId)
            .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "That session isn't in this room."));
    }
}

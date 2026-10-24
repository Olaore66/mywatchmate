package com.mywatchmate.track.dto;

import com.mywatchmate.track.Note;
import com.mywatchmate.watch.Participant;
import com.mywatchmate.watch.dto.PersonRef;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A note in a room. It's yours when author.id is your myParticipantId.")
public record NoteResponse(
    UUID id,
    UUID spaceId,
    @Schema(nullable = true) UUID sessionId,
    @Schema(description = "Participant id and name") PersonRef author,
    @Schema(example = "Psalm 23: come back to verse 4") String body,
    Note.Visibility visibility,
    @Schema(example = "750", nullable = true) Integer positionSeconds,
    Instant createdAt,
    Instant updatedAt
) {
    public static NoteResponse from(Note note) {
        Participant author = note.getAuthor();
        return new NoteResponse(note.getId(), note.getSpace().getId(),
            note.getSession() == null ? null : note.getSession().getId(),
            new PersonRef(author.getId(), author.displayName()), note.getBody(), note.getVisibility(),
            note.getPositionSeconds(), note.getCreatedAt(), note.getUpdatedAt());
    }
}

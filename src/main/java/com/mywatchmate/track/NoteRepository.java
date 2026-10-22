package com.mywatchmate.track;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteRepository extends JpaRepository<Note, UUID> {

    List<Note> findBySpaceIdAndVisibilityOrderByCreatedAtDesc(UUID spaceId, Note.Visibility visibility);

    List<Note> findByAuthorIdOrderByCreatedAtDesc(UUID authorId);
}

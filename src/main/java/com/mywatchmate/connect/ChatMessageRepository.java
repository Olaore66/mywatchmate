package com.mywatchmate.connect;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, ChatMessageId> {

    List<ChatMessage> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable page);

    /** The newest messages in a room, newest first. */
    @Query(value = """
        select cast(id as varchar) from chat_messages
        where space_id = :spaceId
        order by created_at desc, id desc
        limit :limit
        """, nativeQuery = true)
    List<String> findLatestIds(UUID spaceId, int limit);

    /** The messages just older than the cursor (created_at, id), newest first. */
    @Query(value = """
        select cast(id as varchar) from chat_messages
        where space_id = :spaceId and (created_at, id) < (:beforeAt, :beforeId)
        order by created_at desc, id desc
        limit :limit
        """, nativeQuery = true)
    List<String> findIdsBefore(UUID spaceId, Instant beforeAt, UUID beforeId, int limit);

    /** Those messages with their senders, oldest first (the order a chat is read in). */
    @Query("""
        select m from ChatMessage m join fetch m.participant p left join fetch p.user
        where m.space.id = :spaceId and m.id in :ids
        order by m.createdAt, m.id
        """)
    List<ChatMessage> findWithSenders(UUID spaceId, Collection<UUID> ids);

    @Query("""
        select m from ChatMessage m join fetch m.participant p left join fetch p.user
        where m.space.id = :spaceId and m.id = :id
        """)
    Optional<ChatMessage> findInSpace(UUID spaceId, UUID id);

    /** Messages from others, not deleted, since the participant last read the chat. */
    @Query("""
        select count(m) from ChatMessage m
        where m.space.id = :spaceId and m.participant.id <> :participantId
          and m.deletedAt is null and m.createdAt > :since
        """)
    long countUnread(UUID spaceId, UUID participantId, Instant since);
}

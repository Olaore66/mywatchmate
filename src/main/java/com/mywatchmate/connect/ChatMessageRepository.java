package com.mywatchmate.connect;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, ChatMessageId> {

    List<ChatMessage> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable page);
}

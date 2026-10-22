package com.mywatchmate.connect;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** chat_messages is partitioned by created_at, so it must be part of the key. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ChatMessageId implements Serializable {

    private UUID id;

    private Instant createdAt;
}

package com.mywatchmate.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

class StompErrorHandlerTest {

    private final StompErrorHandler handler = new StompErrorHandler();

    @Test
    void theRealReasonReplacesSpringsGenericWrapper() {
        Throwable wrapped = new MessageDeliveryException(clientFrame(),
            "Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]",
            new AccessDeniedException("Not allowed to subscribe to /topic/spaces/x/events"));

        Message<byte[]> error = handler.handleClientMessageProcessingError(clientFrame(), wrapped);

        StompHeaderAccessor headers = StompHeaderAccessor.wrap(error);
        assertThat(headers.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(headers.getMessage()).isEqualTo("Not allowed to subscribe to /topic/spaces/x/events");
    }

    @Test
    void deeplyNestedReasonsAreFoundToo() {
        Throwable wrapped = new RuntimeException("outer",
            new IllegalStateException("middle", new AccessDeniedException("Invalid or expired token")));

        Message<byte[]> error = handler.handleClientMessageProcessingError(clientFrame(), wrapped);

        assertThat(StompHeaderAccessor.wrap(error).getMessage()).isEqualTo("Invalid or expired token");
    }

    @Test
    void otherErrorsKeepTheirOwnMessage() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(clientFrame(), new IllegalStateException("Broker unavailable"));

        assertThat(StompHeaderAccessor.wrap(error).getMessage()).isEqualTo("Broker unavailable");
    }

    private static Message<byte[]> clientFrame() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}

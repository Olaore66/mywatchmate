package com.mywatchmate.config;

import org.springframework.messaging.Message;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * Puts the real reason ("Invalid or expired token", "Not allowed to subscribe to …") in the
 * STOMP ERROR frame's {@code message} header, instead of Spring's generic
 * "Failed to send message to ExecutorSubscribableChannel".
 */
public class StompErrorHandler extends StompSubProtocolErrorHandler {

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        Throwable cause = ex;
        while (cause != null && !(cause instanceof AccessDeniedException)) {
            cause = cause.getCause();
        }
        return super.handleClientMessageProcessingError(clientMessage, cause != null ? cause : ex);
    }
}

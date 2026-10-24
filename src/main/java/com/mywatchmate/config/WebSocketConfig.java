package com.mywatchmate.config;

import com.mywatchmate.live.ConnectionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket for playback sync, chat and the Give button.
 * Clients send to /app/**, subscribe to /topic/spaces/{id}/**.
 * The in-memory broker only works on a single instance; swap it for a broker
 * relay (RabbitMQ) or Redis fan-out before running more than one.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final long HEARTBEAT_MS = 10_000;

    private final WebSocketAuthInterceptor authInterceptor;
    private final ConnectionRegistry connections;

    @Value("${mywatchmate.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
        registry.setErrorHandler(new StompErrorHandler());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Heartbeats every 10 s both ways (stompjs sends them by default), so a phone that lost signal
        // is noticed and drops off the room's online list within about half a minute
        registry.enableSimpleBroker("/topic", "/queue")
            .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
            .setTaskScheduler(heartbeats());
        // Deliver each client's messages in the order they were sent (CONTENT_ADDED before
        // NOW_PLAYING_CHANGED, MEMBER_JOINED before MEMBER_LEFT); Spring's default thread pool may reorder them
        registry.setPreservePublishOrder(true);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    /** Lets the server close a connection, e.g. of someone removed from the room they're watching. */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(connections);
    }

    /** Kept out of the context so it doesn't become the scheduler for the background jobs. */
    private static TaskScheduler heartbeats() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}

package com.mywatchmate.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html, OpenAPI JSON at /v3/api-docs. */
@Configuration
public class OpenApiConfig {

    /** Name of the security scheme; reference it with {@code @SecurityRequirement(name = BEARER)}. */
    public static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI mywatchmateOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("MyWatchMate API")
                .version("v1")
                .description("""
                    Backend API for MyWatchMate (WatchWithMe).

                    ### Trying it here
                    1. Call **POST /api/v1/auth/register** or **/login**, copy `accessToken` from the response.
                    2. Click **Authorize** (top right) and paste the token (without the word "Bearer").
                    3. Locked endpoints now work until the token expires (15 minutes).

                    ### Errors
                    Every error is `application/problem+json` with a stable `code` to switch on, e.g.
                    `{"status":409,"title":"Conflict","detail":"An account with this email already exists.","code":"EMAIL_TAKEN"}`.
                    Validation errors add `errors`: `{"email":"must be a well-formed email address"}`.

                    ### Real-time
                    Live features use STOMP over WebSocket at `/ws`. Send `Authorization: Bearer <accessToken>`
                    as a header on the STOMP **CONNECT** frame, then subscribe to `/topic/spaces/{spaceId}/events`
                    (room members only). Every event is `{"type","spaceId","at","data"}`. Types: MEMBER_JOINED,
                    MEMBER_LEFT, MEMBER_REMOVED, ROLE_CHANGED, OWNERSHIP_TRANSFERRED, SPACE_UPDATED, SPACE_ARCHIVED,
                    SPACE_RESTORED, CONTENT_ADDED, CONTENT_REMOVED, NOW_PLAYING_CHANGED, SCHEDULE_UPDATED,
                    SESSION_SCHEDULED, SESSION_STARTED, SESSION_ENDED, SESSION_CANCELLED.
                    Full payloads: `docs/realtime-events.md` in the backend repository.
                    """))
            .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Access token from /auth/login, /auth/register, /auth/refresh or /auth/guest")));
    }
}

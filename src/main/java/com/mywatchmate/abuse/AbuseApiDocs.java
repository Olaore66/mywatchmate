package com.mywatchmate.abuse;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Locale;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Adds the 429 to every endpoint a limit applies to, from the same settings the limits use. */
@Component
public class AbuseApiDocs implements OpenApiCustomizer {

    static final String DESCRIPTION = """
        TOO_MANY_ATTEMPTS: too many requests from this address (or, for sign-in, too many wrong passwords for this \
        account). Wait the seconds in the Retry-After header and try again; it clears by itself.""";

    private final AbuseProperties props;

    public AbuseApiDocs(AbuseProperties props) {
        this.props = props;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (!props.enabled() || openApi.getPaths() == null) {
            return;
        }
        for (Map.Entry<String, PathItem> path : openApi.getPaths().entrySet()) {
            PathContainer container = PathContainer.parsePath(path.getKey());
            for (AbuseProperties.Rule rule : props.rules()) {
                boolean matches = rule.paths().stream()
                    .map(PathPatternParser.defaultInstance::parse)
                    .anyMatch((PathPattern p) -> p.matches(container));
                if (!matches) {
                    continue;
                }
                path.getValue().readOperationsMap().forEach((method, operation) -> {
                    if (rule.method() == null || rule.method().toUpperCase(Locale.ROOT).equals(method.name())) {
                        add429(operation);
                    }
                });
            }
        }
    }

    private static void add429(Operation operation) {
        if (operation.getResponses() == null) {
            return;
        }
        ApiResponse response = operation.getResponses().get("429");
        if (response == null) {
            response = new ApiResponse()
                .description(DESCRIPTION)
                .content(new Content().addMediaType("application/problem+json",
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"))));
            operation.getResponses().addApiResponse("429", response);
        }
        // Also on a 429 the endpoint documents itself (e.g. sign-in, with its own wording)
        if (response.getHeaders() == null || !response.getHeaders().containsKey("Retry-After")) {
            response.addHeaderObject("Retry-After", new Header().description("Seconds to wait").schema(new IntegerSchema()));
        }
    }
}

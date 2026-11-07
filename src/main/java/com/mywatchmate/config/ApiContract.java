package com.mywatchmate.config;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.OpenAPI;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Makes the OpenAPI document something the frontend can generate good types from:
 * <ul>
 *   <li><b>Stable operation ids</b>: {@code <controller><Method>} (e.g. {@code chatHistory}), so they don't
 *       renumber ({@code list_3} → {@code list_4}) whenever an endpoint is added elsewhere.</li>
 *   <li><b>Required response fields</b>: every field of a response record is listed as required unless it's
 *       marked {@code @Schema(nullable = true)}, so generated types only make the truly optional ones optional.
 *       Requests are left alone: their required fields come from validation ({@code @NotNull}, {@code @NotBlank}).</li>
 * </ul>
 */
@Configuration
public class ApiContract {

    private static final String BASE_PACKAGE = "com.mywatchmate";

    @Bean
    OperationCustomizer stableOperationIds() {
        return (operation, handler) -> {
            operation.setOperationId(operationId(handler.getBeanType().getSimpleName(), handler.getMethod().getName()));
            return operation;
        };
    }

    static String operationId(String controller, String method) {
        String prefix = controller.replaceFirst("Controllers?$", "");
        return prefix.substring(0, 1).toLowerCase(Locale.ROOT) + prefix.substring(1)
            + method.substring(0, 1).toUpperCase(Locale.ROOT) + method.substring(1);
    }

    @Bean
    OpenApiCustomizer requiredResponseFields() {
        Map<String, Class<?>> records = responseRecords();
        return openApi -> markRequired(openApi, records);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void markRequired(OpenAPI openApi, Map<String, Class<?>> records) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        openApi.getComponents().getSchemas().forEach((name, schema) -> {
            Class<?> type = records.get(name);
            Map<String, io.swagger.v3.oas.models.media.Schema> properties = schema.getProperties();
            if (type == null || properties == null) {
                return;
            }
            List<String> required = new ArrayList<>(schema.getRequired() == null ? List.of() : schema.getRequired());
            for (String field : alwaysPresent(type)) {
                if (properties.containsKey(field) && !required.contains(field)) {
                    required.add(field);
                }
            }
            schema.setRequired(required);
        });
    }

    /** The record's components that are never null: primitives, and anything not marked nullable. */
    static List<String> alwaysPresent(Class<?> record) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            Schema schema = component.getAccessor().getAnnotation(Schema.class);
            if (schema == null) {
                schema = component.getAnnotation(Schema.class);
            }
            boolean nullable = schema != null && schema.nullable();
            if (component.getType().isPrimitive() || !nullable) {
                names.add(component.getName());
            }
        }
        return names;
    }

    /**
     * Our response records by schema name (springdoc names schemas by simple class name). Requests and
     * commands are left out, as are names two records share (we couldn't tell which one a schema is).
     */
    static Map<String, Class<?>> responseRecords() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition bean) {
                return bean.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Record.class));
        Map<String, Class<?>> found = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (BeanDefinition bean : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type = ClassUtils.resolveClassName(bean.getBeanClassName(), ApiContract.class.getClassLoader());
            String name = type.getSimpleName();
            if (name.endsWith("Request") || name.endsWith("Command")) {
                continue;
            }
            if (found.putIfAbsent(name, type) != null) {
                ambiguous.add(name);
            }
        }
        ambiguous.forEach(found::remove);
        return found;
    }
}

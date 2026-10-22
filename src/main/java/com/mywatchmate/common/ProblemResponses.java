package com.mywatchmate.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes problem+json for errors raised in the security filters, before any controller
 * (and so {@link GlobalExceptionHandler}) is involved.
 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static void write(HttpServletRequest request, HttpServletResponse response,
                             HttpStatus status, String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\""
            + ",\"title\":\"" + json(status.getReasonPhrase()) + "\""
            + ",\"status\":" + status.value()
            + ",\"detail\":\"" + json(detail) + "\""
            + ",\"instance\":\"" + json(request.getRequestURI()) + "\""
            + ",\"code\":\"" + json(code) + "\"}");
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}

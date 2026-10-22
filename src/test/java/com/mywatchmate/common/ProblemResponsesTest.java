package com.mywatchmate.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ProblemResponsesTest {

    @Test
    void writesProblemJsonWithStatusAndCode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ProblemResponses.write(new MockHttpServletRequest("GET", "/api/v1/me"), response,
            HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in required.");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        DocumentContext json = JsonPath.parse(response.getContentAsString());
        assertThat(json.read("$.status", Integer.class)).isEqualTo(401);
        assertThat(json.read("$.title", String.class)).isEqualTo("Unauthorized");
        assertThat(json.read("$.detail", String.class)).isEqualTo("Sign in required.");
        assertThat(json.read("$.instance", String.class)).isEqualTo("/api/v1/me");
        assertThat(json.read("$.code", String.class)).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void escapesCharactersThatWouldBreakJson() throws Exception {
        String nasty = "He said \"no\"\nthen \\ left\t\u0001, Olúwa ✓";
        MockHttpServletResponse response = new MockHttpServletResponse();

        ProblemResponses.write(new MockHttpServletRequest("GET", "/x\"y"), response, HttpStatus.FORBIDDEN, "FORBIDDEN", nasty);

        DocumentContext json = JsonPath.parse(response.getContentAsString());
        assertThat(json.read("$.detail", String.class)).isEqualTo(nasty);
        assertThat(json.read("$.instance", String.class)).isEqualTo("/x\"y");
    }
}

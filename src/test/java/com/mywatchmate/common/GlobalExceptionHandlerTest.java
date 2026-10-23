package com.mywatchmate.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void apiExceptionKeepsStatusMessageAndCode() {
        ResponseEntity<ProblemDetail> response =
            handler.handleApi(ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProblemDetail body = response.getBody();
        assertThat(body.getStatus()).isEqualTo(409);
        assertThat(body.getTitle()).isEqualTo("Conflict");
        assertThat(body.getDetail()).isEqualTo("An account with this email already exists.");
        assertThat(body.getProperties()).containsEntry("code", "EMAIL_TAKEN");
    }

    @Test
    void factoryMethodsMapToTheRightStatus() {
        assertThat(ApiException.badRequest("C", "m").getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ApiException.unauthorized("C", "m").getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ApiException.forbidden("C", "m").getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ApiException.notFound("C", "m").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ApiException.conflict("C", "m").getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void validationErrorsListOneMessagePerField() throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "request");
        result.addError(new FieldError("request", "email", "must be a well-formed email address"));
        result.addError(new FieldError("request", "email", "must not be blank"));
        result.addError(new FieldError("request", "password", "size must be between 8 and 72"));
        MethodParameter parameter = new MethodParameter(getClass().getDeclaredMethod("endpoint", Object.class), 0);

        ResponseEntity<Object> response = handler.handleException(
            new MethodArgumentNotValidException(parameter, result), new ServletWebRequest(new MockHttpServletRequest()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body.getProperties()).containsEntry("code", "VALIDATION_FAILED");
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) body.getProperties().get("errors");
        assertThat(errors).containsExactly(
            Map.entry("email", "must be a well-formed email address"),
            Map.entry("password", "size must be between 8 and 72"));
    }

    @Test
    void unexpectedErrorsDoNotLeakInternals() {
        ResponseEntity<ProblemDetail> response =
            handler.handleUnexpected(new IllegalStateException("connection to db-prod.internal failed, password=hunter2"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).doesNotContain("hunter2", "db-prod");
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    @Test
    void springsOwnErrorsGetACodeToo() throws Exception {
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());

        ResponseEntity<Object> unreadable = handler.handleException(new HttpMessageNotReadableException("bad json",
            new MockHttpInputMessage(new byte[0])), request);
        ResponseEntity<Object> wrongMethod = handler.handleException(new HttpRequestMethodNotSupportedException("DELETE"), request);
        ResponseEntity<Object> wrongType = handler.handleException(
            new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, java.util.List.of(MediaType.APPLICATION_JSON)), request);

        assertThat(unreadable.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ProblemDetail) unreadable.getBody()).getProperties()).containsEntry("code", "MALFORMED_REQUEST");
        assertThat(((ProblemDetail) wrongMethod.getBody()).getProperties()).containsEntry("code", "METHOD_NOT_ALLOWED");
        assertThat(((ProblemDetail) wrongType.getBody()).getProperties()).containsEntry("code", "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void everyStatusMapsToACode() {
        assertThat(GlobalExceptionHandler.codeFor(HttpStatus.NOT_FOUND)).isEqualTo("NOT_FOUND");
        assertThat(GlobalExceptionHandler.codeFor(HttpStatus.NOT_ACCEPTABLE)).isEqualTo("NOT_ACCEPTABLE");
        assertThat(GlobalExceptionHandler.codeFor(HttpStatus.PAYLOAD_TOO_LARGE)).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(GlobalExceptionHandler.codeFor(HttpStatus.SERVICE_UNAVAILABLE)).isEqualTo("HTTP_503");
    }

    @SuppressWarnings("unused")
    private void endpoint(Object request) {
        // Only here to give MethodArgumentNotValidException a real method parameter
    }
}

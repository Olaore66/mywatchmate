package com.mywatchmate.give;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks Payaza how a checkout payment ended, by the reference we gave the checkout. Authenticated with the church's
 * PUBLIC key, as Payaza documents for its transaction status query: we never hold a key that can move money.
 * Anything unclear (Payaza down, a shape we don't recognise) counts as "not finished yet", so it's checked again.
 */
@Slf4j
@Component
public class PayazaClient {

    static final String STATUS_PATH = "/merchant-collection/transfer_notification_controller/merchant/transaction-query";

    public enum Outcome { COMPLETED, FAILED, PENDING, NOT_FOUND }

    /** What Payaza said. Amount in minor units (kobo, cents); null when it didn't say. */
    public record Payment(Outcome outcome, Long amountMinor, String currency, String payazaReference) {

        static Payment pending() {
            return new Payment(Outcome.PENDING, null, null, null);
        }
    }

    private final RestClient http;

    @Autowired
    public PayazaClient(PayazaProperties props) {
        this(RestClient.builder().baseUrl(props.apiBase()).requestFactory(factory(props)).build());
    }

    PayazaClient(RestClient http) {
        this.http = http;
    }

    public Payment status(String publicKey, PayazaAccount.Mode mode, String reference) {
        try {
            Map<?, ?> body = http.get()
                .uri(uri -> uri.path(STATUS_PATH).queryParam("merchant_reference", reference).build())
                .header(HttpHeaders.AUTHORIZATION, "Payaza " + Base64.getEncoder().encodeToString(publicKey.getBytes(StandardCharsets.UTF_8)))
                .header("X-TenantID", mode == PayazaAccount.Mode.TEST ? "test" : "live")
                .retrieve()
                .body(Map.class);
            return read(body);
        } catch (HttpClientErrorException.NotFound e) {
            return new Payment(Outcome.NOT_FOUND, null, null, null);
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("Payaza status check for {} didn't work ({}); will try again", reference, e.getMessage());
            return Payment.pending();
        }
    }

    static Payment read(Map<?, ?> body) {
        if (body == null || !(body.get("data") instanceof Map<?, ?> data)) {
            return body != null && Boolean.FALSE.equals(body.get("success"))
                ? new Payment(Outcome.NOT_FOUND, null, null, null) : Payment.pending();
        }
        String status = text(data.get("transaction_status"));
        Outcome outcome = status == null ? Outcome.PENDING : switch (status.toLowerCase(Locale.ROOT)) {
            case "completed", "funds received", "successful", "success" -> Outcome.COMPLETED;
            case "failed", "transaction failed" -> Outcome.FAILED;
            default -> Outcome.PENDING;
        };
        return new Payment(outcome, minor(data.get("amount_received")), currency(data.get("currency")),
            text(data.get("transaction_reference")));
    }

    private static Long minor(Object amount) {
        if (amount == null) {
            return null;
        }
        try {
            BigDecimal major = new BigDecimal(amount.toString().strip());
            return major.signum() > 0 ? major.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() : null;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    /** "NGN", or an object such as {"code":"NGN"} depending on the endpoint. */
    private static String currency(Object value) {
        String code = value instanceof Map<?, ?> m
            ? text(m.get("code") != null ? m.get("code") : m.get("currency_code"))
            : text(value);
        return code != null && code.matches("[A-Za-z]{3}") ? code.toUpperCase(Locale.ROOT) : null;
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().strip();
    }

    private static JdkClientHttpRequestFactory factory(PayazaProperties props) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(props.timeout())
            .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(props.timeout());
        return factory;
    }
}

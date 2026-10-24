package com.mywatchmate.give;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentLinkRecognizerTest {

    private final PaymentLinkRecognizer recognizer = new PaymentLinkRecognizer(new GiveProperties(
        List.of("payaza.africa", " Payaza.NG "), Duration.ofMinutes(10), Duration.ofMinutes(5), 3, Duration.ofHours(1), ""));

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "https://payaza.africa/pay/stmarks,                         https://payaza.africa/pay/stmarks",
        "https://pay.payaza.africa/give/stmarks,                    https://pay.payaza.africa/give/stmarks",
        "HTTPS://PAY.Payaza.Africa/give/stmarks,                    https://pay.payaza.africa/give/stmarks",
        "https://pay.payaza.africa:443/give/stmarks/,               https://pay.payaza.africa/give/stmarks",
        "https://pay.payaza.africa./give/stmarks,                   https://pay.payaza.africa/give/stmarks",
        "'  https://pay.payaza.africa/give/stmarks  ',              https://pay.payaza.africa/give/stmarks",
        "https://pay.payaza.africa/give/stmarks#top,                https://pay.payaza.africa/give/stmarks",
        "https://checkout.payaza.ng/l/abc,                          https://checkout.payaza.ng/l/abc",
    })
    void realPayazaLinksAreAcceptedAndWrittenOneWay(String raw, String expected) {
        PaymentLinkRecognizer.Result result = recognizer.recognise(raw);

        assertThat(result.ok()).isTrue();
        assertThat(result.normalizedUrl()).isEqualTo(expected);
        assertThat(result.provider()).isEqualTo(PaymentLink.Provider.PAYAZA);
        assertThat(result.reason()).isNull();
    }

    @Test
    void trackingParametersGoTheLinksOwnStay() {
        assertThat(recognizer.recognise(
            "https://pay.payaza.africa/give/stmarks?utm_source=yt&amount=5000&fbclid=x&UTM_Medium=qr&ref=abc&&fund=tithe")
            .normalizedUrl()).isEqualTo("https://pay.payaza.africa/give/stmarks?amount=5000&fund=tithe");
        assertThat(recognizer.recognise("https://pay.payaza.africa/give/stmarks?utm_source=yt&si=1").normalizedUrl())
            .isEqualTo("https://pay.payaza.africa/give/stmarks");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        // Look-alikes and other sites
        "https://payaza.africa.evil.com/pay/x,       NOT_PAYAZA",
        "https://evilpayaza.africa/pay/x,            NOT_PAYAZA",
        "https://paypaza.africa/pay/x,               NOT_PAYAZA",
        "https://paystack.com/pay/x,                 NOT_PAYAZA",
        "https://pay.payaza.africa@evil.com/pay/x,   BAD_HOST",
        // Wrong scheme, port, host form
        "http://pay.payaza.africa/give/x,            NOT_HTTPS",
        "javascript:alert(1),                        NOT_HTTPS",
        "ftp://pay.payaza.africa/x,                  NOT_HTTPS",
        "https://pay.payaza.africa:8443/give/x,      BAD_PORT",
        "https://user:pw@pay.payaza.africa/give/x,   BAD_HOST",
        "https://203.0.113.9/give/x,                 BAD_HOST",
        "https://[2001:db8::1]/give/x,               BAD_HOST",
        "https://xn--pyaza-9ra.africa/give/x,        BAD_HOST",
        "https://pay.xn--pyaza-9ra.africa/give/x,    BAD_HOST",
        // Not a payment page
        "https://pay.payaza.africa,                  NOT_A_PAYMENT_LINK",
        "https://pay.payaza.africa/,                 NOT_A_PAYMENT_LINK",
        "'https://pay.payaza.africa/give x',         NOT_A_LINK",
        "not a link at all,                          NOT_A_LINK",
    })
    void everythingElseIsRejectedWithAReason(String raw, String reason) {
        PaymentLinkRecognizer.Result result = recognizer.recognise(raw);

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.normalizedUrl()).isNull();
    }

    @Test
    void nonAsciiLookAlikeHostsAreRejected() {
        // Cyrillic "а" in place of the Latin one
        assertThat(recognizer.recognise("https://pаyaza.africa/give/x").reason()).isEqualTo("BAD_HOST");
    }

    @Test
    void emptyOrHugeInputsAreRejected() {
        assertThat(recognizer.recognise(null).reason()).isEqualTo("EMPTY");
        assertThat(recognizer.recognise("   ").reason()).isEqualTo("EMPTY");
        assertThat(recognizer.recognise("https://pay.payaza.africa/" + "x".repeat(PaymentLinkRecognizer.MAX_LENGTH)).reason())
            .isEqualTo("TOO_LONG");
    }
}

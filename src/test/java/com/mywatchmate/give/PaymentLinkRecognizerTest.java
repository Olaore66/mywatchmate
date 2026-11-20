package com.mywatchmate.give;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentLinkRecognizerTest {

    private final PaymentLinkRecognizer recognizer = new PaymentLinkRecognizer(new GiveProperties(
        List.of("https://business.payaza.africa/pay/", " https://checkout.payaza.ng/l "), Duration.ofMinutes(10),
        Duration.ofMinutes(5), 3, Duration.ofHours(1), ""));

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "https://business.payaza.africa/pay/stmarks,                 https://business.payaza.africa/pay/stmarks",
        "HTTPS://Business.Payaza.Africa/pay/StMarks-Lagos_2,          https://business.payaza.africa/pay/StMarks-Lagos_2",
        "https://business.payaza.africa:443/pay/stmarks/,            https://business.payaza.africa/pay/stmarks",
        "https://business.payaza.africa./pay/stmarks,                https://business.payaza.africa/pay/stmarks",
        "'  https://business.payaza.africa/pay/stmarks  ',           https://business.payaza.africa/pay/stmarks",
        "https://business.payaza.africa/pay/stmarks#top,             https://business.payaza.africa/pay/stmarks",
        "https://checkout.payaza.ng/l/abc,                           https://checkout.payaza.ng/l/abc",
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
            "https://business.payaza.africa/pay/stmarks?utm_source=yt&amount=5000&fbclid=x&UTM_Medium=qr&ref=abc&&fund=tithe")
            .normalizedUrl()).isEqualTo("https://business.payaza.africa/pay/stmarks?amount=5000&fund=tithe");
        assertThat(recognizer.recognise("https://business.payaza.africa/pay/stmarks?utm_source=yt&si=1").normalizedUrl())
            .isEqualTo("https://business.payaza.africa/pay/stmarks");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        // Look-alikes, other sites, and other Payaza addresses that aren't payment links' hosts
        "https://business.payaza.africa.evil.com/pay/x,  NOT_PAYAZA",
        "https://evilbusiness.payaza.africa/pay/x,       NOT_PAYAZA",
        "https://x.business.payaza.africa/pay/x,         NOT_PAYAZA",
        "https://payaza.africa/pay/x,                    NOT_PAYAZA",
        "https://docs.payaza.africa/pay/x,               NOT_PAYAZA",
        "https://paystack.com/pay/x,                     NOT_PAYAZA",
        "https://business.payaza.africa@evil.com/pay/x,  BAD_HOST",
        // Wrong scheme, port, host form
        "http://business.payaza.africa/pay/x,            NOT_HTTPS",
        "javascript:alert(1),                            NOT_HTTPS",
        "ftp://business.payaza.africa/pay/x,             NOT_HTTPS",
        "https://business.payaza.africa:8443/pay/x,      BAD_PORT",
        "https://user:pw@business.payaza.africa/pay/x,   BAD_HOST",
        "https://203.0.113.9/pay/x,                      BAD_HOST",
        "https://[2001:db8::1]/pay/x,                    BAD_HOST",
        "https://xn--pyaza-9ra.africa/pay/x,             BAD_HOST",
        "https://business.xn--pyaza-9ra.africa/pay/x,    BAD_HOST",
        // On Payaza's link host, but not a payment link
        "https://business.payaza.africa,                 NOT_A_PAYMENT_LINK",
        "https://business.payaza.africa/,                NOT_A_PAYMENT_LINK",
        "https://business.payaza.africa/pay,             NOT_A_PAYMENT_LINK",
        "https://business.payaza.africa/pay/,            NOT_A_PAYMENT_LINK",
        "https://business.payaza.africa/login,           NOT_A_PAYMENT_LINK",
        "https://business.payaza.africa/pay/x/settings,  NOT_A_PAYMENT_LINK",
        "https://business.payaza.africa/pay/%3Cscript,   NOT_A_PAYMENT_LINK",
        "https://business.payaza.africa/payment/x,       NOT_A_PAYMENT_LINK",
        "'https://business.payaza.africa/pay/x y',       NOT_A_LINK",
        "not a link at all,                              NOT_A_LINK",
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
        assertThat(recognizer.recognise("https://business.payaza.africa/pay/" + "x".repeat(PaymentLinkRecognizer.MAX_LENGTH)).reason())
            .isEqualTo("TOO_LONG");
    }
}

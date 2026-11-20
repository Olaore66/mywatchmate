package com.mywatchmate.give;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mywatchmate.give.PayazaClient.Outcome;
import com.mywatchmate.give.PayazaClient.Payment;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The status query as Payaza documents it, against a stand-in for Payaza's API. */
class PayazaClientTest {

    private static final String KEY = "PZ78-PKTEST-ABCDEF123456";
    private static final String STATUS = "https://api.payaza.africa/live" + PayazaClient.STATUS_PATH + "?merchant_reference=MWMREF1";

    private MockRestServiceServer payaza;
    private PayazaClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.payaza.africa/live");
        payaza = MockRestServiceServer.bindTo(builder).build();
        client = new PayazaClient(builder.build());
    }

    @Test
    void asksWithTheChurchsPublicKeyAndReadsACompletedPayment() {
        payaza.expect(requestTo(STATUS))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Payaza " + Base64.getEncoder().encodeToString(KEY.getBytes())))
            .andExpect(header("X-TenantID", "test"))
            .andRespond(withSuccess("""
                {"success":true,"message":"ok","data":{"transaction_reference":"PZ-123","transaction_status":"Completed",
                 "amount_received":5000.5,"currency":"NGN","merchant_transaction_reference":"MWMREF1"}}""",
                MediaType.APPLICATION_JSON));

        Payment payment = client.status(KEY, PayazaAccount.Mode.TEST, "MWMREF1");

        assertThat(payment).isEqualTo(new Payment(Outcome.COMPLETED, 500_050L, "NGN", "PZ-123"));
        payaza.verify();
    }

    @Test
    void liveModeSaysSo() {
        payaza.expect(requestTo(STATUS)).andExpect(header("X-TenantID", "live"))
            .andRespond(withSuccess("{\"data\":{\"transaction_status\":\"Failed\"}}", MediaType.APPLICATION_JSON));

        assertThat(client.status(KEY, PayazaAccount.Mode.LIVE, "MWMREF1").outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test
    void unknownOrMissingMeansNotFoundAndTroubleMeansAskAgain() {
        payaza.expect(requestTo(STATUS)).andRespond(withResourceNotFound());
        assertThat(client.status(KEY, PayazaAccount.Mode.TEST, "MWMREF1").outcome()).isEqualTo(Outcome.NOT_FOUND);

        payaza.reset();
        payaza.expect(requestTo(STATUS)).andRespond(withServerError());
        assertThat(client.status(KEY, PayazaAccount.Mode.TEST, "MWMREF1").outcome()).isEqualTo(Outcome.PENDING);
    }

    @Test
    void readsTheShapesPayazaDocumentsAndIgnoresNonsense() {
        assertThat(PayazaClient.read(Map.of("data", Map.of("transaction_status", "Initialized"))).outcome())
            .isEqualTo(Outcome.PENDING);
        assertThat(PayazaClient.read(Map.of("data", Map.of("transaction_status", "Funds Received", "amount_received", "1200",
            "currency", Map.of("code", "ngn"))))).isEqualTo(new Payment(Outcome.COMPLETED, 120_000L, "NGN", null));
        assertThat(PayazaClient.read(Map.of("success", false, "message", "no such transaction")).outcome())
            .isEqualTo(Outcome.NOT_FOUND);
        assertThat(PayazaClient.read(Map.of("message", "odd")).outcome()).isEqualTo(Outcome.PENDING);
        assertThat(PayazaClient.read(null).outcome()).isEqualTo(Outcome.PENDING);
        assertThat(PayazaClient.read(Map.of("data", Map.of("transaction_status", "Completed", "amount_received", "abc",
            "currency", "naira"))).amountMinor()).isNull();
        assertThat(PayazaClient.read(Map.of("data", Map.of())).outcome()).isEqualTo(Outcome.PENDING);
    }

    @Test
    void settingsHaveSensibleDefaults() {
        PayazaProperties defaults = new PayazaProperties(" ", null, null);

        assertThat(defaults.apiBase()).isEqualTo("https://api.payaza.africa/live");
        assertThat(defaults.timeout()).hasSeconds(5);
        assertThat(defaults.verifyFor()).hasHours(2);
        assertThat(new PayazaProperties("https://sandbox.example/", null, null).apiBase()).isEqualTo("https://sandbox.example");
        assertThat(new PayazaClient(defaults)).isNotNull();
    }
}

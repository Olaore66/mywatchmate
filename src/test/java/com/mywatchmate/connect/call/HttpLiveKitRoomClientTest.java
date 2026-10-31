package com.mywatchmate.connect.call;

import static com.mywatchmate.testsupport.Fixtures.MONDAY_MIDNIGHT;
import static com.mywatchmate.testsupport.Fixtures.assertApiError;
import static com.mywatchmate.testsupport.Fixtures.clockAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mywatchmate.connect.call.dto.CallParticipant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpLiveKitRoomClientTest {

    private static final String API = "https://mywatchmate-test.livekit.cloud/twirp/livekit.RoomService/";

    private MockRestServiceServer server;
    private HttpLiveKitRoomClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpLiveKitRoomClient(LiveKitTokensTest.PROPS, new LiveKitTokens(LiveKitTokensTest.PROPS),
            clockAt(MONDAY_MIDNIGHT), builder.build());
    }

    @Test
    void listsWhoIsInTheCallWithWhatTheyreSending() {
        UUID bisi = UUID.randomUUID();
        UUID ada = UUID.randomUUID();
        server.expect(requestTo(API + "ListParticipants"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", startsWith("Bearer ")))
            .andExpect(content().json("{\"room\":\"space-1\"}"))
            .andRespond(withSuccess("""
                {"participants":[
                  {"identity":"%s","name":"Bisi","joinedAt":"1793577600","tracks":[
                    {"type":"VIDEO","source":"CAMERA","muted":false},
                    {"source":"MICROPHONE","muted":true}]},
                  {"identity":"%s","name":"Ada","joined_at":1793577660,"tracks":[
                    {"type":1,"source":3},
                    {"muted":false}]},
                  {"identity":"egress-recorder","name":"bot"}
                ]}""".formatted(bisi, ada), MediaType.APPLICATION_JSON));

        List<CallParticipant> found = client.listParticipants("space-1");

        assertThat(found).containsExactly(
            new CallParticipant(bisi, "Bisi", Instant.ofEpochSecond(1793577600L), true, false, false),
            new CallParticipant(ada, "Ada", Instant.ofEpochSecond(1793577660L), false, true, true));
        server.verify();
    }

    @Test
    void unlabelledTracksCountByTheirTypeAndBadTimesAreLeftOut() {
        UUID kemi = UUID.randomUUID();
        server.expect(requestTo(API + "ListParticipants")).andRespond(withSuccess("""
            {"participants":[{"identity":"%s","joinedAt":"soon","tracks":[{"type":"VIDEO"},"junk"]}]}""".formatted(kemi),
            MediaType.APPLICATION_JSON));

        assertThat(client.listParticipants("space-1"))
            .containsExactly(new CallParticipant(kemi, null, null, true, false, false));
    }

    @Test
    void anEmptyOrUnknownRoomHasNobody() {
        server.expect(requestTo(API + "ListParticipants")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "ListParticipants")).andRespond(withResourceNotFound());

        assertThat(client.listParticipants("space-1")).isEmpty();
        assertThat(client.listParticipants("space-2")).isEmpty();
    }

    @Test
    void livekitBeingDownIsA502WithACode() {
        server.expect(requestTo(API + "ListParticipants")).andRespond(withServerError());

        assertApiError(() -> client.listParticipants("space-1"), HttpStatus.BAD_GATEWAY, "CALL_SERVICE_UNAVAILABLE");
    }

    @Test
    void removesSomeoneAndDoesntMindIfTheyWerentThere() {
        server.expect(requestTo(API + "RemoveParticipant"))
            .andExpect(content().json("{\"room\":\"space-1\",\"identity\":\"p-1\"}"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "RemoveParticipant")).andRespond(withResourceNotFound());

        client.removeParticipant("space-1", "p-1");
        assertThatCode(() -> client.removeParticipant("space-1", "gone")).doesNotThrowAnyException();
        server.verify();
    }
}

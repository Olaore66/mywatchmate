package com.mywatchmate.live;

import com.mywatchmate.live.dto.TimeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Live sync")
public class TimeController {

    private final Clock clock;

    @GetMapping("/api/v1/time")
    @Operation(summary = "The server's clock (public)", description = """
        Phones' clocks are often seconds off, so measure the difference once on entering a room:
        note the device time before (t0) and after (t1) the call; offset = serverTimeMs − (t0 + t1) / 2.
        Do it 3–5 times and keep the result from the fastest call (smallest t1 − t0).
        Server time now = device time + offset.""")
    @ApiResponse(responseCode = "200", description = "Server time")
    public TimeResponse now() {
        return TimeResponse.at(clock.instant());
    }
}

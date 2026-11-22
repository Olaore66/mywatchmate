package com.mywatchmate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The AI giving test video is a public, direct .mp4 that players (and the AI) can seek in. */
@SpringBootTest
@AutoConfigureMockMvc
class SampleVideoTests {

    private static final String VIDEO = "/samples/giving-qr-test.mp4";

    @Autowired MockMvc mvc;

    @Test
    void theTestVideoIsPublicAndAnMp4() throws Exception {
        byte[] body = mvc.perform(get(VIDEO))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "video/mp4"))
            .andReturn().getResponse().getContentAsByteArray();

        assertThat(body.length).isGreaterThan(100_000);
    }

    @Test
    void playersCanAskForPartOfIt() throws Exception {
        mvc.perform(get(VIDEO).header("Range", "bytes=1000-1999"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string("Content-Range", org.hamcrest.Matchers.startsWith("bytes 1000-1999/")));
    }

    @Test
    void itCanOnlyBeRead() throws Exception {
        mvc.perform(post(VIDEO)).andExpect(status().is4xxClientError());
    }
}

package com.agenttavern.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class GameTableControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    @Test void createsPrivatePlayerProjectionAndRejectsMissingSession() throws Exception {
        mvc.perform(get("/api/tables/current")).andExpect(status().isUnauthorized());
        String body = mvc.perform(post("/api/tables").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"测试旅人\",\"mode\":\"PLAYER\"}"))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly(GameTableController.SESSION_COOKIE,true))
                .andExpect(jsonPath("$.holeCards.length()").value(2)).andExpect(jsonPath("$.selfSeat").value(5))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("burnedCards","deck","playerId").contains("测试旅人");
    }

    @Test void streamsOnlyCommittedScopedFramesAndRejectsOtherTablesReplay() throws Exception {
        var created = mvc.perform(post("/api/tables").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"SPECTATOR\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.holeCards.length()").value(0)).andReturn();
        Cookie cookie = created.getResponse().getCookie(GameTableController.SESSION_COOKIE);
        var view = json.readTree(created.getResponse().getContentAsString());
        String id = view.path("tableId").asText();
        var stream = mvc.perform(get("/api/tables/current/events").cookie(cookie).param("tableId",id).param("after","0"))
                .andExpect(request().asyncStarted()).andExpect(header().string("X-Accel-Buffering","no")).andReturn();
        assertThat(stream.getResponse().getContentAsString()).contains("id:1","event:table","\"holeCards\":[]").doesNotContain("deck","burnedCards");
        stream.getRequest().getAsyncContext().complete();
    }

    @Test void expiredEventStreamReturnsUnauthorizedWithoutJsonNegotiationFailure() throws Exception {
        mvc.perform(get("/api/tables/current/events").accept(MediaType.TEXT_EVENT_STREAM)
                        .param("tableId", UUID.randomUUID().toString()).param("after", "0"))
                .andExpect(status().isUnauthorized());
    }

    @Test void replayIsSessionScopedAndCommandsNeedAnEnvelope() throws Exception {
        var created = mvc.perform(post("/api/tables").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"SPECTATOR\"}"))
                .andReturn();
        Cookie cookie = created.getResponse().getCookie(GameTableController.SESSION_COOKIE);
        String id = json.readTree(created.getResponse().getContentAsString()).path("tableId").asText();
        mvc.perform(get("/api/tables/current/replay").cookie(cookie).param("tableId",id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.frames[0].sequence").value(1));
        mvc.perform(get("/api/tables/current/replay").cookie(cookie).param("tableId",UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tables/current/advance").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test void validatesRosterSizeAndUniqueCharacters() throws Exception {
        mvc.perform(post("/api/tables").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"PLAYER\",\"personas\":[\"vesper\",\"vesper\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tables/roster")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(8));
    }
}

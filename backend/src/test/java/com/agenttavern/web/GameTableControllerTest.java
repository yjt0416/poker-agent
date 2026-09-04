package com.agenttavern.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class GameTableControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    @Test
    void createsPlayableSessionAndReturnsOnlyTheHumansPrivateCards() throws Exception {
        MvcResult result = mvc.perform(post("/api/tables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"测试旅人\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly(GameTableController.SESSION_COOKIE, true))
                .andExpect(jsonPath("$.selfSeat").value(5))
                .andExpect(jsonPath("$.seats.length()").value(6))
                .andExpect(jsonPath("$.holeCards.length()").value(2))
                .andExpect(jsonPath("$.actorSeat").value(5))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        assertThat(json)
                .doesNotContain("deck", "burnedCards", "currentHandStartingStacks", "playerId")
                .contains("测试旅人");
    }

    @Test
    void rejectsMissingSessionAndAcceptsARealPlayerAction() throws Exception {
        mvc.perform(get("/api/tables/current"))
                .andExpect(status().isUnauthorized());

        MvcResult created = mvc.perform(post("/api/tables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie session = created.getResponse().getCookie(GameTableController.SESSION_COOKIE);
        assertThat(session).isNotNull();

        mvc.perform(post("/api/tables/current/actions")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CALL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isNumber())
                .andExpect(jsonPath("$.actionLog[?(@.name == '旅人')]").exists());
    }

    @Test
    void spectatorReceivesNoPrivateCardsAndCanAdvanceOneAgentAtATime() throws Exception {
        MvcResult created = mvc.perform(post("/api/tables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"SPECTATOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SPECTATOR"))
                .andExpect(jsonPath("$.selfSeat").value(-1))
                .andExpect(jsonPath("$.holeCards.length()").value(0))
                .andExpect(jsonPath("$.actionLog.length()").value(0))
                .andReturn();
        Cookie session = created.getResponse().getCookie(GameTableController.SESSION_COOKIE);

        MvcResult advanced = mvc.perform(post("/api/tables/current/advance").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holeCards.length()").value(0))
                .andExpect(jsonPath("$.actionLog.length()").value(1))
                .andReturn();

        for (int turn = 0; turn < 100
                && "IN_HAND".equals(json.readTree(advanced.getResponse().getContentAsString()).path("status").asText());
                turn++) {
            advanced = mvc.perform(post("/api/tables/current/advance").cookie(session))
                    .andExpect(status().isOk())
                    .andReturn();
        }
        String completed = advanced.getResponse().getContentAsString();
        assertThat(json.readTree(completed).path("status").asText()).isNotEqualTo("IN_HAND");
        assertThat(completed).contains("本手结算", "茶馆荷官");
    }
}

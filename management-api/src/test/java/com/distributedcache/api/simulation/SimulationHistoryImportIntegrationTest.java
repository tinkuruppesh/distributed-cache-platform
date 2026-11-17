package com.distributedcache.api.simulation;

import com.distributedcache.persistence.SimulationRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uses the isolated application-test H2 database and the archived simulation payload as its fixture. */
@SpringBootTest(properties = "cluster.embedded-base-port=18101")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SimulationHistoryImportIntegrationTest {
    private static final String ID = "d7ced369";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SimulationRunRepository repository;

    @AfterEach
    void removeTestFixtureFromTestDatabase() { repository.deleteById(ID); }

    @Test
    void adminCanImportArchivedCancelledRunOnceAndReadIdenticalDetailBack() throws Exception {
        JsonNode archivedDetail;
        try (InputStream fixture = getClass().getResourceAsStream("/simulation-history-import-test.json")) {
            assertNotNull(fixture, "the exported simulation fixture must be on the test classpath");
            JsonNode archive = mapper.readTree(fixture);
            assertEquals(1, archive.path("recordCount").asInt());
            archivedDetail = archive.path("records").get(0);
        }
        assertEquals(ID, archivedDetail.path("summary").path("id").asText());
        assertEquals("CANCELLED", archivedDetail.path("summary").path("outcome").asText());
        assertEquals(15, archivedDetail.path("events").size());
        String body = mapper.writeValueAsString(archivedDetail);

        mvc.perform(post("/api/v1/simulations/import").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        String viewer = login("viewer", "Viewer@12345");
        mvc.perform(post("/api/v1/simulations/import").header("Authorization", viewer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        String admin = login("admin", "Admin@12345");
        ObjectNode invalidDetail = (ObjectNode) archivedDetail.deepCopy();
        ((ObjectNode) invalidDetail.get("summary")).put("outcome", "COMPLETED");
        mvc.perform(post("/api/v1/simulations/import").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(invalidDetail)))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertFalse(repository.existsById(ID), "invalid input must not create a row");

        mvc.perform(post("/api/v1/simulations/import").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        String response = mvc.perform(get("/api/v1/simulations/" + ID).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode restored = mapper.readTree(response);
        JsonNode archivedSummary = archivedDetail.path("summary");
        JsonNode restoredSummary = restored.path("summary");
        for (String field : new String[]{"id", "nodeId", "mode", "phase", "outcome", "startedAt", "endedAt",
                "detectionMs", "failoverMs", "recoveryMs", "requestsTotal", "errorRatePct"})
            assertEquals(archivedSummary.path(field), restoredSummary.path(field), "summary field " + field);
        assertEquals(1433, restoredSummary.path("requestsSucceeded").asInt());
        assertEquals(0, restoredSummary.path("requestsFailed").asInt());
        assertEquals(archivedDetail.path("report"), restored.path("report"));
        assertEquals(archivedDetail.path("events"), restored.path("events"));
        assertEquals("CANCELLED", restored.path("summary").path("phase").asText());
        assertEquals(15, restored.path("events").size());

        mvc.perform(get("/api/v1/simulations/analytics").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.items[0].id").value(ID));

        mvc.perform(post("/api/v1/simulations/import").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        String afterDuplicate = mvc.perform(get("/api/v1/simulations/" + ID).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode unchanged = mapper.readTree(afterDuplicate);
        assertEquals(archivedDetail.path("report"), unchanged.path("report"));
        assertEquals(archivedDetail.path("events"), unchanged.path("events"));
    }

    private String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(body).path("token").asText();
    }
}

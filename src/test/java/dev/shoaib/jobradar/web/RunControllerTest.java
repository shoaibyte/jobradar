package dev.shoaib.jobradar.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shoaib.jobradar.core.IngestRunner;
import dev.shoaib.jobradar.core.persistence.IngestRunEntity;
import dev.shoaib.jobradar.core.persistence.IngestRunRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RunController.class)
@Import(JsonCodec.class)
class RunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IngestRunRepository ingestRunRepository;

    // IngestRunner's concrete bean lives in pipeline-notify's package; the controller only
    // depends on the frozen core.IngestRunner interface, so a Mockito mock of the interface
    // is sufficient here and doesn't require knowledge of that implementation.
    @MockitoBean
    private IngestRunner ingestRunner;

    @Test
    void returnsRecentRunsWithParsedStats() throws Exception {
        IngestRunEntity run = new IngestRunEntity();
        run.setId(7);
        run.setStartedAt("2026-08-05T07:00:00Z");
        run.setFinishedAt("2026-08-05T07:02:00Z");
        run.setStats("{\"fetched\":42,\"matched\":5}");

        when(ingestRunRepository.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(List.of(run));

        mockMvc.perform(get("/api/runs").param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(7))
            .andExpect(jsonPath("$[0].startedAt").value("2026-08-05T07:00:00Z"))
            .andExpect(jsonPath("$[0].stats.fetched").value(42))
            .andExpect(jsonPath("$[0].stats.matched").value(5));
    }

    @Test
    void postRunTriggersIngestAndReturnsTheNewRun() throws Exception {
        IngestRunEntity run = new IngestRunEntity();
        run.setId(9);
        run.setStartedAt("2026-08-05T08:00:00Z");
        run.setFinishedAt("2026-08-05T08:05:00Z");
        run.setStats("{\"fetched\":10}");

        when(ingestRunner.runOnce()).thenReturn(9);
        when(ingestRunRepository.findById(9)).thenReturn(Optional.of(run));

        mockMvc.perform(post("/api/run"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(9))
            .andExpect(jsonPath("$.stats.fetched").value(10));
    }
}

package dev.shoaib.jobradar.web;

import dev.shoaib.jobradar.core.IngestRunner;
import dev.shoaib.jobradar.core.persistence.IngestRunEntity;
import dev.shoaib.jobradar.core.persistence.IngestRunRepository;
import dev.shoaib.jobradar.web.dto.RunView;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backs {@code GET /api/runs?limit=} and {@code POST /api/run} (Section 11).
 * {@code IngestRunner} is autowired purely by the {@code core.IngestRunner} interface type —
 * the concrete bean lives in pipeline-notify's package, never referenced here by class name.
 */
@RestController
public class RunController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final IngestRunRepository ingestRunRepository;
    private final IngestRunner ingestRunner;
    private final JsonCodec jsonCodec;

    public RunController(IngestRunRepository ingestRunRepository, IngestRunner ingestRunner,
        JsonCodec jsonCodec) {
        this.ingestRunRepository = ingestRunRepository;
        this.ingestRunner = ingestRunner;
        this.jsonCodec = jsonCodec;
    }

    @GetMapping("/api/runs")
    public List<RunView> runs(@RequestParam(required = false) Integer limit) {
        int effectiveLimit = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return ingestRunRepository.findAllByOrderByIdDesc(PageRequest.of(0, effectiveLimit)).stream()
            .map(this::toView)
            .toList();
    }

    /**
     * Triggers the same ingest pass the hourly scheduler runs. This is a real fetch/match/notify
     * pass and may take a while to complete; a synchronous blocking response is acceptable for a
     * personal tool (per Section 11), so no async/202 handling here.
     */
    @PostMapping("/api/run")
    public RunView run() {
        Integer runId = ingestRunner.runOnce();
        return ingestRunRepository.findById(runId)
            .map(this::toView)
            .orElseGet(() -> new RunView(runId, null, null, Map.of()));
    }

    private RunView toView(IngestRunEntity entity) {
        return new RunView(entity.getId(), entity.getStartedAt(), entity.getFinishedAt(),
            jsonCodec.toMap(entity.getStats()));
    }
}

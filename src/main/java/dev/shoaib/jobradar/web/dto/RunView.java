package dev.shoaib.jobradar.web.dto;

import java.util.Map;

/**
 * Response shape for {@code GET /api/runs} and {@code POST /api/run}: an ingest_run row
 * with the stats JSON-string column parsed into a structured map.
 */
public record RunView(Integer id, String startedAt, String finishedAt, Map<String, Object> stats) {}

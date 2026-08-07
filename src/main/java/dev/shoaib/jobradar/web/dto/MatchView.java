package dev.shoaib.jobradar.web.dto;

import dev.shoaib.jobradar.core.MatchStrength;
import java.util.List;

/** Response shape for {@code GET /api/matches}: a match_result row joined with its job_record. */
public record MatchView(
    Integer jobId,
    String title,
    String company,
    String city,
    String country,
    String url,
    MatchStrength strength,
    double score,
    List<String> reasons,
    boolean visaFlag,
    String salaryRaw) {}

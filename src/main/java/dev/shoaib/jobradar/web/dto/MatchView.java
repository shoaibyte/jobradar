package dev.shoaib.jobradar.web.dto;

import dev.shoaib.jobradar.core.JobStatus;
import dev.shoaib.jobradar.core.MatchStrength;
import java.util.List;

/**
 * Response shape for {@code GET /api/matches}: a match_result row joined with its job_record.
 * {@code firstSeen} is when JobRadar first collected the job (ISO-8601 instant), which is what
 * the default "freshest first" ordering uses.
 */
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
    String salaryRaw,
    String source,
    List<String> techTags,
    JobStatus status,
    String postedAt,
    String firstSeen,
    String lastSeen) {}

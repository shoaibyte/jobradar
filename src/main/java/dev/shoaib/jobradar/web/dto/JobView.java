package dev.shoaib.jobradar.web.dto;

import dev.shoaib.jobradar.core.JobStatus;
import java.util.List;

/**
 * Response shape for {@code GET /api/jobs/{id}}: the full job_record row, with the
 * tech_tags/benefits JSON-string columns parsed into real lists.
 */
public record JobView(
    Integer id,
    String source,
    String externalId,
    String fingerprint,
    String title,
    String company,
    String city,
    String country,
    String url,
    String description,
    List<String> techTags,
    List<String> benefits,
    String salaryRaw,
    boolean visaFlag,
    String postedAt,
    String firstSeen,
    String lastSeen,
    JobStatus status) {}

package dev.shoaib.jobradar.web.dto;

import java.util.List;

/**
 * One relocateme_job row for the UI, with its issue's week and date attached and the
 * JSON-string columns parsed into lists. {@code location} is display text; filter on
 * {@code countryCodes} (ISO alpha-2) and {@code remote}/{@code remoteRegion}.
 */
public record RelocateMeJobView(
    Integer id,
    Integer issueId,
    Integer weekNumber,
    String postDate,
    String section,
    int position,
    String title,
    String company,
    String companyLinkedinUrl,
    String location,
    String city,
    String country,
    List<String> countryCodes,
    boolean remote,
    String remoteRegion,
    String industrySize,
    List<String> keywords,
    String applyUrl,
    boolean visaMentioned,
    List<String> details) {}

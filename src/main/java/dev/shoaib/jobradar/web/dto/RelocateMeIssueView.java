package dev.shoaib.jobradar.web.dto;

import java.util.Map;

/** One weekly issue with its stored job count per section, in newsletter order. */
public record RelocateMeIssueView(
    Integer id,
    Integer weekNumber,
    String title,
    String subtitle,
    String postDate,
    String slug,
    String bodyStatus,
    int jobCount,
    Map<String, Long> sections) {}

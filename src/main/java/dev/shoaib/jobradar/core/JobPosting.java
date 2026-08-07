package dev.shoaib.jobradar.core;

import java.time.Instant;
import java.util.List;

public record JobPosting(SourceType source, String sourceName, String externalId, String title,
    String company, String city, String country, String url, String description,
    List<String> techTags, List<String> benefits, String salaryRaw,
    boolean visaFlag, Instant postedAt) {}

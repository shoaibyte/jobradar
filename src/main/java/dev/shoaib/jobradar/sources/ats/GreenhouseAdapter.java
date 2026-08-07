package dev.shoaib.jobradar.sources.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shoaib.jobradar.core.CompanyEntry;
import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.JobSourceAdapter;
import dev.shoaib.jobradar.core.SourceFetchException;
import dev.shoaib.jobradar.core.SourceType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One adapter instance per Greenhouse company (Section 4.2). {@link AtsAdapterRegistrar}
 * registers one Spring bean of this class per {@code companyRegistry.byAts("greenhouse")}
 * entry, so each shows up separately as {@code "greenhouse:<token>"} in the pipeline's
 * {@code List<JobSourceAdapter>}.
 *
 * <p>{@code ?content=true} on the list endpoint returns each job's full description inline,
 * so unlike the HTML sources there is no separate detail page to fetch/skip -- the
 * {@code JobRecordRepository} detail-page-politeness pattern from Section 5 does not apply
 * here (see notes/sources-api.md).
 */
public class GreenhouseAdapter implements JobSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(GreenhouseAdapter.class);

    private final CompanyEntry entry;
    private final String baseUrl;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public GreenhouseAdapter(CompanyEntry entry, String baseUrl, boolean enabled) {
        this.entry = entry;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "greenhouse:" + entry.token();
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<JobPosting> fetch(FetchContext ctx) throws SourceFetchException {
        String url = baseUrl + "/" + entry.token() + "/jobs?content=true";
        String body;
        try {
            body = ctx.http().get(url);
        } catch (Exception e) {
            throw new SourceFetchException("Greenhouse fetch failed for " + entry.token(), e);
        }

        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new SourceFetchException("Greenhouse response was not valid JSON for " + entry.token(), e);
        }

        JsonNode jobs = root.path("jobs");
        List<JobPosting> result = new ArrayList<>();
        for (JsonNode job : jobs) {
            try {
                result.add(toPosting(job));
            } catch (Exception e) {
                log.warn("Skipping unparsable Greenhouse job for {}: {}", entry.token(), e.toString());
            }
        }
        return result;
    }

    private JobPosting toPosting(JsonNode job) {
        JsonNode idNode = job.get("id");
        if (idNode == null || idNode.isNull()) {
            // A job with no id has no stable identity to dedupe/track against downstream -- treat
            // it as malformed and let the caller's try/catch skip it rather than emit garbage.
            throw new IllegalStateException("Greenhouse job is missing required \"id\" field");
        }
        String id = idNode.asText();
        String title = job.path("title").asText(null);
        String absoluteUrl = job.path("absolute_url").asText(null);
        String locationName = job.path("location").path("name").asText(null);
        String rawContent = job.path("content").asText("");
        String html = Parser.unescapeEntities(rawContent, false);
        String description = Jsoup.parse(html).text();

        List<String> departments = new ArrayList<>();
        for (JsonNode d : job.path("departments")) {
            String n = d.path("name").asText(null);
            if (n != null && !n.isBlank()) {
                departments.add(n);
            }
        }

        Instant postedAt = parseInstant(job.path("updated_at").asText(null));

        return new JobPosting(
            SourceType.GREENHOUSE,
            name(),
            id,
            title,
            entry.name(),
            locationName,
            null,
            absoluteUrl,
            description,
            departments,
            List.of(),
            null,
            false,
            postedAt
        );
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}

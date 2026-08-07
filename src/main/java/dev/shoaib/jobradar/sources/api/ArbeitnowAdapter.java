package dev.shoaib.jobradar.sources.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.JobSourceAdapter;
import dev.shoaib.jobradar.core.SourceFetchException;
import dev.shoaib.jobradar.core.SourceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Arbeitnow structured feed (Section 4.1). {@code GET .../api/job-board-api?visa_sponsorship=true&page=N},
 * following {@code links.next} until it's null or {@code max-pages} is reached.
 */
@Component
public class ArbeitnowAdapter implements JobSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(ArbeitnowAdapter.class);

    /** Arbeitnow's own country-name spellings that occasionally show up in `location`. */
    private static final Map<String, String> COUNTRY_HINTS = new LinkedHashMap<>();

    static {
        COUNTRY_HINTS.put("germany", "DE");
        COUNTRY_HINTS.put("deutschland", "DE");
        COUNTRY_HINTS.put("austria", "AT");
        COUNTRY_HINTS.put("österreich", "AT");
        COUNTRY_HINTS.put("switzerland", "CH");
        COUNTRY_HINTS.put("schweiz", "CH");
        COUNTRY_HINTS.put("netherlands", "NL");
        COUNTRY_HINTS.put("spain", "ES");
        COUNTRY_HINTS.put("portugal", "PT");
        COUNTRY_HINTS.put("france", "FR");
        COUNTRY_HINTS.put("poland", "PL");
        COUNTRY_HINTS.put("united kingdom", "GB");
        COUNTRY_HINTS.put("uk", "GB");
        COUNTRY_HINTS.put("italy", "IT");
    }

    private final String baseUrl;
    private final boolean enabled;
    private final int maxPages;
    private final ObjectMapper mapper = new ObjectMapper();

    public ArbeitnowAdapter(
        @Value("${app.sources.arbeitnow.base-url}") String baseUrl,
        @Value("${app.sources.arbeitnow.enabled}") boolean enabled,
        @Value("${app.sources.arbeitnow.max-pages}") int maxPages) {
        this.baseUrl = baseUrl;
        this.enabled = enabled;
        this.maxPages = maxPages;
    }

    @Override
    public String name() {
        return "arbeitnow";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<JobPosting> fetch(FetchContext ctx) throws SourceFetchException {
        List<JobPosting> result = new ArrayList<>();
        String url = baseUrl + "?visa_sponsorship=true&page=1";
        int pagesFetched = 0;

        while (url != null && pagesFetched < maxPages) {
            String body;
            try {
                body = ctx.http().get(url);
            } catch (Exception e) {
                throw new SourceFetchException("Arbeitnow fetch failed for " + url, e);
            }

            Page page;
            try {
                page = parsePage(body);
            } catch (Exception e) {
                throw new SourceFetchException("Arbeitnow response was not valid JSON at " + url, e);
            }

            for (JsonNode job : page.jobs()) {
                try {
                    result.add(toPosting(job));
                } catch (Exception e) {
                    log.warn("Skipping unparsable Arbeitnow job: {}", e.toString());
                }
            }

            url = page.nextLink();
            pagesFetched++;
        }
        return result;
    }

    /** Package-visible pure parsing method so tests can feed fixture JSON directly. */
    Page parsePage(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode data = root.path("data");
        String next = root.path("links").path("next").isMissingNode()
            ? null : root.path("links").path("next").asText(null);
        List<JsonNode> jobs = new ArrayList<>();
        for (JsonNode job : data) {
            jobs.add(job);
        }
        return new Page(jobs, next);
    }

    record Page(List<JsonNode> jobs, String nextLink) {
    }

    private JobPosting toPosting(JsonNode job) {
        JsonNode slugNode = job.get("slug");
        if (slugNode == null || slugNode.isNull() || slugNode.asText().isBlank()) {
            // No stable identity to dedupe/track against downstream -- let the caller's
            // try/catch skip this job rather than emit a garbage posting.
            throw new IllegalStateException("Arbeitnow job is missing required \"slug\" field");
        }
        String slug = slugNode.asText();
        String title = job.path("title").asText(null);
        String company = job.path("company_name").asText(null);
        String rawDescription = job.path("description").asText("");
        String description = Jsoup.parse(rawDescription).text();
        boolean remote = job.path("remote").asBoolean(false);
        String url = job.path("url").asText(null);
        String location = job.path("location").asText(null);

        List<String> techTags = new ArrayList<>();
        for (JsonNode t : job.path("tags")) {
            techTags.add(t.asText());
        }
        for (JsonNode t : job.path("job_types")) {
            techTags.add(t.asText());
        }

        long createdAtEpoch = job.path("created_at").asLong(0);
        Instant postedAt = createdAtEpoch > 0 ? Instant.ofEpochSecond(createdAtEpoch) : null;

        String city = remote ? "Remote" : location;
        String country = guessCountry(location);

        return new JobPosting(
            SourceType.ARBEITNOW,
            name(),
            slug,
            title,
            company,
            city,
            country,
            url,
            description,
            techTags,
            List.of(),
            null,
            true, // queried with visa_sponsorship=true, so every result is visa-flagged
            postedAt
        );
    }

    /** Arbeitnow is a German job board; default to DE, override for a handful of known hints. */
    private static String guessCountry(String location) {
        if (location == null || location.isBlank()) {
            return "DE";
        }
        String lower = location.toLowerCase();
        for (Map.Entry<String, String> hint : COUNTRY_HINTS.entrySet()) {
            if (lower.contains(hint.getKey())) {
                return hint.getValue();
            }
        }
        return "DE";
    }
}

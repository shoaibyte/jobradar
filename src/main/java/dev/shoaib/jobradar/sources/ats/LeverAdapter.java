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
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic Lever client (Section 4.5). {@code GET https://api.lever.co/v0/postings/{site}?mode=json}.
 * No live company is wired to it in the current (frozen) {@code companies.yml} and
 * {@code app.sources.lever.enabled} is false, but {@link AtsAdapterRegistrar} will register one
 * bean per {@code companyRegistry.byAts("lever")} entry if one is ever added.
 */
public class LeverAdapter implements JobSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(LeverAdapter.class);

    private final CompanyEntry entry;
    private final String baseUrl;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public LeverAdapter(CompanyEntry entry, String baseUrl, boolean enabled) {
        this.entry = entry;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "lever:" + entry.token();
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<JobPosting> fetch(FetchContext ctx) throws SourceFetchException {
        String url = baseUrl + "/" + entry.token() + "?mode=json";
        String body;
        try {
            body = ctx.http().get(url);
        } catch (Exception e) {
            throw new SourceFetchException("Lever fetch failed for " + entry.token(), e);
        }
        return parse(body);
    }

    /** Package-visible pure parsing method so tests can feed fixture JSON directly. */
    List<JobPosting> parse(String json) throws SourceFetchException {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new SourceFetchException("Lever response was not valid JSON for " + entry.token(), e);
        }
        List<JobPosting> result = new ArrayList<>();
        for (JsonNode posting : root) {
            try {
                result.add(toPosting(posting));
            } catch (Exception e) {
                log.warn("Skipping unparsable Lever posting for {}: {}", entry.token(), e.toString());
            }
        }
        return result;
    }

    private JobPosting toPosting(JsonNode posting) {
        JsonNode idNode = posting.get("id");
        if (idNode == null || idNode.isNull()) {
            // No stable identity to dedupe/track against downstream -- let the caller's
            // try/catch skip this posting rather than emit a garbage one.
            throw new IllegalStateException("Lever posting is missing required \"id\" field");
        }
        String id = idNode.asText();
        String title = posting.path("text").asText(null);
        JsonNode categories = posting.path("categories");
        String location = categories.path("location").asText(null);
        String team = categories.path("team").asText(null);
        String department = categories.path("department").asText(null);

        StringBuilder description = new StringBuilder();
        String descriptionPlain = posting.path("descriptionPlain").asText(null);
        if (descriptionPlain != null && !descriptionPlain.isBlank()) {
            description.append(descriptionPlain);
        } else {
            String html = posting.path("description").asText("");
            description.append(Jsoup.parse(html).text());
        }
        for (JsonNode list : posting.path("lists")) {
            String listTitle = list.path("text").asText(null);
            String content = Jsoup.parse(list.path("content").asText("")).text();
            if (!content.isBlank()) {
                description.append("\n\n");
                if (listTitle != null && !listTitle.isBlank()) {
                    description.append(listTitle).append(": ");
                }
                description.append(content);
            }
        }

        List<String> techTags = new ArrayList<>();
        if (team != null && !team.isBlank()) {
            techTags.add(team);
        }
        if (department != null && !department.isBlank() && !department.equalsIgnoreCase(team)) {
            techTags.add(department);
        }

        String url = posting.path("hostedUrl").asText(null);
        long createdAtMillis = posting.path("createdAt").asLong(0);
        Instant postedAt = createdAtMillis > 0 ? Instant.ofEpochMilli(createdAtMillis) : null;

        return new JobPosting(
            SourceType.LEVER,
            name(),
            id,
            title,
            entry.name(),
            location,
            null,
            url,
            description.toString(),
            techTags,
            List.of(),
            null,
            false,
            postedAt
        );
    }
}

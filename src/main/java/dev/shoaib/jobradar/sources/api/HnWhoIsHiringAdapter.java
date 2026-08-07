package dev.shoaib.jobradar.sources.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.JobSourceAdapter;
import dev.shoaib.jobradar.core.SourceFetchException;
import dev.shoaib.jobradar.core.SourceType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hacker News "Who is hiring?" thread scraper (Section 4.4). Discovers the current and
 * previous month's threads via Algolia's search API, pages through top-level comments on
 * both, and keeps only ones that look like a visa/relocation-friendly Java or Go role.
 *
 * <p>Does not support removal detection -- a "gone" HN comment doesn't mean the role closed,
 * it usually just means the thread aged out of easy pagination.
 */
@Component
public class HnWhoIsHiringAdapter implements JobSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(HnWhoIsHiringAdapter.class);

    private static final Pattern VISA_OR_RELOCATE = Pattern.compile("(?i)visa|relocat");
    private static final String THREAD_SEARCH_QUERY = "\"Ask HN: Who is hiring?\"";

    private final String algoliaBaseUrl;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public HnWhoIsHiringAdapter(
        @Value("${app.sources.hn-who-is-hiring.algolia-base-url}") String algoliaBaseUrl,
        @Value("${app.sources.hn-who-is-hiring.enabled}") boolean enabled) {
        this.algoliaBaseUrl = algoliaBaseUrl;
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "hn-who-is-hiring";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean supportsRemovalDetection() {
        return false;
    }

    @Override
    public List<JobPosting> fetch(FetchContext ctx) throws SourceFetchException {
        List<Long> storyIds;
        try {
            storyIds = discoverThreadIds(ctx);
        } catch (Exception e) {
            throw new SourceFetchException("Could not discover HN who-is-hiring threads", e);
        }

        List<JobPosting> result = new ArrayList<>();
        for (Long storyId : storyIds) {
            try {
                result.addAll(fetchThread(ctx, storyId));
            } catch (Exception e) {
                log.warn("Skipping HN who-is-hiring thread {}: {}", storyId, e.toString());
            }
        }
        return result;
    }

    private List<Long> discoverThreadIds(FetchContext ctx) throws Exception {
        String query = URLEncoder.encode(THREAD_SEARCH_QUERY, StandardCharsets.UTF_8);
        String url = algoliaBaseUrl + "/search_by_date?query=" + query
            + "&tags=story,author_whoishiring&hitsPerPage=2";
        String body = ctx.http().get(url);
        JsonNode root = mapper.readTree(body);
        List<Long> ids = new ArrayList<>();
        for (JsonNode hit : root.path("hits")) {
            String objectId = hit.path("objectID").asText(null);
            if (objectId != null) {
                ids.add(Long.parseLong(objectId));
            }
        }
        return ids;
    }

    private List<JobPosting> fetchThread(FetchContext ctx, long storyId) throws Exception {
        List<JobPosting> postings = new ArrayList<>();
        int page = 0;
        while (true) {
            String url = algoliaBaseUrl + "/search_by_date?tags=comment,story_" + storyId
                + "&hitsPerPage=100&page=" + page;
            String body = ctx.http().get(url);
            CommentsPage parsed = parseCommentsPage(body);
            if (parsed.comments().isEmpty()) {
                break;
            }
            for (HnComment c : parsed.comments()) {
                if (isRelevant(c, storyId)) {
                    postings.add(toPosting(c));
                }
            }
            page++;
            if (page >= parsed.nbPages()) {
                break;
            }
        }
        return postings;
    }

    /** Package-visible pure parsing method so tests can feed fixture JSON directly. */
    CommentsPage parseCommentsPage(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        int page = root.path("page").asInt(0);
        int nbPages = root.path("nbPages").asInt(1);
        List<HnComment> comments = new ArrayList<>();
        for (JsonNode hit : root.path("hits")) {
            String objectId = hit.path("objectID").asText(null);
            String commentText = hit.path("comment_text").asText(null);
            if (objectId == null || commentText == null) {
                continue;
            }
            Long parentId = hit.hasNonNull("parent_id") ? hit.path("parent_id").asLong() : null;
            Long storyId = hit.hasNonNull("story_id") ? hit.path("story_id").asLong() : null;
            long createdAtEpoch = hit.path("created_at_i").asLong(0);
            comments.add(new HnComment(objectId, parentId, storyId, commentText, createdAtEpoch));
        }
        return new CommentsPage(comments, page, nbPages);
    }

    /** Top-level (i.e. a direct reply to the story, not a reply-to-a-reply) and Java/Go + visa/relocation shaped. */
    boolean isRelevant(HnComment c, long storyId) {
        if (c.parentId() == null || c.parentId() != storyId) {
            return false;
        }
        String text = Jsoup.parse(c.commentText()).text();
        return VISA_OR_RELOCATE.matcher(text).find() && TechGate.matches(text);
    }

    /** Package-visible so tests can convert a filtered {@link HnComment} without a full fetch(). */
    JobPosting toPosting(HnComment c) {
        String text = Jsoup.parse(c.commentText()).text();
        String header = headerLine(c.commentText());
        String[] tokens = header.split("\\|");
        String company = tokens.length > 0 && !tokens[0].isBlank() ? tokens[0].trim() : "Unknown";
        String title = tokens.length > 1 && !tokens[1].isBlank() ? tokens[1].trim() : header;

        List<String> techTags = new ArrayList<>();
        if (TechGate.matchesJava(text)) {
            techTags.add("java");
        }
        if (TechGate.matchesGo(text)) {
            techTags.add("go");
        }

        Instant postedAt = c.createdAtEpoch() > 0 ? Instant.ofEpochSecond(c.createdAtEpoch()) : null;

        return new JobPosting(
            SourceType.HN_WHO_IS_HIRING,
            name(),
            c.objectId(),
            title,
            company,
            null,
            null,
            "https://news.ycombinator.com/item?id=" + c.objectId(),
            text,
            techTags,
            List.of(),
            null,
            true, // already filtered on the visa/relocation regex
            postedAt
        );
    }

    /** HN convention: "Company | Role | Location | ..." as the first `<p>`-delimited segment. */
    private static String headerLine(String rawCommentText) {
        int idx = rawCommentText.indexOf("<p>");
        String segment = idx >= 0 ? rawCommentText.substring(0, idx) : rawCommentText;
        return Jsoup.parse(segment).text().trim();
    }

    record HnComment(String objectId, Long parentId, Long storyId, String commentText, long createdAtEpoch) {
    }

    record CommentsPage(List<HnComment> comments, int page, int nbPages) {
    }
}

package dev.shoaib.jobradar.sources.html;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Japan Dev (Section 5.2). japan-dev.com now runs on Nuxt, not the Next.js the spec
 * assumed -- see notes/phase0-orchestrator.md. The embedded JSON island is
 * {@code <script id="__NUXT_DATA__">}, encoded devalue-style (see
 * {@link NuxtDevaluePayload}), and it carries every field we need (title, company,
 * location, salary, skills, visa-sponsorship, and the "apply from abroad"/"residents
 * only" distinction) directly -- no per-job detail page fetch is needed or available
 * as a fixture, so this adapter only ever crawls the listing/category pages configured
 * in {@code app.sources.japandev.paths}.
 *
 * <p>Per spec Section 5.2's own fallback clause, if the {@code __NUXT_DATA__} script is
 * missing or fails to parse, we fall back to best-effort DOM scraping of the
 * server-rendered job cards. In practice the DOM fallback recovers materially less
 * (the technology-list tags are empty in the raw SSR HTML, populated only after Vue
 * hydration from this same JSON), so it is a defensive last resort, not a first-class
 * data source -- see notes/sources-html.md.
 */
@Component
public class JapanDevAdapter implements JobSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(JapanDevAdapter.class);
    private static final String SOURCE_NAME = "japandev";
    private static final String CANDIDATE_LOCATION_ANYWHERE = "candidate_location_anywhere";
    private static final String CANDIDATE_LOCATION_JAPAN_ONLY = "candidate_location_japan_only";

    private final JapanDevProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public JapanDevAdapter(JapanDevProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return SOURCE_NAME;
    }

    @Override
    public boolean enabled() {
        return props.enabled();
    }

    @Override
    public List<JobPosting> fetch(FetchContext ctx) throws SourceFetchException {
        if (!enabled()) {
            return List.of();
        }
        Map<String, JobPosting> byExternalId = new LinkedHashMap<>();
        boolean anyPageFetched = false;

        for (String path : props.paths()) {
            String url = props.baseUrl() + path;
            Optional<String> html;
            try {
                html = ctx.http().tryGet(url);
            } catch (Exception e) {
                log.warn("japandev: failed to fetch {}", url, e);
                continue;
            }
            if (html.isEmpty()) {
                continue;
            }
            anyPageFetched = true;

            List<JobPosting> pagePostings;
            try {
                pagePostings = parseNuxtPage(html.get(), url, ctx);
            } catch (Exception e) {
                log.warn("japandev: __NUXT_DATA__ parse failed for {}, falling back to DOM scraping", url, e);
                pagePostings = null;
            }
            if (pagePostings == null) {
                try {
                    pagePostings = parseDomFallback(html.get(), url, ctx);
                } catch (Exception e) {
                    log.warn("japandev: DOM fallback also failed for {}", url, e);
                    pagePostings = List.of();
                }
            }
            for (JobPosting posting : pagePostings) {
                byExternalId.putIfAbsent(posting.externalId(), posting);
            }
        }

        if (!anyPageFetched) {
            throw new SourceFetchException("japandev: none of the configured paths returned content");
        }
        return new ArrayList<>(byExternalId.values());
    }

    /** Package-visible so tests can feed fixture HTML directly. Returns null if no NUXT_DATA found/parseable. */
    List<JobPosting> parseNuxtPage(String html, String pageUrl, FetchContext ctx) throws Exception {
        Document doc = Jsoup.parse(html, pageUrl);
        Element script = doc.selectFirst("script#__NUXT_DATA__");
        if (script == null) {
            return null;
        }
        String json = script.data();
        if (json == null || json.isBlank()) {
            return null;
        }
        List<Object> raw = mapper.readValue(json, new TypeReference<List<Object>>() {});
        NuxtDevaluePayload payload = new NuxtDevaluePayload(raw);
        List<Map<String, Object>> hits = payload.findJobHitLists();

        List<JobPosting> postings = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            try {
                JobPosting posting = toJobPosting(hit, ctx);
                if (posting != null) {
                    postings.add(posting);
                }
            } catch (Exception e) {
                log.warn("japandev: skipping unparsable job hit: {}", e.toString());
            }
        }
        return postings;
    }

    @SuppressWarnings("unchecked")
    private JobPosting toJobPosting(Map<String, Object> hit, FetchContext ctx) {
        String candidateLocation = str(hit.get("candidate_location"));
        if (CANDIDATE_LOCATION_JAPAN_ONLY.equals(candidateLocation)) {
            // Japan-residents-only listings are dropped per spec (5.2). This mirrors the DOM's
            // "Residents Only"/"Japan Only" badge 1:1 in the fixture (verified while building this).
            return null;
        }
        boolean visaFlag = CANDIDATE_LOCATION_ANYWHERE.equals(candidateLocation);

        String externalId = str(hit.get("id"));
        String title = str(hit.get("title"));
        String companyName = str(hit.get("company_name"));
        String city = str(hit.get("location"));
        String slug = str(hit.get("slug"));

        Object companyObj = hit.get("company");
        String companySlug = companyObj instanceof Map<?, ?> companyMap ? str(companyMap.get("slug")) : null;
        String shortDescription = companyObj instanceof Map<?, ?> companyMap ? str(companyMap.get("short_description")) : null;

        String url = companySlug != null && slug != null
            ? props.baseUrl() + "/jobs/" + companySlug + "/" + slug
            : (slug != null ? props.baseUrl() + "/jobs/" + slug : props.baseUrl() + "/jobs");

        if (externalId == null || title == null) {
            throw new IllegalStateException("japandev job hit missing id/title");
        }

        List<String> techTags = new ArrayList<>();
        Object skillNames = hit.get("skill_names");
        if (skillNames instanceof List<?> list) {
            for (Object s : list) {
                if (s != null) {
                    techTags.add(String.valueOf(s));
                }
            }
        }

        List<String> benefits = new ArrayList<>();
        Object companyTagNames = hit.get("company_tag_names");
        if (companyTagNames instanceof List<?> list) {
            for (Object s : list) {
                if (s != null) {
                    benefits.add(String.valueOf(s));
                }
            }
        }

        String salaryRaw = formatSalary(hit.get("salary_min"), hit.get("salary_max"));

        String description = buildDescription(title, companyName, city, shortDescription, hit);

        Instant postedAt = parseInstant(str(hit.get("published_at")), ctx);

        return new JobPosting(
            SourceType.JAPAN_DEV,
            SOURCE_NAME,
            externalId,
            title,
            companyName,
            city,
            "Japan",
            url,
            description,
            techTags,
            benefits,
            salaryRaw,
            visaFlag,
            postedAt
        );
    }

    private static String buildDescription(String title, String company, String city, String shortDescription,
        Map<String, Object> hit) {
        StringBuilder sb = new StringBuilder();
        if (shortDescription != null && !shortDescription.isBlank()) {
            sb.append(shortDescription).append("\n\n");
        }
        sb.append(title);
        if (company != null) {
            sb.append(" at ").append(company);
        }
        if (city != null && !city.isBlank()) {
            sb.append(" (").append(city).append(", Japan)");
        }
        sb.append(".\n");
        appendIfPresent(sb, "Seniority", hit.get("seniority_level"));
        appendIfPresent(sb, "Employment type", hit.get("employment_type"));
        appendIfPresent(sb, "Remote", hit.get("remote_level"));
        appendIfPresent(sb, "Japanese level", hit.get("japanese_level"));
        appendIfPresent(sb, "English level", hit.get("english_level"));
        Object companyTagNames = hit.get("company_tag_names");
        if (companyTagNames instanceof List<?> list && !list.isEmpty()) {
            sb.append("Company perks: ");
            sb.append(String.join(", ", list.stream().map(String::valueOf).toList()));
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static void appendIfPresent(StringBuilder sb, String label, Object value) {
        String s = str(value);
        if (s != null && !s.isBlank()) {
            sb.append(label).append(": ").append(humanize(s)).append('\n');
        }
    }

    private static String humanize(String enumLike) {
        return enumLike.replace('_', ' ');
    }

    private static String formatSalary(Object minObj, Object maxObj) {
        Long min = toLong(minObj);
        Long max = toLong(maxObj);
        if (min == null && max == null) {
            return null;
        }
        if (min != null && max != null) {
            return "¥" + formatYen(min) + " - ¥" + formatYen(max);
        }
        Long single = min != null ? min : max;
        return "¥" + formatYen(single);
    }

    private static String formatYen(long value) {
        return String.format("%,d", value);
    }

    private static Long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private static Instant parseInstant(String iso, FetchContext ctx) {
        if (iso == null || iso.isBlank()) {
            return ctx.clock().instant();
        }
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return ctx.clock().instant();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * Best-effort fallback when {@code __NUXT_DATA__} is absent or unparseable. The raw
     * SSR HTML still carries title/href/company/location/salary and the "Apply from
     * Abroad" vs "Residents Only"/"Japan Only" badge, but not the technology-list tags
     * (those render empty server-side, hydrated client-side from the same JSON we
     * already tried above).
     */
    List<JobPosting> parseDomFallback(String html, String pageUrl, FetchContext ctx) {
        Document doc = Jsoup.parse(html, pageUrl);
        List<JobPosting> postings = new ArrayList<>();
        Elements cards = doc.select("li.job-item");
        for (Element card : cards) {
            try {
                JobPosting posting = parseDomCard(card, ctx);
                if (posting != null) {
                    postings.add(posting);
                }
            } catch (Exception e) {
                log.warn("japandev: skipping unparsable DOM job card: {}", e.toString());
            }
        }
        return postings;
    }

    private JobPosting parseDomCard(Element card, FetchContext ctx) {
        Element titleLink = card.selectFirst("h2 a.job-item__title, .job-item__title-box a");
        if (titleLink == null) {
            return null;
        }
        String title = titleLink.text().trim();
        String href = titleLink.attr("abs:href");
        if (href.isBlank()) {
            href = titleLink.attr("href");
        }
        String externalId = href.replaceAll("/+$", "");
        int lastSlash = externalId.lastIndexOf('/');
        if (lastSlash >= 0) {
            externalId = externalId.substring(lastSlash + 1);
        }
        if (externalId.isBlank()) {
            throw new IllegalStateException("japandev DOM card missing a usable href/id");
        }

        String contractTypeText = text(card.selectFirst(".job-item__contract-type"));
        String company = contractTypeText != null && contractTypeText.contains("・")
            ? contractTypeText.substring(0, contractTypeText.indexOf('・')).trim()
            : contractTypeText;

        Elements tagDescs = card.select(".job-tags .job__tag-desc");
        String city = tagDescs.size() > 0 ? tagDescs.get(0).text().trim() : null;
        String salaryRaw = tagDescs.size() > 1 ? tagDescs.get(1).text().replaceAll("\\s+", " ").trim() : null;

        String topTagsText = card.select("ul.job-top-tag-list li span").text();
        boolean visaFlag = topTagsText.toLowerCase().contains("apply from abroad");
        boolean residentsOnly = topTagsText.toLowerCase().contains("residents only")
            || topTagsText.toLowerCase().contains("japan only");
        if (residentsOnly) {
            return null;
        }

        return new JobPosting(
            SourceType.JAPAN_DEV,
            SOURCE_NAME,
            externalId,
            title,
            company,
            city,
            "Japan",
            href,
            title,
            List.of(),
            List.of(),
            salaryRaw,
            visaFlag,
            ctx.clock().instant()
        );
    }

    private static String text(Element el) {
        return el == null ? null : el.text().trim();
    }
}

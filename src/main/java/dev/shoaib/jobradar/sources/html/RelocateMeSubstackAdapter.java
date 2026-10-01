package dev.shoaib.jobradar.sources.html;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.JobSourceAdapter;
import dev.shoaib.jobradar.core.SourceFetchException;
import dev.shoaib.jobradar.core.SourceType;
import dev.shoaib.jobradar.pipeline.HttpAuthProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Global Move -- relocate.me's Substack newsletter ({@code relocateme.substack.com}).
 * Every Thursday it publishes a hand-curated job-list issue ("Weekly Hand-Curated Tech
 * Jobs With Relocation: Week N") whose body groups jobs under section headings
 * ({@code <h2>Back End</h2>}, "Full Stack", ...), each entry shaped as
 *
 * <pre>{@code
 * <li><p><strong><a href="APPLY_URL">Job Title</a></strong></p>
 *   <ul>
 *     <li><p><strong>Company:</strong> Percona (<a href=...>LinkedIn</a>)</p></li>
 *     <li><p><strong>Location:</strong> Amsterdam, Netherlands</p></li>
 *     <li><p><strong>Industry and size:</strong> ... | 201-500 employees</p></li>
 *     <li><p><strong>Job keywords:</strong> <em>Go, AWS, ...</em></p></li>
 *   </ul></li>
 * }</pre>
 *
 * <p>Discovery and body retrieval go through Substack's public JSON API rather than the
 * rendered pages -- {@code /api/v1/archive} for issue discovery (returns slug, date and
 * tags; allowed by Substack's robots.txt) and {@code /api/v1/posts/{slug}} for the body
 * ({@code body_html}) -- so no JS rendering is needed.
 *
 * <p><b>Paywall:</b> weekly issues are paid-subscriber-only. This adapter never
 * circumvents that; it authenticates as the owner's own paid account when
 * {@code SUBSTACK_COOKIE} carries their browser session cookie (wired per host via
 * {@code app.http.auth.cookies}), and otherwise parses whatever Substack chooses to
 * serve anonymously (free issues, and free previews of paid ones).
 *
 * <p><b>Self-verification:</b> every issue's free intro advertises its own per-section
 * totals ("Back End -- 33 roles (incl. 8 remote)"), which this adapter parses and
 * reconciles against what it actually extracted:
 * <ul>
 *   <li>full body parsed but fewer entries than advertised -> WARN (markup drift or a
 *       half-served body -- the parse silently losing jobs is the one failure mode a
 *       job alerter can't afford);</li>
 *   <li>preview served although a cookie is configured -> WARN (the session cookie has
 *       expired or is malformed and needs re-exporting);</li>
 *   <li>preview served with no cookie configured -> INFO stating exactly how many
 *       relevant roles this issue holds behind the paywall.</li>
 * </ul>
 */
@Component
public class RelocateMeSubstackAdapter implements JobSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(RelocateMeSubstackAdapter.class);
    private static final String SOURCE_NAME = "relocateme-substack";

    /** Query params that vary per-issue without identifying a different job. */
    private static final Pattern TRACKING_PARAM = Pattern.compile("(?i)^(utm_.*|ref|source|src|embed)$");

    /** "Back End – 33 roles (incl. 8 remote)" -> name="Back End", count=33. */
    private static final Pattern SECTION_COUNT = Pattern.compile(
        "^(?<name>.+?)\\s*[–—-]\\s*(?<count>\\d+)\\s+roles?\\b");

    private final RelocateMeSubstackProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Pattern fallbackTitlePattern;
    private final boolean cookieConfigured;

    public RelocateMeSubstackAdapter(RelocateMeSubstackProperties props, HttpAuthProperties auth) {
        this.props = props;
        this.fallbackTitlePattern = props.fallbackTitlePattern() == null || props.fallbackTitlePattern().isBlank()
            ? null : Pattern.compile(props.fallbackTitlePattern());
        this.cookieConfigured = auth.cookieFor(URI.create(props.baseUrl()).getHost()).isPresent();
    }

    /** Whether a session cookie is configured for the Substack host (paid issues readable). */
    boolean cookieConfigured() {
        return cookieConfigured;
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
        List<PostRef> posts;
        try {
            posts = discoverPosts(ctx);
        } catch (Exception e) {
            throw new SourceFetchException("relocateme-substack: archive discovery failed", e);
        }

        // Archive is newest-first; first occurrence of an apply URL wins, so a job
        // re-listed across several weekly issues keeps its newest issue's data.
        Map<String, JobPosting> byExternalId = new LinkedHashMap<>();
        for (PostRef post : posts) {
            try {
                Optional<String> json = ctx.http().tryGet(props.baseUrl() + "/api/v1/posts/" + post.slug());
                if (json.isEmpty()) {
                    log.warn("relocateme-substack: post {} vanished (404), skipping", post.slug());
                    continue;
                }
                PostParseResult result = parsePost(json.get(), post);
                logPostDiagnostics(post, result);
                for (JobPosting posting : result.postings()) {
                    byExternalId.putIfAbsent(posting.externalId(), posting);
                }
            } catch (Exception e) {
                log.warn("relocateme-substack: skipping post {} due to error: {}", post.slug(), e.toString());
            }
        }
        return List.copyOf(byExternalId.values());
    }

    /**
     * Pages {@code /api/v1/archive} (newest-first) selecting job-list issues by slug
     * prefix or tag, until {@code maxPosts} are collected, the lookback window is
     * exhausted, or the archive ends.
     */
    private List<PostRef> discoverPosts(FetchContext ctx) throws Exception {
        Instant cutoff = ctx.clock().instant().minus(props.lookbackDays(), ChronoUnit.DAYS);
        List<PostRef> selected = new ArrayList<>();
        int offset = 0;
        while (selected.size() < props.maxPosts()) {
            String url = props.baseUrl() + "/api/v1/archive?sort=new&offset=" + offset
                + "&limit=" + props.archivePageSize();
            Optional<String> json = ctx.http().tryGet(url);
            if (json.isEmpty()) {
                break;
            }
            List<PostRef> page = parseArchivePage(json.get());
            if (page.isEmpty()) {
                break;
            }
            for (PostRef post : page) {
                if (post.postDate().isBefore(cutoff)) {
                    return selected;
                }
                if (selected.size() < props.maxPosts() && isJobListPost(post)) {
                    selected.add(post);
                }
            }
            offset += page.size();
        }
        return selected;
    }

    /** Package-visible pure parsing so tests can feed fixture JSON directly. */
    List<PostRef> parseArchivePage(String json) throws Exception {
        List<PostRef> result = new ArrayList<>();
        for (JsonNode post : mapper.readTree(json)) {
            String slug = post.path("slug").asText("");
            String dateRaw = post.path("post_date").asText("");
            if (slug.isBlank() || dateRaw.isBlank()) {
                continue;
            }
            List<String> tags = new ArrayList<>();
            for (JsonNode tag : post.path("postTags")) {
                tags.add(tag.path("name").asText(""));
            }
            result.add(new PostRef(slug, post.path("title").asText(""),
                Instant.parse(dateRaw), post.path("audience").asText(""), tags));
        }
        return result;
    }

    private boolean isJobListPost(PostRef post) {
        boolean slugMatch = props.postSlugPrefixes().stream()
            .anyMatch(prefix -> post.slug().startsWith(prefix));
        boolean tagMatch = post.tags().stream().anyMatch(tag ->
            props.postTags().stream().anyMatch(wanted -> wanted.equalsIgnoreCase(tag)));
        return slugMatch || tagMatch;
    }

    /** Package-visible pure parsing so tests can feed fixture JSON directly. */
    PostParseResult parsePost(String postJson, PostRef post) throws Exception {
        JsonNode root = mapper.readTree(postJson);
        String bodyHtml = root.path("body_html").asText("");
        if (bodyHtml.isBlank()) {
            log.warn("relocateme-substack: post {} has no body_html", post.slug());
            return new PostParseResult(List.of(), Map.of(), Map.of(), false, false);
        }
        Document doc = Jsoup.parse(bodyHtml, props.baseUrl());
        Map<String, Integer> advertised = advertisedSectionCounts(doc);

        List<JobPosting> postings = new ArrayList<>();
        Map<String, Integer> parsedCounts = new LinkedHashMap<>();
        boolean anySectionFound = false;
        for (Element heading : doc.select("h1, h2, h3, h4, h5, h6")) {
            if (!isWantedSection(heading.text())) {
                continue;
            }
            anySectionFound = true;
            int count = 0;
            for (Element entry : sectionEntries(heading)) {
                JobPosting posting = parseEntry(entry, post);
                if (posting != null) {
                    postings.add(posting);
                    count++;
                }
            }
            parsedCounts.merge(normalize(heading.text()), count, Integer::sum);
        }

        boolean paidPreview = !anySectionFound && "only_paid".equals(root.path("audience").asText(""));
        if (!anySectionFound) {
            postings.addAll(fallbackSweep(doc, post));
        }
        return new PostParseResult(postings, advertised, parsedCounts, anySectionFound, paidPreview);
    }

    /**
     * The free intro of every issue indexes its own sections with per-section totals
     * ("Back End – 33 roles (incl. 8 remote)" linking to {@code .../i/<postid>/back-end}).
     * Present in both the full body and the anonymous preview, which makes it a ground
     * truth to reconcile the parse against. Keys are {@link #normalize(String)}d names.
     */
    Map<String, Integer> advertisedSectionCounts(Document doc) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Element link : doc.select("li a[href*='substack.com/i/']")) {
            Element li = link.closest("li");
            if (li == null) {
                continue;
            }
            Matcher m = SECTION_COUNT.matcher(li.text().trim());
            if (m.find()) {
                counts.putIfAbsent(normalize(m.group("name")), Integer.parseInt(m.group("count")));
            }
        }
        return counts;
    }

    /**
     * Package-visible so tests can assert the exact operator guidance. One log line per
     * issue describing what was (or wasn't) extracted and, when short of the advertised
     * totals, why that might be and what to do about it.
     */
    void logPostDiagnostics(PostRef post, PostParseResult result) {
        if (result.sectionsFound()) {
            List<String> shortfalls = new ArrayList<>();
            for (Map.Entry<String, Integer> section : result.parsedCounts().entrySet()) {
                Integer expected = result.advertisedCounts().get(section.getKey());
                if (expected != null && section.getValue() < expected) {
                    shortfalls.add(section.getKey() + ": got " + section.getValue() + " of " + expected);
                }
            }
            if (shortfalls.isEmpty()) {
                log.info("relocateme-substack: {} -> {} job(s) extracted, all advertised section totals met",
                    post.slug(), result.postings().size());
            } else {
                log.warn("relocateme-substack: {} parsed fewer entries than the issue advertises ({}). "
                    + "Likely entry-markup drift or a partially served body -- compare a raw "
                    + "/api/v1/posts/{} response against the fixtures before trusting this run's removals.",
                    post.slug(), String.join("; ", shortfalls), post.slug());
            }
            return;
        }
        if (result.paidPreview()) {
            String behindPaywall = result.advertisedCounts().entrySet().stream()
                .filter(e -> props.sections().stream().anyMatch(s -> normalize(s).equals(e.getKey())))
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
            String inventory = behindPaywall.isBlank() ? "" : " This issue advertises " + behindPaywall
                + " role(s) behind the paywall.";
            if (cookieConfigured) {
                log.warn("relocateme-substack: an auth cookie is configured but Substack still served the free "
                    + "preview of {} -- the session cookie has likely expired or is malformed (expected "
                    + "\"substack.sid=<value>\"). Re-export it from a logged-in browser into SUBSTACK_COOKIE.{}",
                    post.slug(), inventory);
            } else {
                log.info("relocateme-substack: {} is paid-only and only the free preview was served.{} "
                    + "Set SUBSTACK_COOKIE to a paid subscriber's session cookie to ingest them.",
                    post.slug(), inventory);
            }
        }
    }

    /** Entry {@code <li>}s of the {@code <ul>} siblings between this heading and the next one. */
    private List<Element> sectionEntries(Element heading) {
        List<Element> entries = new ArrayList<>();
        for (Element sib = heading.nextElementSibling(); sib != null; sib = sib.nextElementSibling()) {
            if (sib.tagName().matches("h[1-6]")) {
                break;
            }
            if (sib.tagName().equals("ul") || sib.tagName().equals("ol")) {
                entries.addAll(sib.select("> li"));
            }
        }
        return entries;
    }

    /**
     * Layout-drift safety net: when no configured section heading is present, scan every
     * job-shaped entry in the whole body and keep the ones whose title or keywords look
     * backend-ish per {@code fallback-title-pattern}.
     */
    private List<JobPosting> fallbackSweep(Document doc, PostRef post) {
        if (fallbackTitlePattern == null) {
            return List.of();
        }
        List<JobPosting> postings = new ArrayList<>();
        for (Element li : doc.select("li")) {
            if (li.selectFirst("> ul, > ol") == null) {
                continue; // job entries carry a nested detail list; index/prose bullets don't
            }
            JobPosting posting = parseEntry(li, post);
            if (posting == null) {
                continue;
            }
            String haystack = posting.title() + " " + String.join(", ", posting.techTags());
            if (fallbackTitlePattern.matcher(haystack).find()) {
                postings.add(posting);
            }
        }
        if (!postings.isEmpty()) {
            log.info("relocateme-substack: no configured section headings in {}; fallback sweep kept {} entries",
                post.slug(), postings.size());
        }
        return postings;
    }

    /** One {@code <li>} job entry -> posting; null when it isn't entry-shaped. */
    private JobPosting parseEntry(Element li, PostRef post) {
        Entry entry = parseEntryFields(li);
        if (entry == null) {
            return null;
        }
        StringBuilder description = new StringBuilder(String.join("\n", entry.details()));
        if (description.length() > 0) {
            description.append("\n\n");
        }
        description.append("Listed in \"").append(post.title()).append("\" (")
            .append(post.postDate().toString(), 0, 10).append(")");

        // The newsletter's premise is that every relocation-tagged entry with a location
        // comes with visa/relocation support, even when the bullet doesn't spell it out.
        boolean visaFlag = !entry.visaLines().isEmpty();
        if (!visaFlag && entry.country() != null && post.tags().stream()
                .anyMatch(t -> t.toLowerCase(Locale.ROOT).contains("relocation"))) {
            visaFlag = true;
        }

        return new JobPosting(
            SourceType.RELOCATE_ME_SUBSTACK,
            SOURCE_NAME,
            canonicalUrl(entry.applyUrl()),
            entry.title(),
            entry.company(),
            entry.city(),
            entry.country(),
            entry.applyUrl(),
            description.toString(),
            entry.keywords(),
            entry.visaLines(),
            null,
            visaFlag,
            post.postDate()
        );
    }

    /**
     * Every job entry in an issue body, across all sections, each tagged with the
     * nearest preceding heading. Used by {@link RelocateMeWeeklyArchiver} to keep a full
     * copy of each issue, independent of the configured {@code sections} filter.
     */
    IssueEntries parseAllEntries(String postJson) throws Exception {
        JsonNode root = mapper.readTree(postJson);
        String audience = root.path("audience").asText("");
        String subtitle = root.path("subtitle").asText("");
        String bodyHtml = root.path("body_html").asText("");
        if (bodyHtml.isBlank()) {
            return new IssueEntries(audience, subtitle, List.of(), Map.of(), List.of());
        }
        Document doc = Jsoup.parse(bodyHtml, props.baseUrl());
        List<SectionEntry> entries = new ArrayList<>();
        List<EmbeddedTable> tables = new ArrayList<>();
        Map<String, Integer> positions = new LinkedHashMap<>();
        String section = "";
        // Combined selector -> matches come back in document order.
        for (Element el : doc.select("h1, h2, h3, h4, h5, h6, li, div.datawrapper-wrap")) {
            if (el.tagName().matches("h[1-6]")) {
                section = el.text().trim();
                continue;
            }
            if (el.tagName().equals("div")) {
                // The first issues embed each section as a Datawrapper table instead of a list.
                Element iframe = el.selectFirst("iframe[src]");
                if (iframe != null) {
                    String src = iframe.attr("src");
                    tables.add(new EmbeddedTable(section, (src.endsWith("/") ? src : src + "/") + "dataset.csv"));
                }
                continue;
            }
            if (el.selectFirst("> ul, > ol") == null && el.select("> p").size() < 2) {
                continue; // detail bullets and section-index bullets carry no detail lines
            }
            Entry entry = parseEntryFields(el);
            if (entry != null) {
                int position = positions.merge(section, 1, Integer::sum);
                entries.add(new SectionEntry(section, position, entry));
            }
        }
        return new IssueEntries(audience, subtitle, entries, advertisedSectionCounts(doc), tables);
    }

    /** "[Backend Developer](https://...) ✅" -> title, url, trailing marker. */
    private static final Pattern MARKDOWN_LINK = Pattern.compile(
        "^\\s*\\[(?<title>.+?)\\]\\((?<url>[^)\\s]+)\\)\\s*(?<rest>.*)$", Pattern.DOTALL);

    /**
     * Rows of an embedded Datawrapper table ({@code dataset.csv}, header
     * {@code Role,Location,Company,Size,Industry,LinkedIn page,Job keywords}) as entries.
     * Columns are matched by header name, so reordering or a missing column is tolerated;
     * rows whose Role cell carries no markdown link (no apply URL) are dropped.
     */
    static List<Entry> entriesFromCsv(String csv) {
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> col = new LinkedHashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            col.putIfAbsent(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        List<Entry> entries = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            Matcher role = MARKDOWN_LINK.matcher(cell(row, col, "role"));
            if (!role.matches()) {
                continue;
            }
            String location = blankToNull(cell(row, col, "location"));
            String company = blankToNull(cell(row, col, "company"));
            Matcher linkedin = MARKDOWN_LINK.matcher(cell(row, col, "linkedin page"));
            String companyLinkedinUrl = linkedin.matches() ? linkedin.group("url") : null;
            String industry = cell(row, col, "industry");
            String size = cell(row, col, "size");
            String industrySize = industry.isBlank() && size.isBlank() ? null
                : industry.isBlank() ? size : size.isBlank() ? industry : industry + " | " + size;
            List<String> keywords = new ArrayList<>();
            for (String k : cell(row, col, "job keywords").split(",")) {
                if (!k.isBlank()) {
                    keywords.add(k.trim());
                }
            }
            List<String> details = new ArrayList<>();
            if (company != null) {
                details.add("Company: " + company);
            }
            if (location != null) {
                details.add("Location: " + location);
            }
            if (industrySize != null) {
                details.add("Industry and size: " + industrySize);
            }
            if (!keywords.isEmpty()) {
                details.add("Job keywords: " + String.join(", ", keywords));
            }
            if (!role.group("rest").isBlank()) {
                details.add("Marker: " + role.group("rest").trim());
            }
            String title = role.group("title").trim();
            String rawLocation = location;
            Matcher titleTag = JobLocation.REMOTE_TAG.matcher(title);
            if (titleTag.find()) {
                rawLocation = titleTag.group() + " " + nullToEmpty(location);
                title = title.substring(titleTag.end()).trim();
            }
            JobLocation where = JobLocation.parse(rawLocation);
            entries.add(new Entry(title, role.group("url"), company, companyLinkedinUrl, where,
                industrySize, List.copyOf(keywords), List.of(), List.copyOf(details)));
        }
        return entries;
    }

    private static String cell(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() ? "" : row.get(i).trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** RFC 4180: quoted fields, doubled quotes, commas and newlines inside quotes. */
    static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        String text = csv.startsWith("\uFEFF") ? csv.substring(1) : csv;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                if (row.size() > 1 || !row.get(0).isBlank()) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        row.add(field.toString());
        if (row.size() > 1 || !row.get(0).isBlank()) {
            rows.add(row);
        }
        return rows;
    }

    /** Field extraction shared by the match feed and the archive; null when not entry-shaped. */
    private Entry parseEntryFields(Element li) {
        Element titleP = li.selectFirst("> p");
        Element titleLink = titleP == null ? null : titleP.selectFirst("a[href]");
        if (titleLink == null) {
            return null;
        }
        String applyUrl = titleLink.absUrl("href").isBlank() ? titleLink.attr("href") : titleLink.absUrl("href");
        String title = titleLink.text().trim();
        if (title.isBlank() || applyUrl.contains("substack.com")) {
            return null; // section-index bullets link back into the post itself
        }

        String company = null;
        String companyLinkedinUrl = null;
        String location = titleLineLocation(titleP);
        String industrySize = null;
        List<String> keywords = new ArrayList<>();
        List<String> visaLines = new ArrayList<>();
        List<String> details = new ArrayList<>();

        // Detail lines are a nested list in current issues, sibling <p>s in older ones.
        List<Element> detailLines = new ArrayList<>(li.select("> ul > li, > ol > li"));
        detailLines.addAll(li.select("> p").stream().filter(p -> p != titleP).toList());
        for (Element detail : detailLines) {
            String line = detail.text().trim();
            if (line.isBlank()) {
                continue;
            }
            details.add(line);

            int colon = line.indexOf(':');
            String label = colon > 0 ? line.substring(0, colon).trim().toLowerCase(Locale.ROOT) : "";
            String value = colon > 0 ? line.substring(colon + 1).trim() : line;

            switch (label) {
                case "company" -> {
                    company = stripLinkParens(detail, value);
                    Element linkedin = detail.selectFirst("a[href*=linkedin.com]");
                    companyLinkedinUrl = linkedin == null ? null : linkedin.attr("href");
                }
                case "location", "locations" -> location = value;
                case "industry and size" -> industrySize = value;
                case "job keywords", "keywords", "tech stack" -> {
                    String plain = value.trim();
                    if (!plain.isBlank() && !plain.equalsIgnoreCase("Not specified")) {
                        for (String keyword : plain.split(",")) {
                            String k = keyword.trim();
                            if (!k.isBlank()) {
                                keywords.add(k);
                            }
                        }
                    }
                }
                default -> { /* free-form line; kept in details */ }
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("visa") || lower.contains("relocation")) {
                visaLines.add(line);
            }
        }
        // A few entries carry the remote tag inside the link text instead of before it.
        Matcher titleTag = JobLocation.REMOTE_TAG.matcher(title);
        if (titleTag.find()) {
            location = titleTag.group() + " " + (location == null ? "" : location);
            title = title.substring(titleTag.end()).trim();
        }
        return new Entry(title, applyUrl, company, companyLinkedinUrl, JobLocation.parse(location),
            industrySize, List.copyOf(keywords), List.copyOf(visaLines), List.copyOf(details));
    }

    /**
     * Current issues put the location on the title line itself, either after the link
     * ({@code <strong><a>Title</a></strong> in Amsterdam, Netherlands 🇳🇱}) or as a remote
     * tag before it ({@code [REMOTE – LATAM] <strong><a>Title</a></strong>}). Returns the
     * raw remainder for {@link JobLocation#parse}; null when there is none.
     */
    private static String titleLineLocation(Element titleP) {
        Element clone = titleP.clone();
        clone.select("a").remove();
        String rest = clone.text().trim();
        return rest.isBlank() ? null : rest;
    }

    /**
     * "Percona (LinkedIn)" -> "Percona": drop the trailing link-only parenthetical by
     * removing anchors from a clone and cleaning the leftover "()".
     */
    private static String stripLinkParens(Element detail, String fallback) {
        Element clone = detail.clone();
        clone.select("a").remove();
        String text = clone.text().trim();
        int colon = text.indexOf(':');
        String value = colon > 0 ? text.substring(colon + 1).trim() : fallback;
        value = value.replaceAll("\\(\\s*\\)", "").trim();
        return value.isBlank() ? fallback : value;
    }

    /**
     * Stable externalId for an apply link: same job re-listed across weeks dedupes even
     * when tracking params differ. Keeps meaningful params (gh_jid, ashby_jid, ...).
     */
    static String canonicalUrl(String url) {
        try {
            URI u = new URI(url.trim());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            String path = u.getPath() == null ? "" : u.getPath();
            if (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            StringBuilder query = new StringBuilder();
            if (u.getQuery() != null) {
                for (String param : u.getQuery().split("&")) {
                    String name = param.contains("=") ? param.substring(0, param.indexOf('=')) : param;
                    if (!TRACKING_PARAM.matcher(name).matches()) {
                        query.append(query.length() == 0 ? "" : "&").append(param);
                    }
                }
            }
            return (u.getScheme() == null ? "https" : u.getScheme().toLowerCase(Locale.ROOT))
                + "://" + host + path + (query.length() == 0 ? "" : "?" + query);
        } catch (URISyntaxException e) {
            return url.trim();
        }
    }

    private boolean isWantedSection(String headingText) {
        String normalized = normalize(headingText);
        return props.sections().stream().anyMatch(s -> normalized.startsWith(normalize(s)));
    }

    /** "Back End (33 roles)" / "back-end" / "Back End" all compare equal. */
    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ").trim();
    }

    record PostRef(String slug, String title, Instant postDate, String audience, List<String> tags) {
    }

    /**
     * Everything one issue yielded: the postings plus the reconciliation inputs --
     * per-section totals the issue advertises about itself vs. what was actually
     * extracted ({@code parsedCounts} covers only the wanted sections), whether any
     * configured section heading existed in the body at all, and whether the body was
     * a paid post's free preview.
     */
    record PostParseResult(List<JobPosting> postings, Map<String, Integer> advertisedCounts,
        Map<String, Integer> parsedCounts, boolean sectionsFound, boolean paidPreview) {
    }

    /** One job entry's raw fields; {@code visaLines} are detail lines mentioning visa/relocation. */
    record Entry(String title, String applyUrl, String company, String companyLinkedinUrl, JobLocation where,
        String industrySize, List<String> keywords, List<String> visaLines, List<String> details) {

        String location() {
            return where.location();
        }

        String city() {
            return where.city();
        }

        String country() {
            return where.country();
        }
    }

    /** {@code position} is 1-based within {@code section}. */
    record SectionEntry(String section, int position, Entry entry) {
    }

    /**
     * {@code tables} are embedded Datawrapper sections whose rows live at {@code csvUrl}
     * and still need fetching; {@code entries} holds only what the HTML itself contained.
     */
    record IssueEntries(String audience, String subtitle, List<SectionEntry> entries,
        Map<String, Integer> advertisedCounts, List<EmbeddedTable> tables) {
    }

    record EmbeddedTable(String section, String csvUrl) {
    }
}

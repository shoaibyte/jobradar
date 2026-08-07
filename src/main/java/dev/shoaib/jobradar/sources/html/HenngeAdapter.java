package dev.shoaib.jobradar.sources.html;

import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.JobSourceAdapter;
import dev.shoaib.jobradar.core.SourceFetchException;
import dev.shoaib.jobradar.core.SourceType;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HENNGE (Section 5.5). <b>Known gap:</b> the configured
 * {@code app.sources.hennge.base-url} (frozen at
 * {@code https://hennge.com/global/recruit/}) currently 404s -- the captured fixture
 * ({@code fixtures/hennge/recruit.html}) is HENNGE's own WordPress "Not Found" page.
 * The real careers listing lives on a different domain entirely
 * ({@code https://recruit.hennge.com/en/}, linked from that 404 page's footer), which
 * we have no fixture for. See notes/sources-html.md for the full writeup.
 *
 * <p>Since we cannot verify real markup, this parses generically (headings/links that
 * look like job postings) and -- critically -- degrades to an empty list rather than
 * throwing when nothing recognizable is found, so a moved/broken careers page never
 * takes down the rest of the ingest run. The one real, fully-detailed HENNGE posting
 * available anywhere in the fixture set was captured via Relocate.me
 * (fixtures/relocateme/hennge-senior-software-engineer-backend-infrastructure.html) and
 * is exercised by {@code RelocateMeAdapterTest}, not here.
 */
@Component
public class HenngeAdapter implements JobSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(HenngeAdapter.class);
    private static final String SOURCE_NAME = "custom-html:hennge";
    private static final String COMPANY = "HENNGE";
    private static final Set<String> TECH_KEYWORDS = Set.of(
        "Python", "Go", "Golang", "Java", "Kotlin", "Scala", "Kubernetes", "AWS", "GCP",
        "Terraform", "Docker", "React", "TypeScript", "SRE", "Infrastructure", "Backend");

    private final String baseUrl;
    private final boolean enabled;
    private final JobRecordRepository jobRecordRepository;

    public HenngeAdapter(
        @Value("${app.sources.hennge.base-url}") String baseUrl,
        @Value("${app.sources.hennge.enabled}") boolean enabled,
        JobRecordRepository jobRecordRepository) {
        this.baseUrl = baseUrl;
        this.enabled = enabled;
        this.jobRecordRepository = jobRecordRepository;
    }

    @Override
    public String name() {
        return SOURCE_NAME;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<JobPosting> fetch(FetchContext ctx) throws SourceFetchException {
        if (!enabled()) {
            return List.of();
        }
        Optional<String> html;
        try {
            html = ctx.http().tryGet(baseUrl);
        } catch (Exception e) {
            throw new SourceFetchException("hennge: failed to fetch " + baseUrl, e);
        }
        if (html.isEmpty()) {
            log.warn("hennge: {} returned no content (404?)", baseUrl);
            return List.of();
        }

        List<Candidate> candidates = parseCandidates(html.get(), baseUrl);
        if (candidates.isEmpty()) {
            log.warn("hennge: no recognizable job postings found at {} "
                + "(page may have moved -- see notes/sources-html.md)", baseUrl);
            return List.of();
        }

        List<JobPosting> results = new ArrayList<>();
        for (Candidate c : candidates) {
            try {
                boolean known = jobRecordRepository.findBySourceAndExternalId(SOURCE_NAME, c.externalId()).isPresent();
                results.add(toJobPosting(c, ctx, known));
            } catch (Exception e) {
                log.warn("hennge: skipping candidate {} due to parse error: {}", c.externalId(), e.toString());
            }
        }
        return results;
    }

    /**
     * Best-effort, unverified against real markup (see class javadoc): looks for
     * headings that read like a job title within the main content area, plus any
     * anchors under the recruiting path, and pairs each with the nearest following
     * paragraph as a description.
     */
    List<Candidate> parseCandidates(String html, String pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl);
        List<Candidate> result = new ArrayList<>();

        for (Element card : doc.select(
            "article, .job-list__item, .p-job, .c-job-card, [class*=job-item], [class*=position-card]")) {
            try {
                Candidate c = fromCard(card);
                if (c != null) {
                    result.add(c);
                }
            } catch (Exception e) {
                log.warn("hennge: skipping unparsable job card: {}", e.toString());
            }
        }
        return result;
    }

    private Candidate fromCard(Element card) {
        Element heading = card.selectFirst("h1, h2, h3, h4");
        Element link = card.selectFirst("a[href]");
        if (heading == null && link == null) {
            return null;
        }
        String title = heading != null ? heading.text().trim() : link.text().trim();
        if (title.isBlank() || title.equalsIgnoreCase("Not Found")) {
            return null;
        }
        String href = link != null ? link.attr("abs:href") : baseUrl;
        String description = card.text().trim();
        String externalId = href.replaceAll("/+$", "");
        int lastSlash = externalId.lastIndexOf('/');
        externalId = lastSlash >= 0 ? externalId.substring(lastSlash + 1) : externalId;
        if (externalId.isBlank()) {
            externalId = title;
        }
        return new Candidate(externalId, title, href, description);
    }

    private JobPosting toJobPosting(Candidate c, FetchContext ctx, boolean known) {
        String description = known ? "" : c.description();
        List<String> techTags = new ArrayList<>();
        String upper = description.toUpperCase(Locale.ROOT);
        for (String keyword : TECH_KEYWORDS) {
            if (upper.contains(keyword.toUpperCase(Locale.ROOT))) {
                techTags.add(keyword);
            }
        }
        boolean visaFlag = description.toLowerCase(Locale.ROOT).matches("(?s).*(visa|sponsor|relocat).*");

        return new JobPosting(
            SourceType.CUSTOM_HTML,
            SOURCE_NAME,
            c.externalId(),
            c.title(),
            COMPANY,
            "",
            "Japan",
            c.url(),
            description,
            techTags,
            List.of(),
            null,
            visaFlag,
            ctx.clock().instant()
        );
    }

    record Candidate(String externalId, String title, String url, String description) {
    }
}

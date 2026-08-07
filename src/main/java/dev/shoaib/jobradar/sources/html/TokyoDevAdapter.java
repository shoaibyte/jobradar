package dev.shoaib.jobradar.sources.html;

import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.JobSourceAdapter;
import dev.shoaib.jobradar.core.SourceFetchException;
import dev.shoaib.jobradar.core.SourceType;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * TokyoDev (Section 5.3). Job cards are grouped by company ({@code <li id="...">} per
 * company, one or more job rows inside); each row carries an explicit tag strip
 * ({@code a.tag}) mixing meta info (salary, Japanese-level, remote-level,
 * "Apply from abroad") with tech/role tags. Meta tags are told apart from tech tags by
 * their {@code href} (a fixed, small vocabulary under {@code /jobs/...}), not by text or
 * position, so a new tech tag never gets misfiled as meta.
 */
@Component
public class TokyoDevAdapter implements JobSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(TokyoDevAdapter.class);
    private static final String SOURCE_NAME = "tokyodev";

    /** Known non-tech tag hrefs (relative to /jobs/); anything else is treated as a tech/role tag. */
    private static final Set<String> META_TAG_SLUGS = Set.of(
        "salary-data", "japanese-required", "no-japanese-required", "residents-only",
        "apply-from-abroad", "no-remote", "partially-remote", "fully-remote");

    private final String baseUrl;
    private final boolean enabled;
    private final JobRecordRepository jobRecordRepository;

    public TokyoDevAdapter(
        @Value("${app.sources.tokyodev.base-url}") String baseUrl,
        @Value("${app.sources.tokyodev.enabled}") boolean enabled,
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
        String html;
        try {
            html = ctx.http().get(baseUrl);
        } catch (Exception e) {
            throw new SourceFetchException("tokyodev: failed to fetch " + baseUrl, e);
        }

        List<JobCard> cards = parseListing(html, baseUrl);
        List<JobPosting> results = new ArrayList<>();
        for (JobCard card : cards) {
            try {
                boolean known = jobRecordRepository.findBySourceAndExternalId(SOURCE_NAME, card.externalId()).isPresent();
                String description;
                if (known) {
                    description = card.summary();
                } else {
                    description = fetchDescription(ctx, card).orElse(card.summary());
                }
                results.add(toJobPosting(card, description, ctx.clock().instant()));
            } catch (Exception e) {
                log.warn("tokyodev: skipping job {} due to parse error: {}", card.externalId(), e.toString());
            }
        }
        return results;
    }

    /** Package-visible pure parsing so tests can feed fixture HTML directly. */
    List<JobCard> parseListing(String html, String pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl);
        List<JobCard> result = new ArrayList<>();
        for (Element companyLi : doc.select("li:has(h3 a[href^=/companies/])")) {
            try {
                result.addAll(parseCompanyBlock(companyLi));
            } catch (Exception e) {
                log.warn("tokyodev: skipping unparsable company block: {}", e.toString());
            }
        }
        return result;
    }

    private List<JobCard> parseCompanyBlock(Element companyLi) {
        List<JobCard> result = new ArrayList<>();
        Element companyLink = companyLi.selectFirst("h3 a[href^=/companies/]");
        String company = companyLink.text().trim();

        for (Element titleLink : companyLi.select("a[href~=^/companies/[^/]+/jobs/.+$]")) {
            try {
                result.add(parseJobRow(titleLink, company));
            } catch (Exception e) {
                log.warn("tokyodev: skipping unparsable job row for {}: {}", company, e.toString());
            }
        }
        return result;
    }

    private JobCard parseJobRow(Element titleLink, String company) {
        String title = titleLink.text().trim();
        String href = titleLink.attr("href");
        String externalId = href.replaceFirst("^/companies/([^/]+)/jobs/", "$1/");

        Element titleDiv = titleLink.parent();
        Element tagsDiv = titleDiv != null ? titleDiv.nextElementSibling() : null;

        String salaryRaw = null;
        boolean visaFlag = false;
        List<String> techTags = new ArrayList<>();
        List<String> metaNotes = new ArrayList<>();

        if (tagsDiv != null) {
            for (Element tag : tagsDiv.select("a.tag")) {
                String tagHref = tag.attr("href");
                String slug = tagHref.replaceFirst("^/jobs/", "");
                String text = tag.text().trim();
                if (text.isBlank()) {
                    continue;
                }
                if ("salary-data".equals(slug)) {
                    salaryRaw = text;
                } else if ("apply-from-abroad".equals(slug)) {
                    visaFlag = true;
                    metaNotes.add(text);
                } else if (META_TAG_SLUGS.contains(slug)) {
                    metaNotes.add(text);
                } else {
                    techTags.add(text);
                }
            }
        }

        String summary = title + " at " + company
            + (metaNotes.isEmpty() ? "" : ". " + String.join(", ", metaNotes) + ".")
            + (salaryRaw != null ? " Salary: " + salaryRaw + "." : "");

        return new JobCard(externalId, href, title, company, techTags, salaryRaw, visaFlag, summary.trim());
    }

    private Optional<String> fetchDescription(FetchContext ctx, JobCard card) {
        try {
            String root = rootOf(baseUrl);
            String detailUrl = root + card.href();
            return ctx.http().tryGet(detailUrl).map(html -> extractDescription(html, detailUrl));
        } catch (Exception e) {
            log.warn("tokyodev: detail fetch failed for {}: {}", card.externalId(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * No detail-page fixture was captured for TokyoDev (only the listing page), so this
     * extraction is written defensively against generic article/main-content shapes and
     * is not verified against real TokyoDev detail markup -- see notes/sources-html.md.
     */
    private String extractDescription(String html, String pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl);
        Element content = doc.selectFirst("article, main, [class*=job-description], [class*=description]");
        String text = content != null ? content.text().trim() : doc.body().text().trim();
        return text;
    }

    private static String rootOf(String baseUrl) {
        URI uri = URI.create(baseUrl);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private JobPosting toJobPosting(JobCard card, String description, Instant postedAt) {
        return new JobPosting(
            SourceType.TOKYO_DEV,
            SOURCE_NAME,
            card.externalId(),
            card.title(),
            card.company(),
            "",
            "Japan",
            rootOf(baseUrl) + card.href(),
            description,
            card.techTags(),
            List.of(),
            card.salaryRaw(),
            card.visaFlag(),
            postedAt
        );
    }

    record JobCard(String externalId, String href, String title, String company, List<String> techTags,
        String salaryRaw, boolean visaFlag, String summary) {

        JobCard {
            techTags = techTags == null ? List.of() : List.copyOf(new LinkedHashSet<>(techTags));
        }
    }
}

package dev.shoaib.jobradar.sources.html;

import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.JobSourceAdapter;
import dev.shoaib.jobradar.core.SourceFetchException;
import dev.shoaib.jobradar.core.SourceType;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Relocate.me (Section 5.1). Listing job cards link to
 * {@code /{country}/{city}/{company}/{slug}-{NNNNN}}; the trailing integer is the
 * stable externalId. Detail pages carry the full relocation-benefits list, tech stack
 * (folded into the free-text "Position" section, not a separate DOM block -- see
 * notes/sources-html.md), and keyword tags.
 */
@Component
public class RelocateMeAdapter implements JobSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(RelocateMeAdapter.class);
    private static final String SOURCE_NAME = "relocateme";

    /** {country}/{city}/{company}/{slug}-{id}, e.g. /netherlands/amsterdam/picnic/backend-engineer-10205 */
    private static final Pattern JOB_HREF = Pattern.compile(
        "^/(?<country>[a-z0-9-]+)/(?<city>[a-z0-9-]+)/(?<company>[a-z0-9-]+)/(?<slug>[a-z0-9-]+)-(?<id>\\d+)/?$");

    private final RelocateMeProperties props;
    private final JobRecordRepository jobRecordRepository;

    public RelocateMeAdapter(RelocateMeProperties props, JobRecordRepository jobRecordRepository) {
        this.props = props;
        this.jobRecordRepository = jobRecordRepository;
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
        Map<String, ListingCard> cards;
        try {
            cards = collectCards(ctx);
        } catch (Exception e) {
            throw new SourceFetchException("relocateme: failed to collect listing cards", e);
        }

        List<JobPosting> results = new ArrayList<>();
        for (ListingCard card : cards.values()) {
            try {
                boolean known = jobRecordRepository.findBySourceAndExternalId(SOURCE_NAME, card.externalId()).isPresent();
                if (known) {
                    results.add(minimalPosting(card, ctx));
                    continue;
                }
                Optional<String> detailHtml = ctx.http().tryGet(card.url());
                if (detailHtml.isEmpty()) {
                    results.add(minimalPosting(card, ctx));
                    continue;
                }
                Document detailDoc = Jsoup.parse(detailHtml.get(), card.url());
                results.add(parseDetailPage(detailDoc, card, ctx));
            } catch (Exception e) {
                log.warn("relocateme: skipping job {} due to parse error: {}", card.externalId(), e.toString());
            }
        }
        return results;
    }

    private Map<String, ListingCard> collectCards(FetchContext ctx) {
        Map<String, ListingCard> cards = new LinkedHashMap<>();

        for (int page = 1; page <= props.maxListingPages(); page++) {
            String url = props.baseUrl() + "/international-jobs?page=" + page;
            Optional<String> html = ctx.http().tryGet(url);
            if (html.isEmpty()) {
                break;
            }
            Document doc = Jsoup.parse(html.get(), url);
            int before = cards.size();
            for (ListingCard card : parseListingCards(doc)) {
                cards.putIfAbsent(card.externalId(), card);
            }
            // Fixture coverage stops at page 1 (see notes/phase0-orchestrator.md); against real
            // traffic this loop stops itself once a page contributes no unseen job cards.
            if (page > 1 && cards.size() == before) {
                break;
            }
        }

        for (String category : props.categories()) {
            String url = props.baseUrl() + "/international-jobs?category[]=" + category;
            try {
                ctx.http().tryGet(url).ifPresent(html -> {
                    Document doc = Jsoup.parse(html, url);
                    for (ListingCard card : parseListingCards(doc)) {
                        cards.putIfAbsent(card.externalId(), card);
                    }
                });
            } catch (Exception e) {
                log.warn("relocateme: category view fetch failed for {}: {}", category, e.toString());
            }
        }
        return cards;
    }

    /** Package-visible pure parsing so tests can feed fixture HTML directly. */
    List<ListingCard> parseListingCards(Document doc) {
        List<ListingCard> result = new ArrayList<>();
        for (Element jobDiv : doc.select("div.jobs-list__job")) {
            try {
                ListingCard card = parseListingCard(jobDiv);
                if (card != null) {
                    result.add(card);
                }
            } catch (Exception e) {
                log.warn("relocateme: skipping unparsable listing card: {}", e.toString());
            }
        }
        return result;
    }

    private ListingCard parseListingCard(Element jobDiv) {
        Element link = jobDiv.selectFirst("div.job__title a[href]");
        if (link == null) {
            return null;
        }
        String href = link.attr("href");
        Matcher m = JOB_HREF.matcher(href);
        if (!m.matches()) {
            return null;
        }
        String companySlug = m.group("company");
        if (props.excludedSlugs().stream().anyMatch(s -> s.equalsIgnoreCase(companySlug))) {
            return null;
        }
        String externalId = m.group("id");

        String title = text(link.selectFirst("b"));
        if (title == null || title.isBlank()) {
            title = link.text().trim();
        }
        String rest = link.text().replace(title, "").trim();
        String city = rest.startsWith("in ") ? rest.substring(3).trim() : (rest.isBlank() ? null : rest);

        Elements companyDivs = jobDiv.select("div.job__info > div.job__company p");
        String country = companyDivs.size() >= 1 ? companyDivs.get(0).text().trim() : null;
        String company = companyDivs.size() >= 2 ? companyDivs.get(1).text().trim() : null;

        String detailUrl = props.baseUrl() + href;
        return new ListingCard(externalId, detailUrl, title, company, city, country);
    }

    private JobPosting minimalPosting(ListingCard card, FetchContext ctx) {
        return new JobPosting(
            SourceType.RELOCATE_ME,
            SOURCE_NAME,
            card.externalId(),
            card.title(),
            card.company(),
            card.city(),
            card.country(),
            card.url(),
            "",
            List.of(),
            List.of(),
            null,
            false,
            ctx.clock().instant()
        );
    }

    /** Package-visible pure parsing so tests can feed fixture HTML directly. */
    JobPosting parseDetailPage(Document doc, ListingCard card, FetchContext ctx) {
        String title = text(doc.selectFirst("div.job-info__heading h1"));
        if (title == null || title.isBlank()) {
            title = card.title();
        }

        String company = text(doc.selectFirst("div.job-info__company a"));
        if (company == null || company.isBlank()) {
            company = card.company();
        }

        String city = card.city();
        String country = card.country();
        String countryLine = text(doc.selectFirst("div.job-info__country p"));
        if (countryLine != null && countryLine.contains(",")) {
            String[] parts = countryLine.split(",", 2);
            city = parts[0].trim();
            country = parts[1].trim();
        }

        String relocationHeading = text(doc.selectFirst("div.job-info__relocation-packages h2.job-info__title"));
        List<String> benefits = new ArrayList<>();
        for (Element span : doc.select("div.job-info__relocation-packages .relocation-packages__item span")) {
            String benefit = span.text().trim();
            if (!benefit.isBlank()) {
                benefits.add(benefit);
            }
        }

        List<String> techTags = new ArrayList<>();
        for (Element tag : doc.select("div.job-info__tags a.job__tag")) {
            String t = tag.text().trim();
            if (!t.isBlank()) {
                techTags.add(t);
            }
        }

        StringBuilder description = new StringBuilder();
        for (Element item : doc.select("div.job-info__description .job-info__description-item")) {
            String itemText = item.text().trim();
            if (!itemText.isBlank()) {
                if (description.length() > 0) {
                    description.append("\n\n");
                }
                description.append(itemText);
            }
        }

        boolean visaFlag = benefits.stream().anyMatch(b -> b.toLowerCase(Locale.ROOT).contains("visa"))
            || (relocationHeading != null && relocationHeading.toLowerCase(Locale.ROOT).contains("relocation"));

        return new JobPosting(
            SourceType.RELOCATE_ME,
            SOURCE_NAME,
            card.externalId(),
            title,
            company,
            city,
            country,
            card.url(),
            description.toString(),
            techTags,
            benefits,
            null,
            visaFlag,
            ctx.clock().instant()
        );
    }

    private static String text(Element el) {
        return el == null ? null : el.text().trim();
    }

    record ListingCard(String externalId, String url, String title, String company, String city, String country) {
    }
}

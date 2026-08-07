package dev.shoaib.jobradar.sources.ats;

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
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One adapter instance per Personio company (Section 4.3). {@link AtsAdapterRegistrar}
 * registers one bean per {@code companyRegistry.byAts("personio")} entry.
 *
 * <p>The whole job (including every {@code jobDescriptions} block) comes back in the single
 * XML feed response, so -- as with Greenhouse -- there is no separate detail page and the
 * {@code JobRecordRepository} politeness pattern does not apply here.
 */
public class PersonioAdapter implements JobSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(PersonioAdapter.class);

    private final CompanyEntry entry;
    private final String urlTemplate;
    private final boolean enabled;

    public PersonioAdapter(CompanyEntry entry, String urlTemplate, boolean enabled) {
        this.entry = entry;
        this.urlTemplate = urlTemplate;
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "personio:" + entry.token();
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<JobPosting> fetch(FetchContext ctx) throws SourceFetchException {
        String feedUrl = urlTemplate.contains("{sub}") ? urlTemplate.replace("{sub}", entry.token()) : urlTemplate;
        String xml;
        try {
            xml = ctx.http().get(feedUrl);
        } catch (Exception e) {
            throw new SourceFetchException("Personio fetch failed for " + entry.token(), e);
        }
        return parse(xml);
    }

    /** Package-visible pure parsing method so tests can feed fixture XML directly. */
    List<JobPosting> parse(String xml) {
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
        Elements positions = doc.select("position");
        List<JobPosting> result = new ArrayList<>();
        for (Element position : positions) {
            try {
                result.add(toPosting(position));
            } catch (Exception e) {
                log.warn("Skipping unparsable Personio position for {}: {}", entry.token(), e.toString());
            }
        }
        return result;
    }

    private JobPosting toPosting(Element position) {
        String id = text(position, "id");
        if (id == null || id.isBlank()) {
            // No stable identity to dedupe/track against downstream -- let the caller's
            // try/catch skip this <position> rather than emit a garbage posting.
            throw new IllegalStateException("Personio <position> is missing required <id>");
        }
        String title = text(position, "name");
        String office = text(position, "office");

        // The <value> text node already comes back fully entity-decoded by the XML parser
        // (e.g. "&lt;p&gt;" -> literal "<p>"), so it's just an HTML fragment as plain text --
        // re-parse it as HTML to strip the tags.
        StringBuilder description = new StringBuilder();
        for (Element jd : position.select("jobDescriptions > jobDescription")) {
            String value = text(jd, "value");
            if (value != null && !value.isBlank()) {
                if (!description.isEmpty()) {
                    description.append("\n\n");
                }
                description.append(Jsoup.parse(value).text());
            }
        }

        List<String> techTags = new ArrayList<>();
        String keywords = text(position, "keywords");
        if (keywords != null && !keywords.isBlank()) {
            for (String k : keywords.split(",")) {
                String t = k.trim();
                if (!t.isEmpty()) {
                    techTags.add(t);
                }
            }
        }

        String url = "https://" + entry.token() + ".jobs.personio.de/job/" + id + "?language=en";
        Instant postedAt = parseInstant(text(position, "createdAt"));

        return new JobPosting(
            SourceType.PERSONIO,
            name(),
            id,
            title,
            entry.name(),
            office,
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

    private static String text(Element el, String tag) {
        Element child = el.selectFirst("> " + tag);
        return child == null ? null : child.text();
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

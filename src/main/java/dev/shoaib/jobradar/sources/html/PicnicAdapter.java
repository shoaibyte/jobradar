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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Picnic careers (Section 5.4). {@code jobs.picnic.app} is a Next.js App Router site
 * that streams its vacancy list as React Server Component ("flight") data rather than
 * plain server-rendered anchors: the {@code self.__next_f.push([1,"..."])} script
 * chunks carry an escaped JSON string with one record per vacancy
 * ({@code {"data":{"location":{...},"teams":"Engineering"},"_id":"...","name":"...",
 * "url":"/en/vacancies/{CODE}/{category}/{slug}/{city}/{state}/{country}"}}). A handful
 * of "featured" vacancies additionally render as plain {@code <a href="/en/vacancies/...">}
 * anchors. This adapter merges both sources: plain anchors first (cheap, and would pick
 * up a future move back to normal SSR for free), then the flight-payload records as the
 * primary/complete source.
 */
@Component
public class PicnicAdapter implements JobSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(PicnicAdapter.class);
    private static final String SOURCE_NAME = "custom-html:picnic";
    private static final String COMPANY = "Picnic";
    private static final Set<String> ENGINEERING_CATEGORIES = Set.of("engineering", "technology");

    /** /{locale}/(vacancies|poste-vacant)/{CODE}/{category}/{slug}[/{city}/{state}/{country}] */
    private static final Pattern VACANCY_URL = Pattern.compile(
        "^/[a-z]{2}/(?:vacancies|poste-vacant)/(?<code>[A-Za-z0-9]+)/(?<category>[a-z0-9-]+)/(?<slug>[a-z0-9-]+)"
            + "(?:/(?<city>[a-z0-9-]+)/(?<state>[a-z0-9-]+)/(?<country>[a-z0-9-]+))?/?$");

    /** One vacancy record as embedded (escaped) in the RSC flight payload. */
    private static final Pattern EMBEDDED_RECORD = Pattern.compile(
        "\\{\"injectables\":null,\"data\":\\{\"location\":\\{\"city\":\"(?<city>[^\"]*)\",\"state\":\"(?<state>[^\"]*)\","
            + "\"country\":\"(?<country>[^\"]*)\"\\},\"search_string\":\"[^\"]*\",\"custom_search_string\":\"[^\"]*\","
            + "\"teams\":\"(?<teams>[^\"]*)\"\\},\"_id\":\"[^\"]*\",\"name\":\"(?<name>[^\"]*)\",\"visibility\":\"[^\"]*\","
            + "\"template\":\"[^\"]*\",\"locales\":\\[[^]]*],\"url\":\"(?<url>[^\"]*)\"}");

    private final String baseUrl;
    private final boolean enabled;
    private final JobRecordRepository jobRecordRepository;

    public PicnicAdapter(
        @Value("${app.sources.picnic.base-url}") String baseUrl,
        @Value("${app.sources.picnic.enabled}") boolean enabled,
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
            throw new SourceFetchException("picnic: failed to fetch " + baseUrl, e);
        }
        if (html.isEmpty()) {
            throw new SourceFetchException("picnic: listing page returned no content");
        }

        Map<String, VacancyRecord> records = new LinkedHashMap<>();
        try {
            for (VacancyRecord r : parseEmbeddedJson(html.get())) {
                records.putIfAbsent(r.code(), r);
            }
        } catch (Exception e) {
            log.warn("picnic: embedded-JSON extraction failed: {}", e.toString());
        }
        try {
            for (VacancyRecord r : parseDomAnchors(html.get(), baseUrl)) {
                records.putIfAbsent(r.code(), r);
            }
        } catch (Exception e) {
            log.warn("picnic: DOM anchor extraction failed: {}", e.toString());
        }

        List<JobPosting> results = new ArrayList<>();
        for (VacancyRecord r : records.values()) {
            if (!ENGINEERING_CATEGORIES.contains(r.category().toLowerCase(Locale.ROOT))) {
                continue;
            }
            try {
                boolean known = jobRecordRepository.findBySourceAndExternalId(SOURCE_NAME, r.code()).isPresent();
                if (known) {
                    results.add(minimalPosting(r, ctx));
                    continue;
                }
                Optional<String> detailHtml = ctx.http().tryGet(r.absoluteUrl(baseUrl));
                if (detailHtml.isEmpty()) {
                    results.add(minimalPosting(r, ctx));
                    continue;
                }
                results.add(parseDetailPage(detailHtml.get(), r, ctx));
            } catch (Exception e) {
                log.warn("picnic: skipping vacancy {} due to parse error: {}", r.code(), e.toString());
            }
        }
        return results;
    }

    /** Package-visible pure parsing so tests can feed fixture HTML directly. */
    List<VacancyRecord> parseEmbeddedJson(String html) {
        String unescaped = html.replace("\\\"", "\"");
        List<VacancyRecord> result = new ArrayList<>();
        Matcher m = EMBEDDED_RECORD.matcher(unescaped);
        while (m.find()) {
            try {
                String url = unescapeJs(m.group("url"));
                Matcher urlMatcher = VACANCY_URL.matcher(url);
                if (!urlMatcher.matches()) {
                    continue;
                }
                String code = urlMatcher.group("code").toUpperCase(Locale.ROOT);
                String category = urlMatcher.group("category");
                String name = unescapeJs(m.group("name")).trim();
                String city = unescapeJs(m.group("city"));
                String state = unescapeJs(m.group("state"));
                String country = unescapeJs(m.group("country"));
                result.add(new VacancyRecord(codeWithPrefix(code), category, name, city, state, country, url));
            } catch (Exception e) {
                log.warn("picnic: skipping unparsable embedded vacancy record: {}", e.toString());
            }
        }
        return result;
    }

    /** Package-visible pure parsing so tests can feed fixture HTML directly. */
    List<VacancyRecord> parseDomAnchors(String html, String pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl);
        List<VacancyRecord> result = new ArrayList<>();
        for (Element a : doc.select("a[href*=/vacancies/], a[href*=/poste-vacant/]")) {
            try {
                String href = a.attr("href");
                Matcher m = VACANCY_URL.matcher(href);
                if (!m.matches()) {
                    continue;
                }
                String name = a.text().trim();
                if (name.isBlank()) {
                    continue;
                }
                String city = titleCase(m.group("city"));
                String state = titleCase(m.group("state"));
                String country = titleCase(m.group("country"));
                result.add(new VacancyRecord(codeWithPrefix(m.group("code").toUpperCase(Locale.ROOT)),
                    m.group("category"), name, city, state, country, href));
            } catch (Exception e) {
                log.warn("picnic: skipping unparsable DOM vacancy anchor: {}", e.toString());
            }
        }
        return result;
    }

    private static String codeWithPrefix(String code) {
        return code.startsWith("J") ? code : "J" + code;
    }

    private static String titleCase(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        String[] parts = slug.split("-");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private JobPosting minimalPosting(VacancyRecord r, FetchContext ctx) {
        return new JobPosting(
            SourceType.CUSTOM_HTML,
            SOURCE_NAME,
            r.code(),
            r.name(),
            COMPANY,
            r.city(),
            r.country(),
            r.absoluteUrl(baseUrl),
            "",
            List.of(),
            List.of(),
            null,
            false,
            ctx.clock().instant()
        );
    }

    /**
     * Best-effort description extraction. The bulk of the page body is also RSC flight
     * data (a deeply nested serialized React element tree, not plain HTML/JSON), which a
     * regex/jsoup pass cannot faithfully reconstruct -- see notes/sources-html.md. We
     * pull what is reliably present: the SEO {@code <meta name="description">}, plus any
     * directly-embedded HTML fragments (Recruitee-style rich-text blocks, which -- unlike
     * the surrounding page chrome -- are shipped as literal escaped HTML strings).
     */
    JobPosting parseDetailPage(String html, VacancyRecord r, FetchContext ctx) {
        Document doc = Jsoup.parse(html, r.absoluteUrl(baseUrl));
        String metaDescription = Optional.ofNullable(doc.selectFirst("meta[name=description]"))
            .map(el -> el.attr("content")).orElse("");

        StringBuilder description = new StringBuilder(metaDescription);
        for (String fragment : extractEscapedHtmlFragments(html)) {
            if (!fragment.isBlank()) {
                if (description.length() > 0) {
                    description.append("\n\n");
                }
                description.append(fragment);
            }
        }

        List<String> benefits = new ArrayList<>();
        String fullText = description.toString();
        for (String sentence : fullText.split("(?<=[.!?])\\s+")) {
            if (sentence.toLowerCase(Locale.ROOT).matches(".*(visa|relocat|sponsor|30\\s*%.{0,20}ruling|flight ticket|accommodation).*")) {
                benefits.add(sentence.trim());
            }
        }

        boolean visaFlag = fullText.toLowerCase(Locale.ROOT)
            .matches("(?s).*(visa|sponsor|relocat|30\\s*%.{0,20}ruling).*");

        return new JobPosting(
            SourceType.CUSTOM_HTML,
            SOURCE_NAME,
            r.code(),
            r.name(),
            COMPANY,
            r.city(),
            r.country(),
            r.absoluteUrl(baseUrl),
            fullText,
            List.of(),
            benefits,
            null,
            visaFlag,
            ctx.clock().instant()
        );
    }

    private static List<String> extractEscapedHtmlFragments(String html) {
        List<String> fragments = new ArrayList<>();
        Pattern chunkPattern = Pattern.compile(
            "self\\.__next_f\\.push\\(\\[1,\"(\\\\u003c[a-zA-Z][\\s\\S]{0,20000}?)\"]\\)");
        Matcher m = chunkPattern.matcher(html);
        while (m.find()) {
            String decoded = unescapeJs(m.group(1));
            String text = Jsoup.parseBodyFragment(decoded).body().text().trim();
            if (!text.isBlank()) {
                fragments.add(text);
            }
        }
        return fragments;
    }

    /** Decodes a JSON-string-literal body: \", \\, \/, \n, \t and \\uXXXX escapes. */
    private static String unescapeJs(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"' -> { sb.append('"'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case '/' -> { sb.append('/'); i++; }
                    case 'n' -> { sb.append('\n'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case 'u' -> {
                        if (i + 5 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                i += 5;
                            } catch (NumberFormatException e) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    record VacancyRecord(String code, String category, String name, String city, String state, String country,
        String url) {

        String absoluteUrl(String baseUrl) {
            if (url.startsWith("http")) {
                return url;
            }
            int schemeEnd = baseUrl.indexOf("//");
            int rootEnd = baseUrl.indexOf('/', schemeEnd + 2);
            String root = rootEnd > 0 ? baseUrl.substring(0, rootEnd) : baseUrl;
            return root + url;
        }
    }
}

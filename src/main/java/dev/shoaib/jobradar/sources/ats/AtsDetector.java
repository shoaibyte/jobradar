package dev.shoaib.jobradar.sources.ats;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure ATS-marker detection logic for the {@code --app.detect-ats=<careersUrl>} command
 * (Section 6). Kept side-effect free so it can be unit tested against canned HTML without any
 * network access; {@link AtsDetectRunner} does the actual fetch + stdout/exit wiring.
 */
public final class AtsDetector {

    private AtsDetector() {
    }

    private static final Pattern GH_TOKEN = Pattern.compile(
        "(?:job-boards|boards)\\.greenhouse\\.io/([a-zA-Z0-9_-]+)");
    private static final Pattern LEVER_TOKEN = Pattern.compile("jobs\\.lever\\.co/([a-zA-Z0-9_-]+)");
    private static final Pattern PERSONIO_TOKEN = Pattern.compile("([a-zA-Z0-9_-]+)\\.jobs\\.personio\\.de");
    private static final Pattern TITLE_TAG = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final String[] GREENHOUSE_MARKERS = {
        "gh_jid", "gh_src", "boards.greenhouse.io", "job-boards.greenhouse.io", "grnh.se"
    };
    private static final String[] LEVER_MARKERS = {"jobs.lever.co", "api.lever.co"};
    private static final String[] PERSONIO_MARKERS = {".jobs.personio.de"};

    private static final String[] RECOGNITION_ONLY_NAMES = {"SmartRecruiters", "Ashby", "Recruitee", "Workable"};
    private static final String[] RECOGNITION_ONLY_MARKERS = {
        "smartrecruiters.com", "ashbyhq.com", "recruitee.com", "workable.com"
    };

    public sealed interface Result permits Supported, RecognizedOnly, Unknown {
    }

    public record Supported(String ats, String companiesYamlSnippet) implements Result {
    }

    public record RecognizedOnly(String atsName) implements Result {
    }

    public record Unknown() implements Result {
    }

    public static Result detect(String html, String sourceUrl) {
        if (html == null) {
            html = "";
        }
        if (containsAny(html, GREENHOUSE_MARKERS)) {
            String token = firstGroup(GH_TOKEN, html);
            String name = guessName(html);
            String snippet = token != null
                ? "  - { name: " + name + ", ats: greenhouse, token: " + token + " }"
                : "  - { name: " + name + ", ats: greenhouse, token: <FILL-IN-BOARD-TOKEN> }";
            return new Supported("greenhouse", snippet);
        }
        if (containsAny(html, LEVER_MARKERS)) {
            String site = firstGroup(LEVER_TOKEN, html);
            String name = guessName(html);
            String snippet = site != null
                ? "  - { name: " + name + ", ats: lever, token: " + site + " }"
                : "  - { name: " + name + ", ats: lever, token: <FILL-IN-SITE-ID> }";
            return new Supported("lever", snippet);
        }
        if (containsAny(html, PERSONIO_MARKERS)) {
            String sub = firstGroup(PERSONIO_TOKEN, html);
            String name = guessName(html);
            String snippet = sub != null
                ? "  - { name: " + name + ", ats: personio, token: " + sub + " }"
                : "  - { name: " + name + ", ats: personio, token: <FILL-IN-SUBDOMAIN> }";
            return new Supported("personio", snippet);
        }
        for (int i = 0; i < RECOGNITION_ONLY_MARKERS.length; i++) {
            if (html.toLowerCase().contains(RECOGNITION_ONLY_MARKERS[i])) {
                return new RecognizedOnly(RECOGNITION_ONLY_NAMES[i]);
            }
        }
        return new Unknown();
    }

    /** Formats a {@link Result} into the exact text the CLI command should print. */
    public static String render(Result result, String sourceUrl) {
        return switch (result) {
            case Supported s -> "# Detected " + s.ats() + " at " + sourceUrl + " -- paste into config/companies.yml:\n"
                + s.companiesYamlSnippet();
            case RecognizedOnly r -> "unsupported ATS: " + r.atsName();
            case Unknown u -> "no known ATS markers detected at " + sourceUrl;
        };
    }

    private static boolean containsAny(String haystack, String[] markers) {
        String lower = haystack.toLowerCase();
        for (String m : markers) {
            if (lower.contains(m.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String firstGroup(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String guessName(String html) {
        Matcher m = TITLE_TAG.matcher(html);
        if (m.find()) {
            String title = m.group(1).trim();
            // Titles are often "Careers at Acme" / "Acme - Careers" / "Acme Careers"; take the
            // longest alnum-ish token as a best-effort company name guess.
            String[] parts = title.split("[|\\-–:]");
            String best = parts[0].trim();
            for (String p : parts) {
                String candidate = p.trim();
                if (!candidate.isEmpty() && !candidate.equalsIgnoreCase("careers")
                    && !candidate.toLowerCase().contains("job")
                    && candidate.length() > best.length()) {
                    best = candidate;
                }
            }
            if (!best.isEmpty()) {
                return best;
            }
        }
        return "CompanyName";
    }
}

package dev.shoaib.jobradar.sources.api;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coarse Java/Go text gate used to pre-filter the flood of Hacker News "Who is hiring?"
 * comments down to plausibly relevant ones (Section 4.4 / 7). This is intentionally a rough
 * copy for our own pre-filtering purposes only -- the {@code match} module implements its own
 * independent copy of the real gate used for scoring, and the two do not need to be byte-identical.
 *
 * <p>Java: {@code \bjava\b} case-insensitive (word boundaries keep "javascript" from matching).
 * <p>Go: {@code \b(golang)\b} anywhere, OR bare {@code \bgo\b} only when it appears within
 * ~40 chars of an engineering-context word (backend, microservice(s), engineer, service, api,
 * developer) -- never bare "go" in ordinary prose like "good to go".
 */
public final class TechGate {

    private TechGate() {
    }

    private static final Pattern JAVA = Pattern.compile("\\bjava\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GOLANG = Pattern.compile("\\bgolang\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GO = Pattern.compile("\\bgo\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GO_CONTEXT_WORD = Pattern.compile(
        "\\b(backend|microservices?|engineer|service|api|developer)\\b", Pattern.CASE_INSENSITIVE);
    private static final int GO_PROXIMITY_CHARS = 40;

    public static boolean matchesJava(String text) {
        return text != null && JAVA.matcher(text).find();
    }

    public static boolean matchesGo(String text) {
        if (text == null) {
            return false;
        }
        if (GOLANG.matcher(text).find()) {
            return true;
        }
        Matcher go = GO.matcher(text);
        while (go.find()) {
            int windowStart = Math.max(0, go.start() - GO_PROXIMITY_CHARS);
            int windowEnd = Math.min(text.length(), go.end() + GO_PROXIMITY_CHARS);
            if (GO_CONTEXT_WORD.matcher(text.substring(windowStart, windowEnd)).find()) {
                return true;
            }
        }
        return false;
    }

    /** True if the text plausibly mentions Java or Go work. */
    public static boolean matches(String text) {
        return matchesJava(text) || matchesGo(text);
    }
}

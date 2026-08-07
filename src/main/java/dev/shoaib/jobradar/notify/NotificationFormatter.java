package dev.shoaib.jobradar.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared message-building logic for all {@link NotificationChannel} implementations. */
final class NotificationFormatter {

    private static final Pattern GO_WORD = Pattern.compile("(?i)\\bgo(lang)?\\b");
    private static final Pattern JAVA_WORD = Pattern.compile("(?i)\\bjava\\b");

    private NotificationFormatter() {
    }

    static String formatBatch(List<MatchNotification> matches) {
        StringBuilder sb = new StringBuilder();
        sb.append("JobRadar: ").append(matches.size()).append(" new match")
            .append(matches.size() == 1 ? "" : "es").append('\n');
        for (MatchNotification m : matches) {
            sb.append('\n').append(formatOne(m)).append('\n');
        }
        return sb.toString().trim();
    }

    static String formatOne(MatchNotification m) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(m.strength()).append(' ')
            .append(String.format(Locale.ROOT, "%.1f", m.score())).append("] ");
        sb.append(m.title()).append(" — ").append(nullToDash(m.company())).append(" — ")
            .append(nullToDash(m.city())).append(", ").append(nullToDash(m.country()));

        List<String> flags = techFlags(m);
        if (!flags.isEmpty()) {
            sb.append(" [").append(String.join("/", flags)).append(']');
        }
        if (m.salaryRaw() != null && !m.salaryRaw().isBlank()) {
            sb.append(" | ").append(m.salaryRaw());
        }
        sb.append('\n').append(m.url());
        return sb.toString();
    }

    private static List<String> techFlags(MatchNotification m) {
        String haystack = (m.techTags() == null ? "" : String.join(" ", m.techTags()))
            + " " + (m.description() == null ? "" : m.description());
        List<String> flags = new ArrayList<>();
        if (JAVA_WORD.matcher(haystack).find()) {
            flags.add("Java");
        }
        if (GO_WORD.matcher(haystack).find()) {
            flags.add("Go");
        }
        return flags;
    }

    private static String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }
}

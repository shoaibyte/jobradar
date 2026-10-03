package dev.shoaib.jobradar.sources.html;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A newsletter location string, split into the parts a UI filters on. Handles the shapes
 * The Global Move uses:
 * <ul>
 *   <li>{@code in Amsterdam, Netherlands 🇳🇱} -> city Amsterdam, country Netherlands, [NL]</li>
 *   <li>{@code [REMOTE – EMEA, LATAM]} -> remote, region "EMEA, LATAM", no location</li>
 *   <li>{@code [REMOTE]} -> remote, region unknown (null)</li>
 *   <li>{@code in Singapore 🇸🇬 or San Francisco, USA 🇺🇸} -> location kept as text,
 *       [SG, US], no single city/country</li>
 *   <li>{@code in Cambridge or Manchester, UK 🇬🇧} -> city "Cambridge or Manchester", country UK</li>
 *   <li>{@code Spain (Remote)}, {@code Munich or remotely within Germany} -> remote option</li>
 * </ul>
 * {@code countryCodes} are ISO 3166 alpha-2, from the flag emoji where present, else from
 * any country names in the text.
 *
 * @param location cleaned display text, emoji stripped; null when the entry only has a remote tag
 * @param city set only when the location is in a single country
 * @param country set only when the location is in a single country
 */
public record JobLocation(String location, String city, String country, List<String> countryCodes,
    boolean remote, String remoteRegion) {

    static final JobLocation NONE = new JobLocation(null, null, null, List.of(), false, null);

    /** "[REMOTE – EMEA, LATAM]" / "[REMOTE]" at the start of a line. */
    static final Pattern REMOTE_TAG = Pattern.compile(
        "^\\s*\\[\\s*REMOTE(?:\\s*[–—-]\\s*(?<region>[^\\]]*?))?\\s*\\]\\s*", Pattern.CASE_INSENSITIVE);

    private static final Pattern REMOTE_WORD = Pattern.compile("(?i)\\bremote(ly)?\\b");
    private static final Pattern MULTI = Pattern.compile("(?i)\\bor\\b|multiple locations|\\s/\\s");
    private static final Map<String, String> CODE_BY_NAME = codeByName();
    private static final Set<String> CODES = Set.of(Locale.getISOCountries());

    static JobLocation parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String text = raw.trim();
        boolean remote = false;
        String region = null;
        Matcher tag = REMOTE_TAG.matcher(text);
        if (tag.find()) {
            remote = true;
            region = tag.group("region") == null || tag.group("region").isBlank()
                ? null : tag.group("region").trim().toUpperCase(Locale.ROOT);
            text = text.substring(tag.end());
        }
        text = text.replaceFirst("(?i)^in\\s+", "");

        List<String> codes = new ArrayList<>(flagCodes(text));
        String location = clean(text);
        if (location == null) {
            return new JobLocation(null, null, null, List.copyOf(codes), remote, region);
        }
        remote = remote || REMOTE_WORD.matcher(location).find();

        String city = null;
        String country = null;
        // One country even with several cities ("Cambridge or Manchester, UK"): only the part
        // after the last comma decides, plus at most one flag.
        if (codes.size() <= 1) {
            String place = location.replaceAll("(?i)\\s*\\(remote\\)", "").trim();
            int comma = place.lastIndexOf(',');
            String tail = comma > 0 ? place.substring(comma + 1).trim() : place;
            if (!tail.isBlank() && !MULTI.matcher(tail).find() && !REMOTE_WORD.matcher(tail).find()) {
                country = tail;
                city = comma > 0 ? place.substring(0, comma).trim() : null;
            }
        }
        if (codes.isEmpty()) {
            // No flags: map any country names in the text ("Poland, Portugal, or Spain").
            for (String token : location.split("\\s*(?:,|/|\\(|\\)|\\bor\\b)\\s*")) {
                String code = CODE_BY_NAME.get(token.trim().toLowerCase(Locale.ROOT).replaceFirst("^the\\s+", ""));
                if (code != null && !codes.contains(code)) {
                    codes.add(code);
                }
            }
        }
        return new JobLocation(location, city, country, List.copyOf(codes), remote, region);
    }

    /** "Netherlands" / "the UK" / "nl" -> ISO alpha-2 code; empty when unknown. */
    public static Optional<String> countryCode(String nameOrCode) {
        if (nameOrCode == null || nameOrCode.isBlank()) {
            return Optional.empty();
        }
        String key = nameOrCode.trim().toLowerCase(Locale.ROOT).replaceFirst("^the\\s+", "");
        if (key.length() == 2 && CODES.contains(key.toUpperCase(Locale.ROOT))) {
            return Optional.of(key.toUpperCase(Locale.ROOT));
        }
        return Optional.ofNullable(CODE_BY_NAME.get(key));
    }

    /** Regional-indicator pairs -> "NL", in order of appearance, deduped. */
    static Set<String> flagCodes(String text) {
        Set<String> codes = new LinkedHashSet<>();
        int[] cps = text.codePoints().toArray();
        for (int i = 0; i + 1 < cps.length; i++) {
            if (isRegionalIndicator(cps[i]) && isRegionalIndicator(cps[i + 1])) {
                codes.add("" + (char) ('A' + cps[i] - 0x1F1E6) + (char) ('A' + cps[i + 1] - 0x1F1E6));
                i++;
            }
        }
        return codes;
    }

    private static boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    /** Strips flag/globe emoji and variation selectors, tidies spacing and stray punctuation. */
    private static String clean(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            boolean emoji = isRegionalIndicator(cp) || (cp >= 0x1F30D && cp <= 0x1F310) || cp == 0xFE0F
                || cp == 0x2705; // ✅ marker some early entries carry
            if (!emoji) {
                out.appendCodePoint(cp);
            }
        });
        String s = out.toString()
            .replaceAll("\\s+", " ")
            .replaceAll("\\s+,", ",")
            .replaceAll("^[\\s,–—-]+|[\\s,]+$", "")
            .trim();
        return s.isBlank() ? null : s;
    }

    private static Map<String, String> codeByName() {
        Map<String, String> map = new HashMap<>();
        for (String code : Locale.getISOCountries()) {
            map.put(Locale.of("", code).getDisplayCountry(Locale.ENGLISH).toLowerCase(Locale.ROOT), code);
        }
        map.put("uk", "GB");
        map.put("england", "GB");
        map.put("scotland", "GB");
        map.put("usa", "US");
        map.put("us", "US");
        map.put("uae", "AE");
        map.put("netherlands", "NL");
        map.put("czech republic", "CZ");
        map.put("south korea", "KR");
        map.put("turkey", "TR");
        map.put("türkiye", "TR");
        map.put("vietnam", "VN");
        map.put("hong kong", "HK");
        map.put("taiwan", "TW");
        map.put("russia", "RU");
        return map;
    }
}

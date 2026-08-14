package dev.shoaib.jobradar.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cross-source dedupe key: {@code sha256(lower(company)|normalizedTitle|countryIso)}.
 * Normalization strips seniority prefixes (Senior/Staff/Lead/...) and gender-marker
 * suffixes like "(m/w/d)" or "(all genders)" so the same role posted through two
 * sources (e.g. a company's own ATS board + Relocate.me) collapses to one fingerprint.
 */
public final class Fingerprint {

    private static final Pattern SENIORITY_PREFIX = Pattern.compile(
        "(?i)^(?:(?:senior|sr\\.?|junior|jr\\.?|staff|principal|lead|mid[- ]?level|entry[- ]?level)\\s+)+");

    private static final Pattern GENDER_SUFFIX = Pattern.compile(
        "(?i)\\s*\\((?:m/w/d|w/m/d|all genders|f/m/x|m/f/d|d/m/w)\\)\\s*");

    private Fingerprint() {
    }

    public static String normalizeTitle(String title) {
        String t = title == null ? "" : title;
        t = GENDER_SUFFIX.matcher(t).replaceAll(" ");
        t = SENIORITY_PREFIX.matcher(t.trim()).replaceAll("");
        return t.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public static String of(String company, String title, String countryIso) {
        String normalizedCompany = company == null ? "" : company.trim().toLowerCase(Locale.ROOT);
        String normalizedTitle = normalizeTitle(title);
        String normalizedCountry = countryIso == null ? "" : countryIso.trim().toUpperCase(Locale.ROOT);
        String raw = normalizedCompany + "|" + normalizedTitle + "|" + normalizedCountry;
        return sha256Hex(raw);
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

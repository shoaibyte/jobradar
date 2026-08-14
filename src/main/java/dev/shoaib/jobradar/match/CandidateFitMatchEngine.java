package dev.shoaib.jobradar.match;

import dev.shoaib.jobradar.core.CompanyEntry;
import dev.shoaib.jobradar.core.CompanyPriority;
import dev.shoaib.jobradar.core.CompanyRegistry;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.MatchEngine;
import dev.shoaib.jobradar.core.MatchOutcome;
import dev.shoaib.jobradar.core.MatchStrength;
import dev.shoaib.jobradar.core.RelocationPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Single {@link MatchEngine} bean (Section 7): applies the role/tech/visa hard gates in order,
 * then scores everything that survives. {@code pipeline-notify} autowires this by the
 * {@code core.MatchEngine} interface, never by this class name.
 *
 * <p>Gate order matters and mirrors the spec: role gate first (cheap title check), then tech
 * gate (also determines the Java/Go signal booleans reused for scoring and strength), then visa
 * gate (may need the {@link CompanyRegistry} lookup, kept last since it's the "heaviest" check).
 */
@Component
public class CandidateFitMatchEngine implements MatchEngine {

    // Word-boundary java detection: "\bjava\b" does NOT match "javascript" because there is no
    // boundary between the 'a' of "java" and the 's' of "script" (both word characters).
    private static final Pattern JAVA_PATTERN = Pattern.compile("\\bjava\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GOLANG_PATTERN = Pattern.compile("\\bgolang\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GO_WORD_PATTERN = Pattern.compile("\\bgo\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPRING_PATTERN = Pattern.compile("\\bspring(\\s*boot)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECONDARY_LANGUAGE_PATTERN =
        Pattern.compile("\\b(kotlin|scala|python)\\b", Pattern.CASE_INSENSITIVE);

    // "N-M years", "N to M years" (also accepts en/em dash and the "yrs" abbreviation).
    private static final Pattern EXPERIENCE_RANGE_PATTERN =
        Pattern.compile("(\\d{1,2})\\s*(?:-|–|—|to)\\s*(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)",
            Pattern.CASE_INSENSITIVE);
    // "N+ years"
    private static final Pattern EXPERIENCE_PLUS_PATTERN =
        Pattern.compile("(\\d{1,2})\\s*\\+\\s*(?:years?|yrs?)", Pattern.CASE_INSENSITIVE);
    // bare "N years" fallback
    private static final Pattern EXPERIENCE_SINGLE_PATTERN =
        Pattern.compile("(\\d{1,2})\\s*(?:years?|yrs?)\\b", Pattern.CASE_INSENSITIVE);

    // A benefit line counts toward "+1 per relocation benefit" if it mentions one of these.
    private static final List<String> RELOCATION_BENEFIT_HINTS =
        List.of("reloc", "visa", "immigration", "moving", "housing");

    private final MatchProperties properties;
    private final CompanyRegistry companyRegistry;
    private final Pattern visaDescriptionPattern;
    private final List<Pattern> goContextWordPatterns;

    public CandidateFitMatchEngine(MatchProperties properties, CompanyRegistry companyRegistry) {
        this.properties = properties;
        this.companyRegistry = companyRegistry;
        String pattern = properties.getVisaGate().getDescriptionPattern();
        this.visaDescriptionPattern = (pattern == null || pattern.isBlank()) ? null : Pattern.compile(pattern);
        this.goContextWordPatterns = properties.getTechGate().getGoContextWords().stream()
            .filter(w -> w != null && !w.isBlank())
            .map(w -> Pattern.compile("\\b" + Pattern.quote(w.trim().toLowerCase(Locale.ROOT)) + "\\b"))
            .toList();
    }

    @Override
    public Optional<MatchOutcome> evaluate(JobPosting posting) {
        if (failsRoleGate(posting)) {
            return Optional.empty();
        }

        boolean javaInTitle = JAVA_PATTERN.matcher(nullToEmpty(posting.title())).find();
        boolean javaInBody = JAVA_PATTERN.matcher(bodyText(posting)).find();
        boolean goPresent = detectGo(posting);
        boolean techGatePassed = javaInTitle || javaInBody || goPresent;
        if (!techGatePassed) {
            return Optional.empty();
        }

        if (!passesVisaGate(posting)) {
            return Optional.empty();
        }

        List<String> reasons = new ArrayList<>();
        MatchProperties.Weights w = properties.getWeights();
        double score = 0;

        if (javaInTitle) {
            score += w.getJavaInTitle();
            reasons.add(formatSigned(w.getJavaInTitle()) + " Java in title");
        }
        if (javaInBody) {
            score += w.getJavaInBody();
            reasons.add(formatSigned(w.getJavaInBody()) + " Java in tags/stack/description");
        }
        if (goPresent) {
            score += w.getGoPresent();
            reasons.add(formatSigned(w.getGoPresent()) + " Go present");
        }

        String combinedText = combinedText(posting);
        if (SPRING_PATTERN.matcher(combinedText).find()) {
            score += w.getSpringBoot();
            reasons.add(formatSigned(w.getSpringBoot()) + " Spring/Spring Boot mentioned");
        }
        if (SECONDARY_LANGUAGE_PATTERN.matcher(combinedText).find()) {
            score += w.getSecondaryLanguage();
            reasons.add(formatSigned(w.getSecondaryLanguage()) + " Kotlin/Scala/Python mentioned");
        }

        int relocationBenefitCount = countRelocationBenefits(posting);
        if (relocationBenefitCount > 0) {
            double benefitScore = relocationBenefitCount * w.getRelocationBenefit();
            score += benefitScore;
            reasons.add(formatSigned(benefitScore) + " relocation benefit(s) (" + relocationBenefitCount
                + " counted, cap " + w.getRelocationBenefitCap() + ")");
        }

        if (experienceOverlapsTarget(posting.description())) {
            score += w.getExperienceOverlap();
            reasons.add(formatSigned(w.getExperienceOverlap()) + " stated experience overlaps target range ("
                + properties.getExperience().getTargetMinYears() + "-" + properties.getExperience().getTargetMaxYears()
                + " yrs)");
        }

        if (posting.country() != null && properties.getPriorityCountries().stream()
            .anyMatch(c -> c.equalsIgnoreCase(posting.country()))) {
            score += w.getPriorityCountry();
            reasons.add(formatSigned(w.getPriorityCountry()) + " priority country (" + posting.country() + ")");
        }

        if (posting.salaryRaw() != null && !posting.salaryRaw().isBlank()) {
            score += w.getSalaryPublished();
            reasons.add(formatSigned(w.getSalaryPublished()) + " salary published");
        }

        Optional<CompanyEntry> company = companyRegistry.byName(posting.company());
        if (company.isPresent() && company.get().priority() == CompanyPriority.HIGH) {
            score += w.getRegistryPriorityHigh();
            reasons.add(formatSigned(w.getRegistryPriorityHigh()) + " company registry priority HIGH");
        }

        String title = nullToEmpty(posting.title());
        List<String> heavyList = properties.getSeniority().getHeavyDownRank();
        List<String> mildList = properties.getSeniority().getMildDownRank();
        if (containsAnyCaseInsensitive(title, heavyList)) {
            score += w.getSeniorityHeavyPenalty();
            reasons.add(formatSigned(w.getSeniorityHeavyPenalty()) + " " + String.join("/", heavyList) + " in title");
        }
        if (containsAnyCaseInsensitive(title, mildList)) {
            score += w.getSeniorityMildPenalty();
            reasons.add(formatSigned(w.getSeniorityMildPenalty()) + " " + String.join("/", mildList) + " in title");
        }

        MatchStrength strength = determineStrength(javaInTitle, javaInBody, score);
        return Optional.of(new MatchOutcome(strength, score, reasons));
    }

    private boolean failsRoleGate(JobPosting posting) {
        return containsAnyCaseInsensitive(nullToEmpty(posting.title()), properties.getRoleGate().getExcludeKeywords());
    }

    private boolean passesVisaGate(JobPosting posting) {
        if (posting.visaFlag()) {
            return true;
        }
        Optional<CompanyEntry> company = companyRegistry.byName(posting.company());
        if (company.isPresent() && company.get().relocation() == RelocationPolicy.COMPANY_WIDE) {
            return true;
        }
        return visaDescriptionPattern != null
            && visaDescriptionPattern.matcher(nullToEmpty(posting.description())).find();
    }

    private boolean detectGo(JobPosting posting) {
        String title = nullToEmpty(posting.title());
        String description = nullToEmpty(posting.description());
        List<String> tags = posting.techTags() == null ? List.of() : posting.techTags();

        if (GOLANG_PATTERN.matcher(title).find()
            || GOLANG_PATTERN.matcher(description).find()
            || tags.stream().anyMatch(t -> GOLANG_PATTERN.matcher(nullToEmpty(t)).find())) {
            return true;
        }

        // Bare "go" in a tags/tech-stack-like field counts on its own (it's a deliberate tag,
        // not ambiguous prose).
        if (tags.stream().anyMatch(t -> GO_WORD_PATTERN.matcher(nullToEmpty(t)).find())) {
            return true;
        }

        // Bare "go" in the description only counts within go-proximity-chars of a backend-context
        // word - this is what keeps "good to go" from ever triggering the tech gate.
        return goNearContextWordInDescription(description);
    }

    private boolean goNearContextWordInDescription(String description) {
        if (description.isEmpty() || goContextWordPatterns.isEmpty()) {
            return false;
        }
        int proximity = properties.getTechGate().getGoProximityChars();
        Matcher goMatcher = GO_WORD_PATTERN.matcher(description);
        while (goMatcher.find()) {
            int start = Math.max(0, goMatcher.start() - proximity);
            int end = Math.min(description.length(), goMatcher.end() + proximity);
            String window = description.substring(start, end);
            for (Pattern contextPattern : goContextWordPatterns) {
                if (contextPattern.matcher(window).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean experienceOverlapsTarget(String description) {
        if (description == null || description.isBlank()) {
            return false;
        }
        int[] range = parseExperienceRange(description);
        if (range == null) {
            return false;
        }
        int targetMin = properties.getExperience().getTargetMinYears();
        int targetMax = properties.getExperience().getTargetMaxYears();
        return range[0] <= targetMax && range[1] >= targetMin;
    }

    /**
     * Deliberately simple, "good enough" parsing of free-text experience ranges (Section 7 says
     * not to over-engineer this): tries "N-M years"/"N to M years" first (most specific), then
     * "N+ years", then falls back to a bare "N years" treated as an exact single-year point.
     * Returns {@code null} when nothing recognizable is found.
     */
    private static int[] parseExperienceRange(String description) {
        Matcher rangeMatcher = EXPERIENCE_RANGE_PATTERN.matcher(description);
        if (rangeMatcher.find()) {
            int a = Integer.parseInt(rangeMatcher.group(1));
            int b = Integer.parseInt(rangeMatcher.group(2));
            return new int[] {Math.min(a, b), Math.max(a, b)};
        }
        Matcher plusMatcher = EXPERIENCE_PLUS_PATTERN.matcher(description);
        if (plusMatcher.find()) {
            int n = Integer.parseInt(plusMatcher.group(1));
            return new int[] {n, 99};
        }
        Matcher singleMatcher = EXPERIENCE_SINGLE_PATTERN.matcher(description);
        if (singleMatcher.find()) {
            int n = Integer.parseInt(singleMatcher.group(1));
            return new int[] {n, n};
        }
        return null;
    }

    private int countRelocationBenefits(JobPosting posting) {
        List<String> benefits = posting.benefits();
        if (benefits == null || benefits.isEmpty()) {
            return 0;
        }
        int cap = properties.getWeights().getRelocationBenefitCap();
        long count = benefits.stream()
            .filter(b -> b != null)
            .map(b -> b.toLowerCase(Locale.ROOT))
            .filter(b -> RELOCATION_BENEFIT_HINTS.stream().anyMatch(b::contains))
            .count();
        return (int) Math.min(count, cap);
    }

    private MatchStrength determineStrength(boolean javaInTitle, boolean javaInBody, double score) {
        boolean javaSignal = javaInTitle || javaInBody;
        if (javaSignal && score >= properties.getThresholds().getStrongMinScore()) {
            return MatchStrength.STRONG;
        }
        if (score >= properties.getThresholds().getMatchMinScore()) {
            return MatchStrength.MATCH;
        }
        return MatchStrength.PARTIAL;
    }

    private static boolean containsAnyCaseInsensitive(String haystack, List<String> needles) {
        String lower = haystack.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && lower.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String bodyText(JobPosting posting) {
        StringBuilder sb = new StringBuilder();
        if (posting.description() != null) {
            sb.append(posting.description()).append(' ');
        }
        if (posting.techTags() != null) {
            sb.append(String.join(" ", posting.techTags()));
        }
        return sb.toString();
    }

    private static String combinedText(JobPosting posting) {
        return nullToEmpty(posting.title()) + " " + bodyText(posting);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String formatSigned(double value) {
        String magnitude = (value == Math.rint(value)) ? String.valueOf((long) value) : String.valueOf(value);
        return (value >= 0 ? "+" : "") + magnitude;
    }
}

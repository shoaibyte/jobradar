package dev.shoaib.jobradar.match;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shoaib.jobradar.core.CompanyEntry;
import dev.shoaib.jobradar.core.CompanyPriority;
import dev.shoaib.jobradar.core.CompanyRegistry;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.MatchOutcome;
import dev.shoaib.jobradar.core.MatchStrength;
import dev.shoaib.jobradar.core.RelocationPolicy;
import dev.shoaib.jobradar.core.SourceType;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Section 11 table-driven gate/scoring tests for {@link CandidateFitMatchEngine}. Runs as a
 * plain unit test (no Spring context): {@link MatchProperties} is populated by hand to mirror
 * {@code application.yml}'s {@code app.match.*} block exactly, and {@link CompanyRegistry} is a
 * small in-test fake seeded from the real {@code config/companies.yml} values (sources-api owns
 * the real YAML-backed implementation, which this module must not depend on).
 */
class CandidateFitMatchEngineTest {

    private final CandidateFitMatchEngine engine = new CandidateFitMatchEngine(defaultProperties(), testRegistry());

    @Test
    void woltBackendEngineerJavaKotlinScalaGoWelcomed_isStrong() {
        JobPosting posting = posting(
            "Wolt Backend Engineer",
            "Wolt",
            "FI",
            "Join our backend team building services with Spring Boot in Java, Kotlin, and Scala. "
                + "Go experience is welcomed. Roughly 3 to 6 years of experience preferred.",
            List.of("Java", "Kotlin", "Scala", "Go"),
            List.of("Relocation package", "Visa sponsorship", "Housing allowance"),
            "€65,000 - €85,000",
            false);

        Optional<MatchOutcome> outcome = engine.evaluate(posting);

        assertThat(outcome).isPresent();
        assertThat(outcome.get().strength()).isEqualTo(MatchStrength.STRONG);
        assertThat(outcome.get().score()).isEqualTo(14.0);
        assertThat(outcome.get().reasons()).contains("+2 Java in tags/stack/description", "+2 Go present");
    }

    @Test
    void picnicSoftwareEngineerTwoToFiveYearsJava_isStrong() {
        JobPosting posting = posting(
            "Picnic Software Engineer",
            "Picnic",
            "NL",
            "We're looking for a Software Engineer with 2-5 yrs of Java experience, working with "
                + "Spring Boot, to join our logistics platform team.",
            List.of("Java"),
            List.of(),
            "€55,000 - €70,000",
            false);

        Optional<MatchOutcome> outcome = engine.evaluate(posting);

        assertThat(outcome).isPresent();
        assertThat(outcome.get().strength()).isEqualTo(MatchStrength.STRONG);
        assertThat(outcome.get().score()).isEqualTo(8.0);
    }

    @Test
    void vintedBackendRubyOrGoAdvantage_isPartial() {
        JobPosting posting = posting(
            "Vinted Backend Engineer",
            "Vinted",
            "GB",
            "We're building backend services primarily in Ruby, with Go as an advantage for some "
                + "of our services. Relocation support offered for the right candidate.",
            List.of("Ruby", "Go"),
            List.of(),
            null,
            true);

        Optional<MatchOutcome> outcome = engine.evaluate(posting);

        assertThat(outcome).isPresent();
        assertThat(outcome.get().strength()).isEqualTo(MatchStrength.PARTIAL);
        assertThat(outcome.get().score()).isEqualTo(2.0);
    }

    @Test
    void rubyOnlyRole_isGatedOut() {
        JobPosting posting = posting(
            "Ruby Backend Engineer",
            "SomeCo",
            "DE",
            "We use Ruby on Rails extensively for our backend services, plus some Python for data pipelines.",
            List.of("Ruby", "Rails", "Python"),
            List.of(),
            null,
            true);

        Optional<MatchOutcome> outcome = engine.evaluate(posting);

        assertThat(outcome).isEmpty();
    }

    @Test
    void goNiceToHavePythonPrimaryBackend_isPartial() {
        JobPosting posting = posting(
            "Software Engineer",
            "SomeCo",
            "US",
            "The team builds enterprise security products with a Python-primary backend. Go is a "
                + "nice-to-have for our backend team, and prior Go exposure is a plus.",
            List.of("Python"),
            List.of(),
            null,
            true);

        Optional<MatchOutcome> outcome = engine.evaluate(posting);

        assertThat(outcome).isPresent();
        assertThat(outcome.get().strength()).isEqualTo(MatchStrength.PARTIAL);
        assertThat(outcome.get().score()).isEqualTo(3.0);
    }

    @Test
    void verimiSoftwareArchitect_passesGatesButIsPenalized() {
        JobPosting posting = posting(
            "Verimi Software Architect",
            "Verimi",
            "DE",
            "We're looking for a Software Architect to design our Java-based identity platform, "
                + "built with Spring Boot. Visa sponsorship and relocation support are available for "
                + "the right candidate. 8+ years of backend experience required.",
            List.of("Java", "Spring Boot"),
            List.of(),
            null,
            false);

        Optional<MatchOutcome> outcome = engine.evaluate(posting);

        // Verimi has no `relocation:` entry in config/companies.yml (defaults to NONE) and no
        // `priority:` entry (defaults to NORMAL) - the visa gate here must pass on the
        // description-pattern match ("visa sponsorship" / "relocation support"), not the registry.
        assertThat(outcome).isPresent();
        assertThat(outcome.get().reasons()).contains("-3 Principal/Staff/Architect/Director/Head in title");
        assertThat(outcome.get().score()).isEqualTo(1.0);
        assertThat(outcome.get().strength()).isEqualTo(MatchStrength.PARTIAL);
    }

    @Test
    void goodToGoProse_doesNotTriggerTechGate() {
        JobPosting posting = posting(
            "Backend Engineer",
            "SomeCo",
            "US",
            "We ship fast, communicate clearly, and always make sure the release checklist is "
                + "good to go before deploying to production.",
            List.of("Communication", "Teamwork"),
            List.of(),
            null,
            true);

        Optional<MatchOutcome> outcome = engine.evaluate(posting);

        assertThat(outcome).isEmpty();
    }

    // ---- fixtures -----------------------------------------------------------------------

    private static JobPosting posting(String title, String company, String country, String description,
        List<String> techTags, List<String> benefits, String salaryRaw, boolean visaFlag) {
        return new JobPosting(SourceType.GREENHOUSE, "test", "ext-1", title, company, "Somewhere", country,
            "https://example.com/job", description, techTags, benefits, salaryRaw, visaFlag, Instant.now());
    }

    private static MatchProperties defaultProperties() {
        MatchProperties props = new MatchProperties();

        MatchProperties.RoleGate roleGate = new MatchProperties.RoleGate();
        roleGate.setExcludeKeywords(List.of(
            "QA", "Quality Assurance", "Test Engineer", "Data Scientist", "Data Science", "Data Analyst",
            "Product Manager", "Project Manager", "Designer", "UX", "UI Designer", "Recruiter", "Recruiting",
            "Talent Acquisition", "Frontend Engineer", "Front-End Engineer", "Front End Developer",
            "iOS Engineer", "Android Engineer", "Mobile Engineer", "MLOps", "Head of"));
        props.setRoleGate(roleGate);

        MatchProperties.TechGate techGate = new MatchProperties.TechGate();
        techGate.setGoContextWords(
            List.of("backend", "microservice", "microservices", "engineer", "service", "api", "developer"));
        techGate.setGoProximityChars(40);
        props.setTechGate(techGate);

        MatchProperties.VisaGate visaGate = new MatchProperties.VisaGate();
        visaGate.setDescriptionPattern(
            "(?i)visa\\s*(sponsor|sponsorship|support|services)|relocat(e|ion)\\s*(package|support|assistance|bonus)"
                + "|work\\s*permit|blue\\s*card");
        props.setVisaGate(visaGate);

        MatchProperties.Experience experience = new MatchProperties.Experience();
        experience.setTargetMinYears(2);
        experience.setTargetMaxYears(6);
        props.setExperience(experience);

        props.setPriorityCountries(List.of("NL", "DE", "AT", "EE", "FI"));

        MatchProperties.Seniority seniority = new MatchProperties.Seniority();
        seniority.setHeavyDownRank(List.of("Principal", "Staff", "Architect", "Director", "Head"));
        seniority.setMildDownRank(List.of("Lead", "Manager"));
        props.setSeniority(seniority);

        MatchProperties.Weights weights = new MatchProperties.Weights();
        weights.setJavaInTitle(3);
        weights.setJavaInBody(2);
        weights.setGoPresent(2);
        weights.setSpringBoot(1);
        weights.setSecondaryLanguage(1);
        weights.setRelocationBenefit(1);
        weights.setRelocationBenefitCap(3);
        weights.setExperienceOverlap(2);
        weights.setPriorityCountry(1);
        weights.setSalaryPublished(1);
        weights.setRegistryPriorityHigh(1);
        weights.setSeniorityHeavyPenalty(-3);
        weights.setSeniorityMildPenalty(-1);
        props.setWeights(weights);

        MatchProperties.Thresholds thresholds = new MatchProperties.Thresholds();
        thresholds.setStrongMinScore(7);
        thresholds.setMatchMinScore(4);
        props.setThresholds(thresholds);

        return props;
    }

    /**
     * Seeded from the real {@code config/companies.yml} values relevant to these tests. Verimi
     * and Journi have no {@code priority:} key in that file, so per {@code CompanyEntry}'s
     * documented defaults they resolve to NORMAL here - NOT HIGH. Verimi likewise has no
     * {@code relocation:} key (defaults to NONE). Vinted is commented out in the real file (no
     * discoverable Greenhouse board token) and is intentionally absent from this fake too.
     */
    private static CompanyRegistry testRegistry() {
        return new FakeCompanyRegistry()
            .add(new CompanyEntry("Wolt", "greenhouse", "wolt", null, RelocationPolicy.COMPANY_WIDE,
                CompanyPriority.HIGH))
            .add(new CompanyEntry("Picnic", "custom-html", null, "https://jobs.picnic.app/en/vacancies",
                RelocationPolicy.COMPANY_WIDE, CompanyPriority.HIGH))
            .add(new CompanyEntry("Verimi", "personio", "verimi", null, RelocationPolicy.NONE,
                CompanyPriority.NORMAL))
            .add(new CompanyEntry("Journi", "personio", "journi-gmbh", null, RelocationPolicy.NONE,
                CompanyPriority.NORMAL));
    }

    private static final class FakeCompanyRegistry implements CompanyRegistry {
        private final Map<String, CompanyEntry> byName = new HashMap<>();

        FakeCompanyRegistry add(CompanyEntry entry) {
            byName.put(entry.name().toLowerCase(Locale.ROOT), entry);
            return this;
        }

        @Override
        public List<CompanyEntry> all() {
            return List.copyOf(byName.values());
        }

        @Override
        public List<CompanyEntry> byAts(String ats) {
            return byName.values().stream().filter(e -> ats.equals(e.ats())).toList();
        }

        @Override
        public Optional<CompanyEntry> byName(String company) {
            if (company == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(byName.get(company.toLowerCase(Locale.ROOT)));
        }
    }
}

package dev.shoaib.jobradar.match;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code app.match.*} block from {@code application.yml} (Section 7 tunables).
 * Registered as a plain Spring bean via component scanning (no {@code @EnableConfigurationProperties}
 * needed since this class is annotated with both {@code @Component} and
 * {@code @ConfigurationProperties} - Boot's autoconfigured
 * {@code ConfigurationPropertiesBindingPostProcessor} binds it regardless of how the bean
 * was registered).
 *
 * <p>JavaBean-style (mutable, no-arg constructor) rather than record/constructor-binding on
 * purpose: constructor binding for {@code @ConfigurationProperties} beans registered via plain
 * component scan (rather than {@code @EnableConfigurationProperties}) is not guaranteed to be
 * detected the same way, so this sticks to the classic getter/setter form documented by Spring
 * Boot as the alternative to {@code @EnableConfigurationProperties}.
 */
@Component
@ConfigurationProperties(prefix = "app.match")
public class MatchProperties {

    private RoleGate roleGate = new RoleGate();
    private TechGate techGate = new TechGate();
    private VisaGate visaGate = new VisaGate();
    private Experience experience = new Experience();
    private List<String> priorityCountries = List.of();
    private Seniority seniority = new Seniority();
    private Weights weights = new Weights();
    private Thresholds thresholds = new Thresholds();

    public RoleGate getRoleGate() {
        return roleGate;
    }

    public void setRoleGate(RoleGate roleGate) {
        this.roleGate = roleGate;
    }

    public TechGate getTechGate() {
        return techGate;
    }

    public void setTechGate(TechGate techGate) {
        this.techGate = techGate;
    }

    public VisaGate getVisaGate() {
        return visaGate;
    }

    public void setVisaGate(VisaGate visaGate) {
        this.visaGate = visaGate;
    }

    public Experience getExperience() {
        return experience;
    }

    public void setExperience(Experience experience) {
        this.experience = experience;
    }

    public List<String> getPriorityCountries() {
        return priorityCountries;
    }

    public void setPriorityCountries(List<String> priorityCountries) {
        this.priorityCountries = priorityCountries;
    }

    public Seniority getSeniority() {
        return seniority;
    }

    public void setSeniority(Seniority seniority) {
        this.seniority = seniority;
    }

    public Weights getWeights() {
        return weights;
    }

    public void setWeights(Weights weights) {
        this.weights = weights;
    }

    public Thresholds getThresholds() {
        return thresholds;
    }

    public void setThresholds(Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    public static class RoleGate {
        private List<String> excludeKeywords = List.of();

        public List<String> getExcludeKeywords() {
            return excludeKeywords;
        }

        public void setExcludeKeywords(List<String> excludeKeywords) {
            this.excludeKeywords = excludeKeywords;
        }
    }

    public static class TechGate {
        private List<String> goContextWords = List.of();
        private int goProximityChars = 40;

        public List<String> getGoContextWords() {
            return goContextWords;
        }

        public void setGoContextWords(List<String> goContextWords) {
            this.goContextWords = goContextWords;
        }

        public int getGoProximityChars() {
            return goProximityChars;
        }

        public void setGoProximityChars(int goProximityChars) {
            this.goProximityChars = goProximityChars;
        }
    }

    public static class VisaGate {
        private String descriptionPattern = "";

        public String getDescriptionPattern() {
            return descriptionPattern;
        }

        public void setDescriptionPattern(String descriptionPattern) {
            this.descriptionPattern = descriptionPattern;
        }
    }

    public static class Experience {
        private int targetMinYears;
        private int targetMaxYears;

        public int getTargetMinYears() {
            return targetMinYears;
        }

        public void setTargetMinYears(int targetMinYears) {
            this.targetMinYears = targetMinYears;
        }

        public int getTargetMaxYears() {
            return targetMaxYears;
        }

        public void setTargetMaxYears(int targetMaxYears) {
            this.targetMaxYears = targetMaxYears;
        }
    }

    public static class Seniority {
        private List<String> heavyDownRank = List.of();
        private List<String> mildDownRank = List.of();

        public List<String> getHeavyDownRank() {
            return heavyDownRank;
        }

        public void setHeavyDownRank(List<String> heavyDownRank) {
            this.heavyDownRank = heavyDownRank;
        }

        public List<String> getMildDownRank() {
            return mildDownRank;
        }

        public void setMildDownRank(List<String> mildDownRank) {
            this.mildDownRank = mildDownRank;
        }
    }

    public static class Weights {
        private double javaInTitle;
        private double javaInBody;
        private double goPresent;
        private double springBoot;
        private double secondaryLanguage;
        private double relocationBenefit;
        private int relocationBenefitCap;
        private double experienceOverlap;
        private double priorityCountry;
        private double salaryPublished;
        private double registryPriorityHigh;
        private double seniorityHeavyPenalty;
        private double seniorityMildPenalty;

        public double getJavaInTitle() {
            return javaInTitle;
        }

        public void setJavaInTitle(double javaInTitle) {
            this.javaInTitle = javaInTitle;
        }

        public double getJavaInBody() {
            return javaInBody;
        }

        public void setJavaInBody(double javaInBody) {
            this.javaInBody = javaInBody;
        }

        public double getGoPresent() {
            return goPresent;
        }

        public void setGoPresent(double goPresent) {
            this.goPresent = goPresent;
        }

        public double getSpringBoot() {
            return springBoot;
        }

        public void setSpringBoot(double springBoot) {
            this.springBoot = springBoot;
        }

        public double getSecondaryLanguage() {
            return secondaryLanguage;
        }

        public void setSecondaryLanguage(double secondaryLanguage) {
            this.secondaryLanguage = secondaryLanguage;
        }

        public double getRelocationBenefit() {
            return relocationBenefit;
        }

        public void setRelocationBenefit(double relocationBenefit) {
            this.relocationBenefit = relocationBenefit;
        }

        public int getRelocationBenefitCap() {
            return relocationBenefitCap;
        }

        public void setRelocationBenefitCap(int relocationBenefitCap) {
            this.relocationBenefitCap = relocationBenefitCap;
        }

        public double getExperienceOverlap() {
            return experienceOverlap;
        }

        public void setExperienceOverlap(double experienceOverlap) {
            this.experienceOverlap = experienceOverlap;
        }

        public double getPriorityCountry() {
            return priorityCountry;
        }

        public void setPriorityCountry(double priorityCountry) {
            this.priorityCountry = priorityCountry;
        }

        public double getSalaryPublished() {
            return salaryPublished;
        }

        public void setSalaryPublished(double salaryPublished) {
            this.salaryPublished = salaryPublished;
        }

        public double getRegistryPriorityHigh() {
            return registryPriorityHigh;
        }

        public void setRegistryPriorityHigh(double registryPriorityHigh) {
            this.registryPriorityHigh = registryPriorityHigh;
        }

        public double getSeniorityHeavyPenalty() {
            return seniorityHeavyPenalty;
        }

        public void setSeniorityHeavyPenalty(double seniorityHeavyPenalty) {
            this.seniorityHeavyPenalty = seniorityHeavyPenalty;
        }

        public double getSeniorityMildPenalty() {
            return seniorityMildPenalty;
        }

        public void setSeniorityMildPenalty(double seniorityMildPenalty) {
            this.seniorityMildPenalty = seniorityMildPenalty;
        }
    }

    public static class Thresholds {
        private double strongMinScore;
        private double matchMinScore;

        public double getStrongMinScore() {
            return strongMinScore;
        }

        public void setStrongMinScore(double strongMinScore) {
            this.strongMinScore = strongMinScore;
        }

        public double getMatchMinScore() {
            return matchMinScore;
        }

        public void setMatchMinScore(double matchMinScore) {
            this.matchMinScore = matchMinScore;
        }
    }
}

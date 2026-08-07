package dev.shoaib.jobradar.sources.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TechGateTest {

    @Test
    void matchesJavaCaseInsensitively() {
        assertThat(TechGate.matchesJava("We use Java 17 and Spring Boot")).isTrue();
        assertThat(TechGate.matchesJava("we love JAVA here")).isTrue();
    }

    @Test
    void doesNotMatchJavascriptAsJava() {
        assertThat(TechGate.matchesJava("Experience with JavaScript/TypeScript required")).isFalse();
    }

    @Test
    void matchesGolangAnywhere() {
        assertThat(TechGate.matchesGo("We are a golang shop")).isTrue();
    }

    @Test
    void matchesBareGoNearEngineeringContextWord() {
        assertThat(TechGate.matchesGo("Backend Engineer, Go, must have 3 years experience")).isTrue();
        assertThat(TechGate.matchesGo("We use Go for our microservices")).isTrue();
    }

    @Test
    void doesNotMatchBareGoInOrdinaryProse() {
        assertThat(TechGate.matchesGo("Great candidates are always good to go on day one")).isFalse();
        assertThat(TechGate.matchesGo("Let's go build something great, no tech stack mentioned")).isFalse();
    }

    @Test
    void overallMatchesIsTrueIfEitherLanguagePresent() {
        assertThat(TechGate.matches("Senior Java Developer")).isTrue();
        assertThat(TechGate.matches("Backend Engineer with Go experience")).isTrue();
        assertThat(TechGate.matches("Frontend React/TypeScript role, good to go")).isFalse();
    }
}

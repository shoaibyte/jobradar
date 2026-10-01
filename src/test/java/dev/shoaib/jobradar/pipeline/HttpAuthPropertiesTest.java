package dev.shoaib.jobradar.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpAuthPropertiesTest {

    @Test
    void unknownHostAndBlankValueAreAbsent() {
        HttpAuthProperties props = new HttpAuthProperties(Map.of("a.example.com", "  "));
        assertThat(props.cookieFor("a.example.com")).isEmpty();
        assertThat(props.cookieFor("b.example.com")).isEmpty();
        assertThat(new HttpAuthProperties(null).cookieFor("a.example.com")).isEmpty();
    }

    @Test
    void normalizesPasteAccidents() {
        assertThat(new HttpAuthProperties(Map.of("h", "  substack.sid=abc  ")).cookieFor("h"))
            .contains("substack.sid=abc");
        assertThat(new HttpAuthProperties(Map.of("h", "\"substack.sid=abc\"")).cookieFor("h"))
            .contains("substack.sid=abc");
        assertThat(new HttpAuthProperties(Map.of("h", "'substack.sid=abc;'")).cookieFor("h"))
            .contains("substack.sid=abc");
        assertThat(new HttpAuthProperties(Map.of("h", "\"\"")).cookieFor("h")).isEmpty();
    }

    @Test
    void passesThroughMultiCookieValues() {
        assertThat(new HttpAuthProperties(Map.of("h", "substack.sid=abc; substack.lli=1")).cookieFor("h"))
            .contains("substack.sid=abc; substack.lli=1");
    }
}

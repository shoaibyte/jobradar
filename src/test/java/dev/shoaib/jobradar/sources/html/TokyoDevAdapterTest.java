package dev.shoaib.jobradar.sources.html;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokyoDevAdapterTest {

    private final TokyoDevAdapter adapter = new TokyoDevAdapter(
        "https://www.tokyodev.com/jobs", true, mock(JobRecordRepository.class));

    private static String fixture() {
        try (InputStream in = TokyoDevAdapterTest.class.getClassLoader()
            .getResourceAsStream("fixtures/tokyodev/jobs.html")) {
            if (in == null) {
                throw new IllegalStateException("missing fixture");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parsesEveryJobRowAcrossAllCompanyBlocks() {
        List<TokyoDevAdapter.JobCard> cards = adapter.parseListing(fixture(), "https://www.tokyodev.com/jobs");

        // The page itself states "148 positions available" -- every one of them should
        // be recovered, one JobCard per job title link, regardless of company grouping.
        assertThat(cards).hasSize(148);
    }

    @Test
    void separatesMetaTagsFromTechTagsAndCapturesSalary() {
        List<TokyoDevAdapter.JobCard> cards = adapter.parseListing(fixture(), "https://www.tokyodev.com/jobs");

        TokyoDevAdapter.JobCard sre = cards.stream()
            .filter(c -> c.href().equals("/companies/nalalys/jobs/sre-site-reliability-engineer"))
            .findFirst()
            .orElseThrow();
        assertThat(sre.title()).isEqualTo("SRE (Site Reliability Engineer)");
        assertThat(sre.company()).isEqualTo("NaLaLys");
        assertThat(sre.salaryRaw()).isEqualTo("¥7.8M ~ ¥10.8M");
        assertThat(sre.visaFlag()).isFalse();
        assertThat(sre.techTags()).contains("SRE", "Go", "Python", "AWS", "Backend");
        // Meta tags (salary/Japanese-level/remote/residents-only) must never leak into techTags.
        assertThat(sre.techTags()).doesNotContain("¥7.8M ~ ¥10.8M", "Business Japanese", "Japan residents only",
            "Partially remote");
    }

    @Test
    void marksApplyFromAbroadAsVisaFlag() {
        List<TokyoDevAdapter.JobCard> cards = adapter.parseListing(fixture(), "https://www.tokyodev.com/jobs");

        TokyoDevAdapter.JobCard qa = cards.stream()
            .filter(c -> c.href().equals("/companies/toridori/jobs/qa-engineer"))
            .findFirst()
            .orElseThrow();
        assertThat(qa.visaFlag()).isTrue();
        assertThat(qa.techTags()).contains("Quality Assurance", "Test Automation");
    }

    @Test
    void aSiblingLiThatIsNotACompanyBlockDoesNotSinkTheWholePage() {
        String html = """
            <html><body>
            <ul>
              <li id="1"><h3><a href="/companies/good-co">GoodCo</a></h3>
                <div><div class="text-lg font-bold mb-1"><a href="/companies/good-co/jobs/backend-engineer">Backend Engineer</a></div>
                <div class="flex gap-2 flex-wrap font-sm">
                  <a class="tag" href="/jobs/salary-data">&yen;5M ~ &yen;8M</a>
                  <a class="tag" href="/jobs/go">Go</a>
                </div></div>
              </li>
              <li id="2">not a company block at all, no h3/a</li>
            </ul>
            </body></html>
            """;
        List<TokyoDevAdapter.JobCard> cards = adapter.parseListing(html, "https://www.tokyodev.com/jobs");

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).company()).isEqualTo("GoodCo");
        assertThat(cards.get(0).salaryRaw()).isEqualTo("¥5M ~ ¥8M");
    }
}

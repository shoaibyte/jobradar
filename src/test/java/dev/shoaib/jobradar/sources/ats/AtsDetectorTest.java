package dev.shoaib.jobradar.sources.ats;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AtsDetectorTest {

    @Test
    void detectsGreenhouseFromBoardUrlAndExtractsToken() {
        String html = """
            <html><head><title>Careers at Acme</title></head>
            <body><a href="https://job-boards.greenhouse.io/acme/jobs/12345?gh_jid=12345">Apply</a></body>
            </html>
            """;

        AtsDetector.Result result = AtsDetector.detect(html, "https://acme.com/careers");

        assertThat(result).isInstanceOf(AtsDetector.Supported.class);
        AtsDetector.Supported supported = (AtsDetector.Supported) result;
        assertThat(supported.ats()).isEqualTo("greenhouse");
        assertThat(supported.companiesYamlSnippet()).contains("ats: greenhouse", "token: acme");
    }

    @Test
    void detectsGreenhouseFromGrnhShortLinkEvenWithoutAnExtractableToken() {
        String html = "<html><body><a href=\"https://grnh.se/abc123\">Apply now</a></body></html>";

        AtsDetector.Result result = AtsDetector.detect(html, "https://example.com/careers");

        assertThat(result).isInstanceOf(AtsDetector.Supported.class);
        assertThat(((AtsDetector.Supported) result).ats()).isEqualTo("greenhouse");
    }

    @Test
    void detectsLeverAndExtractsSite() {
        String html = """
            <html><head><title>Widgets Inc - Careers</title></head>
            <body><a href="https://jobs.lever.co/widgetsinc/abcde">Backend Engineer</a></body></html>
            """;

        AtsDetector.Result result = AtsDetector.detect(html, "https://widgets.example/careers");

        assertThat(result).isInstanceOf(AtsDetector.Supported.class);
        AtsDetector.Supported supported = (AtsDetector.Supported) result;
        assertThat(supported.ats()).isEqualTo("lever");
        assertThat(supported.companiesYamlSnippet()).contains("ats: lever", "token: widgetsinc");
    }

    @Test
    void detectsPersonioAndExtractsSubdomain() {
        String html = "<html><body>Powered by <a href=\"https://examplecorp.jobs.personio.de/\">Personio</a></body></html>";

        AtsDetector.Result result = AtsDetector.detect(html, "https://examplecorp.com/careers");

        assertThat(result).isInstanceOf(AtsDetector.Supported.class);
        AtsDetector.Supported supported = (AtsDetector.Supported) result;
        assertThat(supported.ats()).isEqualTo("personio");
        assertThat(supported.companiesYamlSnippet()).contains("ats: personio", "token: examplecorp");
    }

    @Test
    void recognizesAshbyAsUnsupported() {
        String html = "<html><body><a href=\"https://jobs.ashbyhq.com/maincode\">Apply</a></body></html>";

        AtsDetector.Result result = AtsDetector.detect(html, "https://maincode.com/careers");

        assertThat(result).isInstanceOf(AtsDetector.RecognizedOnly.class);
        assertThat(((AtsDetector.RecognizedOnly) result).atsName()).isEqualTo("Ashby");
        assertThat(AtsDetector.render(result, "https://maincode.com/careers")).isEqualTo("unsupported ATS: Ashby");
    }

    @Test
    void recognizesSmartRecruitersWorkableAndRecruitee() {
        assertThat(nameOf(AtsDetector.detect("<a href='https://jobs.smartrecruiters.com/x'>x</a>", "u")))
            .isEqualTo("SmartRecruiters");
        assertThat(nameOf(AtsDetector.detect("<a href='https://apply.workable.com/x'>x</a>", "u")))
            .isEqualTo("Workable");
        assertThat(nameOf(AtsDetector.detect("<a href='https://x.recruitee.com/o/y'>x</a>", "u")))
            .isEqualTo("Recruitee");
    }

    @Test
    void unknownWhenNoMarkersPresent() {
        AtsDetector.Result result = AtsDetector.detect("<html><body>Just a plain page</body></html>", "https://x.com");

        assertThat(result).isInstanceOf(AtsDetector.Unknown.class);
        assertThat(AtsDetector.render(result, "https://x.com")).contains("no known ATS markers detected");
    }

    @Test
    void greenhouseIsCheckedBeforeRecognitionOnlyMarkers() {
        // A page that happens to mention both should still be reported as the fully-supported one.
        String html = "<html><body>gh_jid=123 also see workable.com somewhere</body></html>";

        AtsDetector.Result result = AtsDetector.detect(html, "u");

        assertThat(result).isInstanceOf(AtsDetector.Supported.class);
    }

    private static String nameOf(AtsDetector.Result result) {
        return ((AtsDetector.RecognizedOnly) result).atsName();
    }
}

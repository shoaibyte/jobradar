package dev.shoaib.jobradar.pipeline;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link ThrottledHttpFetcher} against a local WireMock server (not a
 * real network call) covering the 404 contract, robots.txt enforcement, and
 * per-host throttling. Constructed directly (no Spring context), so
 * {@code @Retryable} is inert here -- these tests only cover the base fetch
 * logic, not the retry-on-5xx behavior (see notes/pipeline-notify.md).
 */
class ThrottledHttpFetcherTest {

    private WireMockServer server;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private ThrottledHttpFetcher newFetcher(long perHostMinIntervalMs, boolean robotsEnabled) {
        return new ThrottledHttpFetcher("JobRadar-test", new HttpAuthProperties(java.util.Map.of()),
            perHostMinIntervalMs, 5, 5, robotsEnabled, 60);
    }

    @Test
    void getReturnsBodyOnSuccess() {
        server.stubFor(get(urlEqualTo("/robots.txt")).willReturn(aResponse().withStatus(404)));
        server.stubFor(get(urlEqualTo("/jobs")).willReturn(aResponse().withStatus(200).withBody("hello")));

        String body = newFetcher(0, true).get(server.baseUrl() + "/jobs");

        assertThat(body).isEqualTo("hello");
    }

    @Test
    void tryGetReturnsEmptyOn404() {
        server.stubFor(get(urlEqualTo("/robots.txt")).willReturn(aResponse().withStatus(404)));
        server.stubFor(get(urlEqualTo("/missing")).willReturn(aResponse().withStatus(404)));

        Optional<String> result = newFetcher(0, true).tryGet(server.baseUrl() + "/missing");

        assertThat(result).isEmpty();
    }

    @Test
    void getThrowsOn404() {
        server.stubFor(get(urlEqualTo("/robots.txt")).willReturn(aResponse().withStatus(404)));
        server.stubFor(get(urlEqualTo("/missing")).willReturn(aResponse().withStatus(404)));

        ThrottledHttpFetcher fetcher = newFetcher(0, true);
        assertThatThrownBy(() -> fetcher.get(server.baseUrl() + "/missing"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void robotsDisallowBlocksFetch() {
        server.stubFor(get(urlEqualTo("/robots.txt")).willReturn(aResponse().withStatus(200)
            .withBody("User-agent: *\nDisallow: /private\n")));
        server.stubFor(get(urlEqualTo("/private/page")).willReturn(aResponse().withStatus(200).withBody("secret")));

        ThrottledHttpFetcher fetcher = newFetcher(0, true);

        assertThatThrownBy(() -> fetcher.get(server.baseUrl() + "/private/page"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void robotsCheckSkippedWhenDisabled() {
        server.stubFor(get(urlEqualTo("/private/page")).willReturn(aResponse().withStatus(200).withBody("secret")));

        String body = newFetcher(0, false).get(server.baseUrl() + "/private/page");

        assertThat(body).isEqualTo("secret");
    }

    @Test
    void enforcesPerHostMinInterval() {
        server.stubFor(get(urlEqualTo("/robots.txt")).willReturn(aResponse().withStatus(404)));
        server.stubFor(get(urlEqualTo("/a")).willReturn(aResponse().withStatus(200).withBody("a")));
        server.stubFor(get(urlEqualTo("/b")).willReturn(aResponse().withStatus(200).withBody("b")));

        ThrottledHttpFetcher fetcher = newFetcher(300, true);
        long start = System.nanoTime();
        fetcher.get(server.baseUrl() + "/a");
        fetcher.get(server.baseUrl() + "/b");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(280);
    }

    @Test
    void decodesUtf8WhenNoCharsetIsDeclared() {
        byte[] body = "\uFEFFRole\n[Dev](https://x.test) ✅ Málaga".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);

        assertThat(ThrottledHttpFetcher.decode(body, headers)).isEqualTo("Role\n[Dev](https://x.test) ✅ Málaga");
    }

    @Test
    void honoursDeclaredCharset() {
        byte[] body = "Málaga".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType("text/plain; charset=ISO-8859-1"));

        assertThat(ThrottledHttpFetcher.decode(body, headers)).isEqualTo("Málaga");
    }
}

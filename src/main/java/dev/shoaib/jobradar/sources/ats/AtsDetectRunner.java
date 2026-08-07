package dev.shoaib.jobradar.sources.ats;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@code --app.detect-ats=<careersUrl>} (Section 6): fetch the given careers page, probe it for
 * ATS markers, print a ready-to-paste {@code companies.yml} entry (or a "recognized but
 * unsupported" / "unknown" message), then exit -- so the app doesn't go on to also start the
 * scheduler/web server for what is meant to be a one-off CLI utility.
 *
 * <p>Uses a plain JDK {@link HttpClient} rather than the polite, rate-limited {@code core.HttpFetcher}
 * on purpose: this is a single ad-hoc request against an arbitrary URL the operator supplies,
 * not part of the hourly crawl that {@code HttpFetcher}'s per-host politeness rules govern.
 */
@Component
@Order(Integer.MIN_VALUE)
public class AtsDetectRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AtsDetectRunner.class);

    private final String detectAtsUrl;

    public AtsDetectRunner(@Value("${app.detect-ats:}") String detectAtsUrl) {
        this.detectAtsUrl = detectAtsUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (detectAtsUrl == null || detectAtsUrl.isBlank()) {
            return;
        }

        String html;
        try {
            html = fetch(detectAtsUrl);
        } catch (Exception e) {
            log.error("Could not fetch {} for ATS detection: {}", detectAtsUrl, e.toString());
            System.out.println("error fetching " + detectAtsUrl + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        AtsDetector.Result result = AtsDetector.detect(html, detectAtsUrl);
        System.out.println(AtsDetector.render(result, detectAtsUrl));
        System.exit(0);
    }

    private String fetch(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "JobRadar/1.0 (detect-ats utility)")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}

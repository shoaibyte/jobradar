package dev.shoaib.jobradar.pipeline;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import dev.shoaib.jobradar.core.HttpFetcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@code core.HttpFetcher} isn't explicitly assigned to any of the five agents
 * in the ownership table, but pipeline-notify is the piece that most needs a
 * real implementation to exist for the app to run end-to-end, and it's a frozen
 * *interface* -- providing a bean for it from another package is normal Spring
 * DI, not a file-ownership violation. See notes/pipeline-notify.md.
 *
 * <p>Per-host politeness: a per-host monitor lock serializes the throttle-wait
 * and the request itself, so two sources hitting the <em>same</em> host never
 * overlap, while sources on different hosts run fully in parallel (the fan-out
 * in {@link IngestOrchestrator} submits N adapter calls to a virtual-thread
 * executor; this class supplies the per-host mutual exclusion so that
 * parallelism is safe).
 */
@Component
public class ThrottledHttpFetcher implements HttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(ThrottledHttpFetcher.class);
    private static final List<String> ROBOT_NAMES = List.of("jobradar");

    private final RestClient restClient;
    private final SimpleRobotRulesParser robotRulesParser = new SimpleRobotRulesParser();
    private final ConcurrentHashMap<String, Object> hostLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastRequestNanosByHost = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RobotsEntry> robotsCache = new ConcurrentHashMap<>();

    private final String userAgent;
    private final HttpAuthProperties auth;
    private final long perHostMinIntervalMs;
    private final boolean robotsEnabled;
    private final long robotsCacheTtlMinutes;

    public ThrottledHttpFetcher(
        @Value("${app.http.user-agent}") String userAgent,
        HttpAuthProperties auth,
        @Value("${app.http.per-host-min-interval-ms}") long perHostMinIntervalMs,
        @Value("${app.http.connect-timeout-seconds}") int connectTimeoutSeconds,
        @Value("${app.http.read-timeout-seconds}") int readTimeoutSeconds,
        @Value("${app.http.robots-txt.enabled}") boolean robotsEnabled,
        @Value("${app.http.robots-txt.cache-ttl-minutes}") long robotsCacheTtlMinutes) {
        this.userAgent = userAgent;
        this.auth = auth;
        this.perHostMinIntervalMs = perHostMinIntervalMs;
        this.robotsEnabled = robotsEnabled;
        this.robotsCacheTtlMinutes = robotsCacheTtlMinutes;
        this.restClient = buildRestClient(connectTimeoutSeconds, readTimeoutSeconds);
    }

    private RestClient buildRestClient(int connectTimeoutSeconds, int readTimeoutSeconds) {
        HttpClient jdkClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    @Retryable(retryFor = {HttpServerErrorException5xx.class, ResourceAccessException.class},
        maxAttemptsExpression = "${app.http.retry.max-attempts}",
        backoff = @Backoff(delayExpression = "${app.http.retry.initial-backoff-ms}",
            multiplierExpression = "${app.http.retry.backoff-multiplier}"))
    public String get(String url) {
        return fetchInternal(url)
            .orElseThrow(() -> new HttpFetchException("404 Not Found: " + url));
    }

    @Override
    @Retryable(retryFor = {HttpServerErrorException5xx.class, ResourceAccessException.class},
        maxAttemptsExpression = "${app.http.retry.max-attempts}",
        backoff = @Backoff(delayExpression = "${app.http.retry.initial-backoff-ms}",
            multiplierExpression = "${app.http.retry.backoff-multiplier}"))
    public Optional<String> tryGet(String url) {
        return fetchInternal(url);
    }

    private Optional<String> fetchInternal(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        Object lock = hostLocks.computeIfAbsent(host, h -> new Object());
        synchronized (lock) {
            if (robotsEnabled && !isAllowedByRobots(uri)) {
                throw new RobotsDisallowedException("Disallowed by robots.txt: " + url);
            }
            waitForHostSlot(host);
            try {
                RestClient.RequestHeadersSpec<?> request = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.USER_AGENT, userAgent);
                Optional<String> cookie = auth.cookieFor(host);
                if (cookie.isPresent()) {
                    request = request.header(HttpHeaders.COOKIE, cookie.get());
                }
                String body = request.retrieve().body(String.class);
                return Optional.ofNullable(body);
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.empty();
            } catch (org.springframework.web.client.HttpServerErrorException e) {
                throw new HttpServerErrorException5xx(e);
            } finally {
                lastRequestNanosByHost.put(host, System.nanoTime());
            }
        }
    }

    private void waitForHostSlot(String host) {
        Long last = lastRequestNanosByHost.get(host);
        if (last == null) {
            return;
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - last);
        long waitMs = perHostMinIntervalMs - elapsedMs;
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean isAllowedByRobots(URI uri) {
        String hostKey = uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
        long ttlMillis = TimeUnit.MINUTES.toMillis(robotsCacheTtlMinutes);
        RobotsEntry entry = robotsCache.get(hostKey);
        if (entry == null || (System.currentTimeMillis() - entry.fetchedAtMillis()) > ttlMillis) {
            entry = fetchRobots(uri.getScheme(), uri.getHost(), uri.getPort());
            robotsCache.put(hostKey, entry);
        }
        return entry.rules().isAllowed(uri.toString());
    }

    private RobotsEntry fetchRobots(String scheme, String host, int port) {
        String authority = host + (port != -1 ? ":" + port : "");
        String robotsUrl = scheme + "://" + authority + "/robots.txt";
        try {
            ResponseEntity<byte[]> response = restClient.get()
                .uri(robotsUrl)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .retrieve()
                .toEntity(byte[].class);
            byte[] content = response.getBody() != null ? response.getBody() : new byte[0];
            String contentType = response.getHeaders().getContentType() != null
                ? response.getHeaders().getContentType().toString() : "text/plain";
            BaseRobotRules rules = robotRulesParser.parseContent(robotsUrl, content, contentType, ROBOT_NAMES);
            return new RobotsEntry(rules, System.currentTimeMillis());
        } catch (RestClientException e) {
            log.debug("robots.txt unavailable for {} ({}); allowing all", host, e.getMessage());
            BaseRobotRules allowAll = robotRulesParser.parseContent(robotsUrl, new byte[0], "text/plain", ROBOT_NAMES);
            return new RobotsEntry(allowAll, System.currentTimeMillis());
        }
    }

    private record RobotsEntry(BaseRobotRules rules, long fetchedAtMillis) {
    }

    /** Unchecked wrapper so 5xx responses are retryable via {@code retryFor}. */
    static class HttpServerErrorException5xx extends RuntimeException {
        HttpServerErrorException5xx(org.springframework.web.client.HttpServerErrorException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** Thrown for a 404 from {@link #get(String)} (see {@code tryGet}'s "empty on 404" contract). */
    static class HttpFetchException extends RuntimeException {
        HttpFetchException(String message) {
            super(message);
        }
    }

    /** Never retried -- a robots.txt disallow is not a transient failure. */
    static class RobotsDisallowedException extends RuntimeException {
        RobotsDisallowedException(String message) {
            super(message);
        }
    }
}

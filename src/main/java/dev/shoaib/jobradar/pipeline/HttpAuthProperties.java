package dev.shoaib.jobradar.pipeline;

import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.http.auth.*}. Maps a hostname to the {@code Cookie} header value
 * {@link ThrottledHttpFetcher} sends on every request to that host. This exists for
 * sources where the owner of this deployment has an account entitling them to the
 * content (e.g. a paid Substack subscription: set {@code SUBSTACK_COOKIE} to the
 * browser session cookie, typically {@code substack.sid=...}). Blank values (the
 * default when the env var is unset) are treated as absent.
 *
 * <p>Registered via {@code @ConfigurationPropertiesScan} on the application root, same
 * as the other properties records (see {@code RelocateMeProperties} for why not
 * {@code @Component}).
 */
@ConfigurationProperties(prefix = "app.http.auth")
public record HttpAuthProperties(Map<String, String> cookies) {

    public HttpAuthProperties {
        cookies = cookies == null ? Map.of() : Map.copyOf(cookies);
    }

    public Optional<String> cookieFor(String host) {
        String value = cookies.get(host);
        if (value == null) {
            return Optional.empty();
        }
        // Tolerate the usual env-var paste accidents: surrounding whitespace, shell
        // quoting that survived into the value, and a trailing separator.
        value = value.trim();
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        while (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}

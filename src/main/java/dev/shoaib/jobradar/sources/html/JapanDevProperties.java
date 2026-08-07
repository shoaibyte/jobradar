package dev.shoaib.jobradar.sources.html;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.sources.japandev.*} (see application.yml, frozen). Registered via
 * {@code @ConfigurationPropertiesScan} on the application root (added in Phase 2) rather
 * than {@code @Component} -- constructor/record-style binding requires the properties
 * {@code Binder} machinery, which plain component-scanning does not invoke; a bare
 * {@code @Component} on a record causes Spring to try (and fail) to autowire its
 * constructor parameters as regular beans.
 */
@ConfigurationProperties(prefix = "app.sources.japandev")
public record JapanDevProperties(boolean enabled, String baseUrl, List<String> paths) {

    public JapanDevProperties {
        paths = paths == null ? List.of() : List.copyOf(paths);
    }
}

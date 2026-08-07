package dev.shoaib.jobradar.sources.html;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.sources.relocateme.*} (see application.yml, frozen). Registered via
 * {@code @ConfigurationPropertiesScan} on the application root (added in Phase 2) rather
 * than {@code @Component} -- constructor/record-style binding requires the properties
 * {@code Binder} machinery, which plain component-scanning does not invoke; a bare
 * {@code @Component} on a record causes Spring to try (and fail) to autowire its
 * constructor parameters as regular beans.
 */
@ConfigurationProperties(prefix = "app.sources.relocateme")
public record RelocateMeProperties(
    boolean enabled,
    String baseUrl,
    int maxListingPages,
    List<String> categories,
    List<String> excludedSlugs) {

    public RelocateMeProperties {
        categories = categories == null ? List.of() : List.copyOf(categories);
        excludedSlugs = excludedSlugs == null ? List.of() : List.copyOf(excludedSlugs);
    }
}

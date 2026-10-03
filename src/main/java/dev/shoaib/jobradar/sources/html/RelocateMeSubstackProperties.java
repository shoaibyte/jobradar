package dev.shoaib.jobradar.sources.html;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.sources.relocateme-substack.*} (see application.yml). Registered via
 * {@code @ConfigurationPropertiesScan} on the application root, same as
 * {@link RelocateMeProperties}.
 *
 * @param postSlugPrefixes archive posts whose slug starts with any of these are job-list
 *     issues worth fetching (e.g. {@code weekly-hand-curated-tech-jobs})
 * @param postTags a post is also selected when any of its Substack tags matches one of
 *     these (case-insensitive), e.g. {@code Relocation-friendly jobs}
 * @param sections newsletter body headings whose job entries are collected
 *     (case/punctuation-insensitive prefix match, so "Back End" also matches
 *     "Back End (33 roles)")
 * @param fallbackTitlePattern when none of the configured section headings appear in a
 *     post body (layout drift), every job-shaped entry in the whole post is swept and
 *     kept only if its title or keywords match this regex
 */
@ConfigurationProperties(prefix = "app.sources.relocateme-substack")
public record RelocateMeSubstackProperties(
    boolean enabled,
    String baseUrl,
    int archivePageSize,
    int maxPosts,
    int lookbackDays,
    List<String> postSlugPrefixes,
    List<String> postTags,
    List<String> sections,
    String fallbackTitlePattern) {

    public RelocateMeSubstackProperties {
        postSlugPrefixes = postSlugPrefixes == null ? List.of() : List.copyOf(postSlugPrefixes);
        postTags = postTags == null ? List.of() : List.copyOf(postTags);
        sections = sections == null ? List.of() : List.copyOf(sections);
    }
}

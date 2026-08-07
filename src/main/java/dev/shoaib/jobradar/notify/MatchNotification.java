package dev.shoaib.jobradar.notify;

import java.util.List;

/**
 * A flattened, notify-package-local DTO combining the bits of a match + its
 * job record that a notification message needs. Kept separate from the
 * persistence entities so this package never has to depend on {@code core.persistence}.
 */
public record MatchNotification(String strength, double score, String title, String company,
    String city, String country, List<String> techTags, String description, String salaryRaw,
    String url) {}

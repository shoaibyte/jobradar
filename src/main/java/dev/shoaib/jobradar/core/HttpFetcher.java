package dev.shoaib.jobradar.core;

import java.util.Optional;

/**
 * Implemented once in core: rate-limited (per host), retried, robots.txt-aware.
 */
public interface HttpFetcher {

    String get(String url);

    /** Empty on 404. */
    Optional<String> tryGet(String url);
}

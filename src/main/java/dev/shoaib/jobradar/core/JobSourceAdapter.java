package dev.shoaib.jobradar.core;

import java.util.List;

public interface JobSourceAdapter {

    /** e.g. "relocateme", "greenhouse:paypay" */
    String name();

    boolean enabled();

    /** Full current snapshot. */
    List<JobPosting> fetch(FetchContext ctx) throws SourceFetchException;

    /** HN returns false. */
    default boolean supportsRemovalDetection() {
        return true;
    }
}

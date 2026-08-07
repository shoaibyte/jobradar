package dev.shoaib.jobradar.core;

import java.util.Optional;

/**
 * Implemented by the match module (a single Spring-managed bean, discovered by
 * pipeline via autowiring — pipeline does not depend on the implementation class).
 * Applies the Section 7 hard gates (role/tech/visa) first; an empty Optional means
 * the posting was gated out. A present result carries the strength/score/reasons
 * for postings that passed all gates.
 */
public interface MatchEngine {

    Optional<MatchOutcome> evaluate(JobPosting posting);
}

package dev.shoaib.jobradar.core;

import java.util.List;
import java.util.Optional;

/**
 * Loaded from {@code config/companies.yml} at startup (implementation lives in
 * sources/ats). ATS clients iterate {@link #byAts(String)} to know which
 * tokens to fetch; the match engine uses {@link #byName(String)} to apply
 * {@code COMPANY_WIDE} relocation and {@code HIGH} priority scoring.
 */
public interface CompanyRegistry {

    List<CompanyEntry> all();

    List<CompanyEntry> byAts(String ats);

    /** Case-insensitive match against {@link CompanyEntry#name()}. */
    Optional<CompanyEntry> byName(String company);
}

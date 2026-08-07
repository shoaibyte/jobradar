package dev.shoaib.jobradar.core;

/**
 * One row of {@code config/companies.yml}. {@code ats} is one of
 * "greenhouse", "personio", "lever", "custom-html". {@code token} is the
 * ATS board token/subdomain (greenhouse/personio/lever); {@code url} is used
 * for custom-html entries. {@code relocation}/{@code priority} default to
 * NONE/NORMAL when absent from the YAML.
 */
public record CompanyEntry(String name, String ats, String token, String url,
    RelocationPolicy relocation, CompanyPriority priority) {}

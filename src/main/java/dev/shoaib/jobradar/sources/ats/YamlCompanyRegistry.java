package dev.shoaib.jobradar.sources.ats;

import dev.shoaib.jobradar.core.CompanyEntry;
import dev.shoaib.jobradar.core.CompanyRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * The single {@code core.CompanyRegistry} bean (Section 6). Loads {@code config/companies.yml}
 * (path given by {@code app.companies-file}, a Spring {@link Resource}) once at construction
 * time via SnakeYAML.
 */
@Component
public class YamlCompanyRegistry implements CompanyRegistry {

    private final List<CompanyEntry> entries;

    public YamlCompanyRegistry(@Value("${app.companies-file}") Resource companiesFile) throws IOException {
        try (InputStream in = companiesFile.getInputStream()) {
            this.entries = CompanyYamlLoader.load(in);
        }
    }

    @Override
    public List<CompanyEntry> all() {
        return entries;
    }

    @Override
    public List<CompanyEntry> byAts(String ats) {
        if (ats == null) {
            return List.of();
        }
        return entries.stream()
            .filter(e -> ats.equalsIgnoreCase(e.ats()))
            .toList();
    }

    @Override
    public Optional<CompanyEntry> byName(String company) {
        if (company == null) {
            return Optional.empty();
        }
        return entries.stream()
            .filter(e -> company.equalsIgnoreCase(e.name()))
            .findFirst();
    }
}

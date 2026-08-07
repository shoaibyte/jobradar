package dev.shoaib.jobradar.sources.ats;

import dev.shoaib.jobradar.core.CompanyEntry;
import dev.shoaib.jobradar.core.CompanyPriority;
import dev.shoaib.jobradar.core.RelocationPolicy;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Shared parser for {@code config/companies.yml}, used both by {@link YamlCompanyRegistry}
 * (the {@code core.CompanyRegistry} bean) and by {@link AtsAdapterRegistrar} (which needs the
 * same list at bean-definition time, before the registry bean itself exists, in order to
 * register one adapter bean per company).
 *
 * <p>Malformed rows (missing {@code name}/{@code ats}) are skipped rather than failing the
 * whole load -- one bad entry in the watchlist should not prevent every other company from
 * being tracked.
 */
public final class CompanyYamlLoader {

    private static final Logger log = LoggerFactory.getLogger(CompanyYamlLoader.class);

    private CompanyYamlLoader() {
    }

    @SuppressWarnings("unchecked")
    public static List<CompanyEntry> load(InputStream in) {
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(in);
        if (!(loaded instanceof Map<?, ?> root)) {
            return List.of();
        }
        Object companiesObj = root.get("companies");
        if (!(companiesObj instanceof List<?> rawList)) {
            return List.of();
        }

        List<CompanyEntry> result = new ArrayList<>();
        for (Object o : rawList) {
            if (!(o instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) rawMap;
            try {
                String name = asString(map.get("name"));
                String ats = asString(map.get("ats"));
                if (name == null || ats == null) {
                    log.warn("Skipping companies.yml entry missing name/ats: {}", map);
                    continue;
                }
                String token = asString(map.get("token"));
                String url = asString(map.get("url"));
                RelocationPolicy relocation = map.get("relocation") != null
                    ? RelocationPolicy.valueOf(asString(map.get("relocation")).toUpperCase())
                    : RelocationPolicy.NONE;
                CompanyPriority priority = map.get("priority") != null
                    ? CompanyPriority.valueOf(asString(map.get("priority")).toUpperCase())
                    : CompanyPriority.NORMAL;
                result.add(new CompanyEntry(name, ats, token, url, relocation, priority));
            } catch (Exception e) {
                log.warn("Skipping malformed companies.yml entry {}: {}", map, e.toString());
            }
        }
        return List.copyOf(result);
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}

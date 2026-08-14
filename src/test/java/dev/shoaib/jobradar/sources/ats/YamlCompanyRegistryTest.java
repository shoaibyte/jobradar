package dev.shoaib.jobradar.sources.ats;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shoaib.jobradar.core.CompanyEntry;
import dev.shoaib.jobradar.core.CompanyPriority;
import dev.shoaib.jobradar.core.RelocationPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

/**
 * Exercises the real, frozen {@code config/companies.yml} -- this is the exact file the
 * production {@code app.companies-file} property points at, so this doubles as a sanity check
 * that the watchlist itself still parses cleanly.
 */
class YamlCompanyRegistryTest {

    private final YamlCompanyRegistry registry = new YamlCompanyRegistry(
        new FileSystemResource("config/companies.yml"));

    YamlCompanyRegistryTest() throws Exception {
    }

    @Test
    void loadsAllRowsFromTheRealWatchlist() {
        assertThat(registry.all()).isNotEmpty();
    }

    @Test
    void byNameIsCaseInsensitiveAndReturnsExpectedFields() {
        Optional<CompanyEntry> adyen = registry.byName("adyen");

        assertThat(adyen).isPresent();
        assertThat(adyen.get().name()).isEqualTo("Adyen");
        assertThat(adyen.get().ats()).isEqualTo("greenhouse");
        assertThat(adyen.get().token()).isEqualTo("adyen");
        assertThat(adyen.get().relocation()).isEqualTo(RelocationPolicy.COMPANY_WIDE);
        assertThat(adyen.get().priority()).isEqualTo(CompanyPriority.HIGH);

        assertThat(registry.byName("ADYEN")).isPresent();
        assertThat(registry.byName("does-not-exist")).isEmpty();
    }

    @Test
    void defaultsApplyWhenRelocationAndPriorityAreAbsent() {
        CompanyEntry catawiki = registry.byName("Catawiki").orElseThrow();

        assertThat(catawiki.relocation()).isEqualTo(RelocationPolicy.NONE);
        assertThat(catawiki.priority()).isEqualTo(CompanyPriority.NORMAL);
    }

    @Test
    void byAtsIsCaseInsensitiveAndGroupsCorrectly() {
        List<CompanyEntry> greenhouse = registry.byAts("GREENHOUSE");
        assertThat(greenhouse).extracting(CompanyEntry::token)
            .containsExactlyInAnyOrder("adyen", "imc", "catawiki", "n26", "hellofresh", "sumup",
                "getyourguide", "celonis", "flix", "raisin", "solarisbank", "bitpanda", "wolt", "veriff");

        List<CompanyEntry> personio = registry.byAts("personio");
        assertThat(personio).extracting(CompanyEntry::token)
            .containsExactlyInAnyOrder("verimi", "journi-gmbh");

        // No live company is wired to Lever yet.
        assertThat(registry.byAts("lever")).isEmpty();
    }

    @Test
    void commentedOutVintedRowIsNotLoaded() {
        assertThat(registry.byName("Vinted")).isEmpty();
    }
}

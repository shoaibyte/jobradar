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
        Optional<CompanyEntry> payPay = registry.byName("paypay");

        assertThat(payPay).isPresent();
        assertThat(payPay.get().name()).isEqualTo("PayPay");
        assertThat(payPay.get().ats()).isEqualTo("greenhouse");
        assertThat(payPay.get().token()).isEqualTo("paypay");
        assertThat(payPay.get().relocation()).isEqualTo(RelocationPolicy.COMPANY_WIDE);
        assertThat(payPay.get().priority()).isEqualTo(CompanyPriority.HIGH);

        assertThat(registry.byName("PAYPAY")).isPresent();
        assertThat(registry.byName("does-not-exist")).isEmpty();
    }

    @Test
    void defaultsApplyWhenRelocationAndPriorityAreAbsent() {
        CompanyEntry payPaySecurities = registry.byName("PayPay Securities").orElseThrow();

        assertThat(payPaySecurities.relocation()).isEqualTo(RelocationPolicy.NONE);
        assertThat(payPaySecurities.priority()).isEqualTo(CompanyPriority.NORMAL);
    }

    @Test
    void byAtsIsCaseInsensitiveAndGroupsCorrectly() {
        List<CompanyEntry> greenhouse = registry.byAts("GREENHOUSE");
        assertThat(greenhouse).extracting(CompanyEntry::token)
            .containsExactlyInAnyOrder("paypay", "paypaycard", "paypaysec");

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

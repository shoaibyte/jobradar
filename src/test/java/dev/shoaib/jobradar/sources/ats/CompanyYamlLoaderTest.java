package dev.shoaib.jobradar.sources.ats;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shoaib.jobradar.core.CompanyEntry;
import dev.shoaib.jobradar.core.CompanyPriority;
import dev.shoaib.jobradar.core.RelocationPolicy;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompanyYamlLoaderTest {

    @Test
    void mapsAllFieldsWhenPresent() {
        List<CompanyEntry> entries = load("""
            companies:
              - { name: Picnic, ats: custom-html, url: "https://jobs.picnic.app/en/vacancies", relocation: COMPANY_WIDE, priority: HIGH }
            """);

        assertThat(entries).hasSize(1);
        CompanyEntry e = entries.get(0);
        assertThat(e.name()).isEqualTo("Picnic");
        assertThat(e.ats()).isEqualTo("custom-html");
        assertThat(e.url()).isEqualTo("https://jobs.picnic.app/en/vacancies");
        assertThat(e.token()).isNull();
        assertThat(e.relocation()).isEqualTo(RelocationPolicy.COMPANY_WIDE);
        assertThat(e.priority()).isEqualTo(CompanyPriority.HIGH);
    }

    @Test
    void defaultsRelocationToNoneAndPriorityToNormalWhenAbsent() {
        List<CompanyEntry> entries = load("""
            companies:
              - { name: Verimi, ats: personio, token: verimi }
            """);

        CompanyEntry e = entries.get(0);
        assertThat(e.relocation()).isEqualTo(RelocationPolicy.NONE);
        assertThat(e.priority()).isEqualTo(CompanyPriority.NORMAL);
    }

    @Test
    void skipsEntriesMissingNameOrAtsWithoutFailingTheWholeLoad() {
        List<CompanyEntry> entries = load("""
            companies:
              - { name: Good Co, ats: greenhouse, token: good }
              - { ats: greenhouse, token: missing-name }
              - { name: Missing Ats Co }
            """);

        assertThat(entries).extracting(CompanyEntry::name).containsExactly("Good Co");
    }

    @Test
    void skipsEntriesWithUnparsableEnumValueWithoutFailingTheWholeLoad() {
        List<CompanyEntry> entries = load("""
            companies:
              - { name: Good Co, ats: greenhouse, token: good }
              - { name: Bad Enum Co, ats: lever, token: bad, relocation: NOT_A_REAL_VALUE }
            """);

        assertThat(entries).extracting(CompanyEntry::name).containsExactly("Good Co");
    }

    @Test
    void emptyCompaniesListYieldsEmptyResult() {
        assertThat(load("companies: []")).isEmpty();
    }

    @Test
    void missingCompaniesKeyYieldsEmptyResult() {
        assertThat(load("some-other-key: true")).isEmpty();
    }

    private static List<CompanyEntry> load(String yaml) {
        InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        return CompanyYamlLoader.load(in);
    }
}

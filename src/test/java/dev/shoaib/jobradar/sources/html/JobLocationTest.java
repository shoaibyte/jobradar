package dev.shoaib.jobradar.sources.html;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobLocationTest {

    @Test
    void singleCityWithFlag() {
        JobLocation loc = JobLocation.parse("in Amsterdam, Netherlands 🇳🇱");
        assertThat(loc).isEqualTo(new JobLocation("Amsterdam, Netherlands", "Amsterdam", "Netherlands",
            java.util.List.of("NL"), false, null));
    }

    @Test
    void remoteTagWithRegions() {
        JobLocation loc = JobLocation.parse("[REMOTE – EMEA, LATAM]");
        assertThat(loc.remote()).isTrue();
        assertThat(loc.remoteRegion()).isEqualTo("EMEA, LATAM");
        assertThat(loc.location()).isNull();
        assertThat(loc.country()).isNull();
        assertThat(loc.countryCodes()).isEmpty();
    }

    @Test
    void bareRemoteTagHasNoRegion() {
        JobLocation loc = JobLocation.parse("[REMOTE]");
        assertThat(loc.remote()).isTrue();
        assertThat(loc.remoteRegion()).isNull();
    }

    @Test
    void multiLocationKeepsTextAndAllFlagsButNoSingleCountry() {
        JobLocation loc = JobLocation.parse("in Singapore 🇸🇬 or San Francisco, USA 🇺🇸");
        assertThat(loc.location()).isEqualTo("Singapore or San Francisco, USA");
        assertThat(loc.countryCodes()).containsExactly("SG", "US");
        assertThat(loc.city()).isNull();
        assertThat(loc.country()).isNull();
    }

    @Test
    void multipleLocationsGlobe() {
        JobLocation loc = JobLocation.parse("in Multiple Locations 🌍");
        assertThat(loc.location()).isEqualTo("Multiple Locations");
        assertThat(loc.country()).isNull();
    }

    @Test
    void countryWithRemoteOption() {
        JobLocation loc = JobLocation.parse("Spain (Remote)");
        assertThat(loc.remote()).isTrue();
        assertThat(loc.country()).isEqualTo("Spain");
        assertThat(loc.countryCodes()).containsExactly("ES");
    }

    @Test
    void countryNameWithoutFlagMapsToCode() {
        assertThat(JobLocation.parse("London, UK").countryCodes()).containsExactly("GB");
        assertThat(JobLocation.parse("Dubai, UAE").countryCodes()).containsExactly("AE");
        assertThat(JobLocation.parse("Copenhagen, Denmark").countryCodes()).containsExactly("DK");
    }

    @Test
    void countryNameListWithoutFlags() {
        JobLocation loc = JobLocation.parse("Poland, Portugal, Spain, the UK, or the UAE");
        assertThat(loc.countryCodes()).containsExactly("PL", "PT", "ES", "GB", "AE");
        assertThat(loc.country()).isNull();
    }

    @Test
    void stripsCheckMarker() {
        JobLocation loc = JobLocation.parse("Prague, Czechia ✅");
        assertThat(loc.location()).isEqualTo("Prague, Czechia");
        assertThat(loc.countryCodes()).containsExactly("CZ");
    }

    @Test
    void resolvesCountryNamesAndCodes() {
        assertThat(JobLocation.countryCode("Netherlands")).contains("NL");
        assertThat(JobLocation.countryCode("the UK")).contains("GB");
        assertThat(JobLocation.countryCode("nl")).contains("NL");
        assertThat(JobLocation.countryCode("Atlantis")).isEmpty();
    }

    @Test
    void blankIsNone() {
        assertThat(JobLocation.parse("  ")).isEqualTo(JobLocation.NONE);
    }
}

package dev.shoaib.jobradar.sources.html;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class RelocateMeAdapterTest {

    private static final RelocateMeProperties PROPS = new RelocateMeProperties(
        true, "https://relocate.me", 5,
        List.of("back-end", "full-stack", "lead-developer", "team-lead", "other"),
        List.of("the-global-move", "micro1"));

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);
    private static final FetchContext CTX = new FetchContext(null, null, CLOCK);

    private final RelocateMeAdapter adapter = new RelocateMeAdapter(PROPS, mock(JobRecordRepository.class));

    private static String fixture(String path) {
        try (InputStream in = RelocateMeAdapterTest.class.getClassLoader().getResourceAsStream("fixtures/relocateme/" + path)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parsesListingCardsAndExcludesPartnerAds() {
        Document doc = Jsoup.parse(fixture("listing-page1.html"), "https://relocate.me/international-jobs");
        List<RelocateMeAdapter.ListingCard> cards = adapter.parseListingCards(doc);

        assertThat(cards).isNotEmpty();
        assertThat(cards).noneMatch(c -> c.url().contains("the-global-move"));

        RelocateMeAdapter.ListingCard paypay = cards.stream()
            .filter(c -> c.externalId().equals("9445"))
            .findFirst()
            .orElseThrow();
        assertThat(paypay.title()).isEqualTo("Principal Software Engineer");
        assertThat(paypay.company()).isEqualTo("PayPay");
        assertThat(paypay.city()).isEqualTo("Tokyo");
        assertThat(paypay.country()).isEqualTo("Japan");

        RelocateMeAdapter.ListingCard paypayCard = cards.stream()
            .filter(c -> c.externalId().equals("10180"))
            .findFirst()
            .orElseThrow();
        assertThat(paypayCard.company()).isEqualTo("PayPay Card");

        RelocateMeAdapter.ListingCard picnic = cards.stream()
            .filter(c -> c.externalId().equals("10261"))
            .findFirst()
            .orElseThrow();
        assertThat(picnic.company()).isEqualTo("Picnic");
        assertThat(picnic.city()).isEqualTo("Amsterdam");
    }

    @Test
    void oneMalformedCardDoesNotSinkTheWholeListingPage() {
        String html = """
            <div class="jobs-list__job">
              <div class="job__info">
                <div class="job__company text4-medium"><p>Japan</p></div>
                <div class="job__company text4-medium"><p>PayPay</p></div>
              </div>
              <div class="job__title">
                <a href="/japan/tokyo/paypay/backend-engineer-9999"><b>Backend Engineer</b> in Tokyo</a>
              </div>
            </div>
            <div class="jobs-list__job">
              <div class="job__info"></div>
              <div class="job__title">
                <a href="not-a-real-relative-href-at-all">broken</a>
              </div>
            </div>
            """;
        Document doc = Jsoup.parse(html, "https://relocate.me/international-jobs");
        List<RelocateMeAdapter.ListingCard> cards = adapter.parseListingCards(doc);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).externalId()).isEqualTo("9999");
    }

    @Test
    void parsesPayPayDetailPageWithVisaBenefitsAndTechTags() {
        var card = new RelocateMeAdapter.ListingCard("9445",
            "https://relocate.me/japan/tokyo/paypay/backend-engineer-10205", "Backend Engineer", "PayPay", "Tokyo",
            "Japan");
        Document doc = Jsoup.parse(fixture("paypay-backend-engineer.html"), card.url());

        JobPosting posting = adapter.parseDetailPage(doc, card, CTX);

        assertThat(posting.title()).isEqualTo("Backend Engineer");
        assertThat(posting.company()).isEqualTo("PayPay");
        assertThat(posting.city()).isEqualTo("Tokyo");
        assertThat(posting.country()).isEqualTo("Japan");
        assertThat(posting.benefits()).contains("Visa services", "Flight ticket", "Temporary housing",
            "Money for moving expenses");
        assertThat(posting.visaFlag()).isTrue();
        assertThat(posting.techTags()).contains("Java", "Go", "Golang", "Scala");
        assertThat(posting.description()).contains("PayPay is looking for a Backend Engineer");
    }

    @Test
    void parsesPayPayCardDetailPage() {
        var card = new RelocateMeAdapter.ListingCard("10180",
            "https://relocate.me/japan/tokyo/paypay-card/backend-engineer-10180", "Backend Engineer", "PayPay Card",
            "Tokyo", "Japan");
        Document doc = Jsoup.parse(fixture("paypay-card-backend-engineer.html"), card.url());

        JobPosting posting = adapter.parseDetailPage(doc, card, CTX);

        assertThat(posting.company()).isEqualTo("PayPay Card");
        assertThat(posting.benefits()).contains("Visa services");
        assertThat(posting.visaFlag()).isTrue();
    }

    @Test
    void parsesPicnicDetailPage() {
        var card = new RelocateMeAdapter.ListingCard("10261",
            "https://relocate.me/netherlands/amsterdam/picnic/senior-software-engineer-logistics-10261",
            "Senior Software Engineer - Logistics", "Picnic", "Amsterdam", "Netherlands");
        Document doc = Jsoup.parse(fixture("picnic-senior-software-engineer-logistics.html"), card.url());

        JobPosting posting = adapter.parseDetailPage(doc, card, CTX);

        assertThat(posting.company()).isEqualTo("Picnic");
        assertThat(posting.city()).isEqualTo("Amsterdam");
        assertThat(posting.country()).isEqualTo("Netherlands");
        assertThat(posting.benefits()).contains("Visa services", "Housing search assistance");
        assertThat(posting.visaFlag()).isTrue();
    }

    @Test
    void parsesHenngeDetailPage() {
        var card = new RelocateMeAdapter.ListingCard("10241",
            "https://relocate.me/japan/tokyo/hennge/senior-software-engineer-backend-infrastructure-10241",
            "Senior Software Engineer (Backend & Infrastructure)", "HENNGE", "Tokyo", "Japan");
        Document doc = Jsoup.parse(fixture("hennge-senior-software-engineer-backend-infrastructure.html"), card.url());

        JobPosting posting = adapter.parseDetailPage(doc, card, CTX);

        assertThat(posting.company()).isEqualTo("HENNGE");
        assertThat(posting.city()).isEqualTo("Tokyo");
        assertThat(posting.country()).isEqualTo("Japan");
        assertThat(posting.benefits()).contains("Visa services", "Flight ticket", "Language courses");
        assertThat(posting.visaFlag()).isTrue();
    }
}

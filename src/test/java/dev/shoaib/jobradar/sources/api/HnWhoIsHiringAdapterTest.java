package dev.shoaib.jobradar.sources.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shoaib.jobradar.core.JobPosting;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Feeds the real Algolia fixture JSON straight into the adapter's package-private parsing
 * methods, per the task's own suggestion, rather than standing up WireMock for this one --
 * exercising the comment-filtering logic doesn't need a live HTTP round trip.
 */
class HnWhoIsHiringAdapterTest {

    private static final long AUGUST_STORY_ID = 49156683L;

    private final HnWhoIsHiringAdapter adapter =
        new HnWhoIsHiringAdapter("https://hn.algolia.com/api/v1", true);

    @Test
    void nameAndRemovalDetectionFlag() {
        assertThat(adapter.name()).isEqualTo("hn-who-is-hiring");
        assertThat(adapter.supportsRemovalDetection()).isFalse();
    }

    @Test
    void parseCommentsPageExtractsAllHitsFromFixture() throws Exception {
        HnWhoIsHiringAdapter.CommentsPage page = adapter.parseCommentsPage(fixture("thread-2026-08-page0.json"));

        assertThat(page.comments()).hasSize(100); // hitsPerPage=100 in the captured fixture
        assertThat(page.page()).isEqualTo(0);
        assertThat(page.nbPages()).isEqualTo(3);
    }

    @Test
    void topLevelVisaAndTechMatchIsKeptAndConvertedToAPosting() throws Exception {
        HnWhoIsHiringAdapter.CommentsPage page = adapter.parseCommentsPage(fixture("thread-2026-08-page0.json"));

        HnWhoIsHiringAdapter.HnComment pango = find(page, "49171913");
        assertThat(adapter.isRelevant(pango, AUGUST_STORY_ID)).isTrue();

        JobPosting posting = adapter.toPosting(pango);

        assertThat(posting.company()).isEqualTo("Pango");
        assertThat(posting.title()).isEqualTo("Founding Software Engineer");
        assertThat(posting.externalId()).isEqualTo("49171913");
        assertThat(posting.url()).isEqualTo("https://news.ycombinator.com/item?id=49171913");
        assertThat(posting.visaFlag()).isTrue();
        assertThat(posting.techTags()).contains("go");
        assertThat(posting.postedAt()).isNotNull();
        assertThat(posting.description()).doesNotContain("<p>", "&#x27;");
    }

    @Test
    void replyToAReplyIsExcludedEvenIfContentWouldOtherwiseMatch() throws Exception {
        HnWhoIsHiringAdapter.CommentsPage page = adapter.parseCommentsPage(fixture("thread-2026-08-page0.json"));

        HnWhoIsHiringAdapter.HnComment reply = find(page, "49180436"); // parent_id 49171072 != story_id
        assertThat(reply.parentId()).isNotEqualTo(AUGUST_STORY_ID);
        assertThat(adapter.isRelevant(reply, AUGUST_STORY_ID)).isFalse();
    }

    @Test
    void topLevelCommentWithoutVisaOrRelocationMentionIsExcluded() throws Exception {
        HnWhoIsHiringAdapter.CommentsPage page = adapter.parseCommentsPage(fixture("thread-2026-08-page0.json"));

        HnWhoIsHiringAdapter.HnComment langfuse = find(page, "49180088"); // backend engineers, no visa/relocat mention
        assertThat(langfuse.parentId()).isEqualTo(AUGUST_STORY_ID);
        assertThat(adapter.isRelevant(langfuse, AUGUST_STORY_ID)).isFalse();
    }

    @Test
    void javaJobSeekerCommentStillMatchesTheCoarseFilter() throws Exception {
        // Best-effort coarse pre-filter: it can't distinguish a job-seeker's "hired?" post from
        // a company posting -- that's fine, match's real gate does the precise scoring later.
        HnWhoIsHiringAdapter.CommentsPage page = adapter.parseCommentsPage(fixture("thread-2026-08-page0.json"));

        HnWhoIsHiringAdapter.HnComment seeker = find(page, "49180607"); // "Willing to relocate...Technologies: Java..."
        assertThat(adapter.isRelevant(seeker, AUGUST_STORY_ID)).isTrue();
    }

    @Test
    void julyFixtureAlsoParsesCleanly() throws Exception {
        HnWhoIsHiringAdapter.CommentsPage page = adapter.parseCommentsPage(fixture("thread-2026-07-page0.json"));

        assertThat(page.comments()).hasSize(100);
        assertThat(page.nbPages()).isEqualTo(5);
    }

    private static HnWhoIsHiringAdapter.HnComment find(HnWhoIsHiringAdapter.CommentsPage page, String objectId) {
        return page.comments().stream().filter(c -> c.objectId().equals(objectId)).findFirst()
            .orElseThrow(() -> new AssertionError("fixture hit not found: " + objectId));
    }

    private static String fixture(String filename) throws IOException {
        try (InputStream in = HnWhoIsHiringAdapterTest.class.getResourceAsStream("/fixtures/hn/" + filename)) {
            if (in == null) {
                throw new IOException("fixture not found: " + filename);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

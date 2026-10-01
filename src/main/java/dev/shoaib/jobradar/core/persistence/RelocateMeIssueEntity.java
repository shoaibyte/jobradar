package dev.shoaib.jobradar.core.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps {@code relocateme_issue} (V2__relocateme_weekly.sql): one weekly job-list issue of
 * The Global Move. {@code advertisedCounts} is a JSON object stored as text.
 */
@Entity
@Table(name = "relocateme_issue")
public class RelocateMeIssueEntity {

    public static final String FULL = "FULL";
    public static final String PREVIEW = "PREVIEW";
    public static final String EMPTY = "EMPTY";
    /** Some embedded section tables failed to load; re-fetched on every pass. */
    public static final String PARTIAL = "PARTIAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "week_number")
    private Integer weekNumber;

    @Column(nullable = false)
    private String title;

    private String subtitle;

    @Column(name = "post_date", nullable = false)
    private String postDate;

    private String audience;

    @Column(name = "body_status", nullable = false)
    private String bodyStatus;

    @Column(name = "advertised_counts")
    private String advertisedCounts;

    @Column(name = "job_count", nullable = false)
    private int jobCount;

    @Column(name = "scraped_at", nullable = false)
    private String scrapedAt;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public Integer getWeekNumber() {
        return weekNumber;
    }

    public void setWeekNumber(Integer weekNumber) {
        this.weekNumber = weekNumber;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getPostDate() {
        return postDate;
    }

    public void setPostDate(String postDate) {
        this.postDate = postDate;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getBodyStatus() {
        return bodyStatus;
    }

    public void setBodyStatus(String bodyStatus) {
        this.bodyStatus = bodyStatus;
    }

    public String getAdvertisedCounts() {
        return advertisedCounts;
    }

    public void setAdvertisedCounts(String advertisedCounts) {
        this.advertisedCounts = advertisedCounts;
    }

    public int getJobCount() {
        return jobCount;
    }

    public void setJobCount(int jobCount) {
        this.jobCount = jobCount;
    }

    public String getScrapedAt() {
        return scrapedAt;
    }

    public void setScrapedAt(String scrapedAt) {
        this.scrapedAt = scrapedAt;
    }
}

package dev.shoaib.jobradar.core.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code relocateme_job} (V2__relocateme_weekly.sql): one entry of one weekly issue.
 * {@code keywords} and {@code countryCodes} are JSON arrays stored as text, same convention as
 * {@link JobRecordEntity}. Remote/country/LinkedIn columns come from V3__relocateme_job_location.sql.
 */
@Entity
@Table(name = "relocateme_job",
    uniqueConstraints = @UniqueConstraint(columnNames = {"issue_id", "section", "position"}))
public class RelocateMeJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "issue_id", nullable = false)
    private Integer issueId;

    @Column(nullable = false)
    private String section;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String title;

    private String company;

    private String location;

    private String city;

    private String country;

    @Column(name = "industry_size")
    private String industrySize;

    private String keywords;

    @Column(name = "apply_url", nullable = false)
    private String applyUrl;

    @Column(name = "canonical_url", nullable = false)
    private String canonicalUrl;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "visa_mentioned", nullable = false)
    private boolean visaMentioned;

    private String details;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(nullable = false)
    private boolean remote;

    @Column(name = "remote_region")
    private String remoteRegion;

    @Column(name = "country_codes")
    private String countryCodes;

    @Column(name = "company_linkedin_url")
    private String companyLinkedinUrl;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getIssueId() {
        return issueId;
    }

    public void setIssueId(Integer issueId) {
        this.issueId = issueId;
    }

    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getIndustrySize() {
        return industrySize;
    }

    public void setIndustrySize(String industrySize) {
        this.industrySize = industrySize;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public String getApplyUrl() {
        return applyUrl;
    }

    public void setApplyUrl(String applyUrl) {
        this.applyUrl = applyUrl;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public void setCanonicalUrl(String canonicalUrl) {
        this.canonicalUrl = canonicalUrl;
    }

    public boolean isVisaMentioned() {
        return visaMentioned;
    }

    public void setVisaMentioned(boolean visaMentioned) {
        this.visaMentioned = visaMentioned;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public boolean isRemote() {
        return remote;
    }

    public void setRemote(boolean remote) {
        this.remote = remote;
    }

    public String getRemoteRegion() {
        return remoteRegion;
    }

    public void setRemoteRegion(String remoteRegion) {
        this.remoteRegion = remoteRegion;
    }

    public String getCountryCodes() {
        return countryCodes;
    }

    public void setCountryCodes(String countryCodes) {
        this.countryCodes = countryCodes;
    }

    public String getCompanyLinkedinUrl() {
        return companyLinkedinUrl;
    }

    public void setCompanyLinkedinUrl(String companyLinkedinUrl) {
        this.companyLinkedinUrl = companyLinkedinUrl;
    }
}

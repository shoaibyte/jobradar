package dev.shoaib.jobradar.core.persistence;

import dev.shoaib.jobradar.core.JobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code job_record} (V1__init.sql). {@code techTags}/{@code benefits} are
 * JSON arrays stored as text — (de)serialize with a plain Jackson ObjectMapper
 * at the call site, this entity does not do it for you.
 */
@Entity
@Table(name = "job_record", uniqueConstraints = @UniqueConstraint(columnNames = {"source", "external_id"}))
public class JobRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String source;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(nullable = false)
    private String fingerprint;

    @Column(nullable = false)
    private String title;

    private String company;

    private String city;

    private String country;

    @Column(nullable = false)
    private String url;

    private String description;

    @Column(name = "tech_tags")
    private String techTags;

    private String benefits;

    @Column(name = "salary_raw")
    private String salaryRaw;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "visa_flag", nullable = false)
    private boolean visaFlag;

    @Column(name = "posted_at")
    private String postedAt;

    @Column(name = "first_seen", nullable = false)
    private String firstSeen;

    @Column(name = "last_seen", nullable = false)
    private String lastSeen;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.ACTIVE;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
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

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTechTags() {
        return techTags;
    }

    public void setTechTags(String techTags) {
        this.techTags = techTags;
    }

    public String getBenefits() {
        return benefits;
    }

    public void setBenefits(String benefits) {
        this.benefits = benefits;
    }

    public String getSalaryRaw() {
        return salaryRaw;
    }

    public void setSalaryRaw(String salaryRaw) {
        this.salaryRaw = salaryRaw;
    }

    public boolean isVisaFlag() {
        return visaFlag;
    }

    public void setVisaFlag(boolean visaFlag) {
        this.visaFlag = visaFlag;
    }

    public String getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(String postedAt) {
        this.postedAt = postedAt;
    }

    public String getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(String firstSeen) {
        this.firstSeen = firstSeen;
    }

    public String getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(String lastSeen) {
        this.lastSeen = lastSeen;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }
}

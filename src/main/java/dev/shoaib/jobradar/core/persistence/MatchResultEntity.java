package dev.shoaib.jobradar.core.persistence;

import dev.shoaib.jobradar.core.MatchStrength;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code match_result} (V1__init.sql). {@code jobId} is both the PK and
 * the FK to {@code job_record.id} — set it explicitly, it is not generated.
 * {@code reasons} is a JSON array stored as text.
 */
@Entity
@Table(name = "match_result")
public class MatchResultEntity {

    @Id
    @Column(name = "job_id")
    private Integer jobId;

    @Column(nullable = false)
    private double score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchStrength strength;

    private String reasons;

    @Column(name = "evaluated_at", nullable = false)
    private String evaluatedAt;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(nullable = false)
    private boolean notified;

    public Integer getJobId() {
        return jobId;
    }

    public void setJobId(Integer jobId) {
        this.jobId = jobId;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public MatchStrength getStrength() {
        return strength;
    }

    public void setStrength(MatchStrength strength) {
        this.strength = strength;
    }

    public String getReasons() {
        return reasons;
    }

    public void setReasons(String reasons) {
        this.reasons = reasons;
    }

    public String getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(String evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }

    public boolean isNotified() {
        return notified;
    }

    public void setNotified(boolean notified) {
        this.notified = notified;
    }
}

package dev.shoaib.jobradar.core.persistence;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RelocateMeJobRepository extends JpaRepository<RelocateMeJobEntity, Integer> {

    List<RelocateMeJobEntity> findByIssueIdOrderBySectionAscPositionAsc(Integer issueId);

    @Modifying
    @Query("delete from RelocateMeJobEntity j where j.issueId = :issueId")
    int deleteByIssueId(Integer issueId);

    /**
     * Every filter is optional (null = ignored). Text patterns arrive lower-cased and
     * already wrapped in {@code %}; {@code keyword} matches one whole JSON array element
     * ({@code %"java"%}), {@code countryCode} likewise ({@code %"NL"%}). Newest issue
     * first, then newsletter order within an issue.
     */
    @Query(value = """
        select j from RelocateMeJobEntity j, RelocateMeIssueEntity i
        where i.id = j.issueId
          and (:week is null or i.weekNumber = :week)
          and (:section is null or lower(j.section) = :section)
          and (:remote is null or j.remote = :remote)
          and (:region is null or lower(j.remoteRegion) like :region)
          and (:countryCode is null or j.countryCodes like :countryCode or lower(j.country) = :country)
          and (:keyword is null or lower(j.keywords) like :keyword)
          and (:q is null or lower(j.title) like :q or lower(j.company) like :q
               or lower(j.location) like :q or lower(j.keywords) like :q)
        order by i.postDate desc, j.id asc
        """, countQuery = """
        select count(j) from RelocateMeJobEntity j, RelocateMeIssueEntity i
        where i.id = j.issueId
          and (:week is null or i.weekNumber = :week)
          and (:section is null or lower(j.section) = :section)
          and (:remote is null or j.remote = :remote)
          and (:region is null or lower(j.remoteRegion) like :region)
          and (:countryCode is null or j.countryCodes like :countryCode or lower(j.country) = :country)
          and (:keyword is null or lower(j.keywords) like :keyword)
          and (:q is null or lower(j.title) like :q or lower(j.company) like :q
               or lower(j.location) like :q or lower(j.keywords) like :q)
        """)
    Page<RelocateMeJobEntity> search(Integer week, String section, Boolean remote, String region,
        String countryCode, String country, String keyword, String q, Pageable pageable);

    /** [section, job count] across all issues, most jobs first -- for a filter dropdown. */
    @Query("select j.section, count(j) from RelocateMeJobEntity j group by j.section order by count(j) desc")
    List<Object[]> sectionCounts();

    /** [country_codes JSON, job count] -- the controller splits the arrays into per-code counts. */
    @Query("select j.countryCodes, count(j) from RelocateMeJobEntity j group by j.countryCodes")
    List<Object[]> countryCodeCounts();

    /** [issueId, section, job count] -- per-issue breakdown for the issue list. */
    @Query("select j.issueId, j.section, count(j) from RelocateMeJobEntity j group by j.issueId, j.section "
        + "order by j.issueId, min(j.id)")
    List<Object[]> issueSectionCounts();
}

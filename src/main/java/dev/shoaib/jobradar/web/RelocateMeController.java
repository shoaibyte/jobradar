package dev.shoaib.jobradar.web;

import dev.shoaib.jobradar.core.persistence.RelocateMeIssueEntity;
import dev.shoaib.jobradar.core.persistence.RelocateMeIssueRepository;
import dev.shoaib.jobradar.core.persistence.RelocateMeJobEntity;
import dev.shoaib.jobradar.core.persistence.RelocateMeJobRepository;
import dev.shoaib.jobradar.sources.html.JobLocation;
import dev.shoaib.jobradar.web.dto.PageView;
import dev.shoaib.jobradar.web.dto.RelocateMeIssueView;
import dev.shoaib.jobradar.web.dto.RelocateMeJobView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read API over the weekly-issue archive ({@code relocateme_issue} / {@code relocateme_job}):
 * <ul>
 *   <li>{@code GET /api/relocateme/issues} -- every issue, newest first, with per-section counts</li>
 *   <li>{@code GET /api/relocateme/sections} -- section name -> job count, for a filter list</li>
 *   <li>{@code GET /api/relocateme/jobs?week=&section=&q=&keyword=&country=&remote=&region=&page=&size=}</li>
 *   <li>{@code GET /api/relocateme/jobs/{id}}</li>
 * </ul>
 * {@code country} takes an ISO alpha-2 code ({@code NL}) or a country name
 * ({@code Netherlands}, {@code UK}), resolved to the code; {@code keyword} matches one whole job keyword, case-insensitive;
 * {@code q} is a substring search over title, company, location and keywords.
 */
@RestController
public class RelocateMeController {

    private static final int MAX_PAGE_SIZE = 200;

    private final RelocateMeIssueRepository issueRepository;
    private final RelocateMeJobRepository jobRepository;
    private final JsonCodec jsonCodec;

    public RelocateMeController(RelocateMeIssueRepository issueRepository, RelocateMeJobRepository jobRepository,
        JsonCodec jsonCodec) {
        this.issueRepository = issueRepository;
        this.jobRepository = jobRepository;
        this.jsonCodec = jsonCodec;
    }

    @GetMapping("/api/relocateme/issues")
    public List<RelocateMeIssueView> issues() {
        Map<Integer, Map<String, Long>> sectionsByIssue = new LinkedHashMap<>();
        for (Object[] row : jobRepository.issueSectionCounts()) {
            sectionsByIssue.computeIfAbsent((Integer) row[0], k -> new LinkedHashMap<>())
                .put((String) row[1], ((Number) row[2]).longValue());
        }
        return issueRepository.findAllByOrderByPostDateDesc().stream()
            .map(issue -> new RelocateMeIssueView(issue.getId(), issue.getWeekNumber(), issue.getTitle(),
                issue.getSubtitle(), issue.getPostDate(), issue.getSlug(), issue.getBodyStatus(),
                issue.getJobCount(), sectionsByIssue.getOrDefault(issue.getId(), Map.of())))
            .toList();
    }

    @GetMapping("/api/relocateme/sections")
    public Map<String, Long> sections() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : jobRepository.sectionCounts()) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    @GetMapping("/api/relocateme/jobs")
    public PageView<RelocateMeJobView> jobs(
        @RequestParam(required = false) Integer week,
        @RequestParam(required = false) String section,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String country,
        @RequestParam(required = false) Boolean remote,
        @RequestParam(required = false) String region,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size) {

        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page: " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid size: " + size + " (1-" + MAX_PAGE_SIZE + ")");
        }
        // Names resolve to their code so "Netherlands" also finds multi-country jobs tagged NL;
        // an unknown name still matches the single-country column verbatim.
        String countryTrimmed = blankToNull(country);
        String code = countryTrimmed == null ? null
            : JobLocation.countryCode(countryTrimmed).orElse(countryTrimmed.toUpperCase(Locale.ROOT));
        Page<RelocateMeJobEntity> result = jobRepository.search(
            week,
            lower(section),
            remote,
            contains(region),
            code == null ? null : "%\"" + code + "\"%",
            lower(countryTrimmed),
            keyword == null || keyword.isBlank() ? null : "%\"" + keyword.trim().toLowerCase(Locale.ROOT) + "\"%",
            contains(q),
            PageRequest.of(page, size));

        Map<Integer, RelocateMeIssueEntity> issuesById = issueRepository
            .findAllById(result.getContent().stream().map(RelocateMeJobEntity::getIssueId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(RelocateMeIssueEntity::getId, Function.identity()));
        List<RelocateMeJobView> items = result.getContent().stream()
            .map(job -> toView(job, issuesById.get(job.getIssueId())))
            .toList();
        return new PageView<>(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/api/relocateme/jobs/{id}")
    public RelocateMeJobView job(@PathVariable Integer id) {
        RelocateMeJobEntity job = jobRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job " + id + " not found"));
        return toView(job, issueRepository.findById(job.getIssueId()).orElse(null));
    }

    private RelocateMeJobView toView(RelocateMeJobEntity job, RelocateMeIssueEntity issue) {
        return new RelocateMeJobView(
            job.getId(),
            job.getIssueId(),
            issue == null ? null : issue.getWeekNumber(),
            issue == null ? null : issue.getPostDate(),
            job.getSection(),
            job.getPosition(),
            job.getTitle(),
            job.getCompany(),
            job.getCompanyLinkedinUrl(),
            job.getLocation(),
            job.getCity(),
            job.getCountry(),
            jsonCodec.toStringList(job.getCountryCodes()),
            job.isRemote(),
            job.getRemoteRegion(),
            job.getIndustrySize(),
            jsonCodec.toStringList(job.getKeywords()),
            job.getApplyUrl(),
            job.isVisaMentioned(),
            job.getDetails() == null || job.getDetails().isBlank() ? List.of() : List.of(job.getDetails().split("\n")));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String lower(String s) {
        String t = blankToNull(s);
        return t == null ? null : t.toLowerCase(Locale.ROOT);
    }

    private static String contains(String s) {
        String t = lower(s);
        return t == null ? null : "%" + t + "%";
    }
}

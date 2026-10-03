package dev.shoaib.jobradar.web;

import dev.shoaib.jobradar.core.JobStatus;
import dev.shoaib.jobradar.core.MatchStrength;
import dev.shoaib.jobradar.core.persistence.JobRecordEntity;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import dev.shoaib.jobradar.core.persistence.MatchResultEntity;
import dev.shoaib.jobradar.core.persistence.MatchResultRepository;
import dev.shoaib.jobradar.web.dto.MatchView;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Backs {@code GET /api/matches?strength=&minScore=&status=&sort=} (Section 11).
 * {@code sort} is {@code collected} (default: newest first_seen first), {@code score} or
 * {@code posted}; ties fall back to score desc.
 */
@RestController
public class MatchController {

    private final MatchResultRepository matchResultRepository;
    private final JobRecordRepository jobRecordRepository;
    private final JsonCodec jsonCodec;

    public MatchController(MatchResultRepository matchResultRepository,
        JobRecordRepository jobRecordRepository, JsonCodec jsonCodec) {
        this.matchResultRepository = matchResultRepository;
        this.jobRecordRepository = jobRecordRepository;
        this.jsonCodec = jsonCodec;
    }

    @GetMapping("/api/matches")
    public List<MatchView> matches(
        @RequestParam(required = false) String strength,
        @RequestParam(required = false) Double minScore,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String sort) {

        MatchStrength strengthEnum = parseEnum(strength, MatchStrength.class, "strength");
        JobStatus statusEnum = parseEnum(status, JobStatus.class, "status");
        Comparator<MatchView> order = ordering(sort);

        List<MatchResultEntity> matches = matchResultRepository.search(strengthEnum, minScore, statusEnum);
        // One batched lookup instead of a findById per match.
        Map<Integer, JobRecordEntity> jobsById = jobRecordRepository
            .findAllById(matches.stream().map(MatchResultEntity::getJobId).toList()).stream()
            .collect(Collectors.toMap(JobRecordEntity::getId, Function.identity()));

        return matches.stream()
            .map(match -> toView(match, jobsById.get(match.getJobId())))
            .flatMap(Optional::stream)
            .sorted(order)
            .toList();
    }

    private Comparator<MatchView> ordering(String sort) {
        String key = sort == null || sort.isBlank() ? "collected" : sort.trim().toLowerCase(Locale.ROOT);
        Comparator<MatchView> byScore = Comparator.comparingDouble(MatchView::score).reversed();
        // Parsed rather than compared as text: Instant.toString() trims trailing zeros, so
        // the stored strings vary in length. Missing/unparseable values (posted_at is
        // optional) go last.
        return switch (key) {
            case "collected" -> Comparator.comparing((MatchView m) -> parseInstant(m.firstSeen()),
                Comparator.nullsLast(Comparator.<Instant>reverseOrder())).thenComparing(byScore);
            case "posted" -> Comparator.comparing((MatchView m) -> parseInstant(m.postedAt()),
                Comparator.nullsLast(Comparator.<Instant>reverseOrder())).thenComparing(byScore);
            case "score" -> byScore;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid sort: " + sort);
        };
    }

    private Optional<MatchView> toView(MatchResultEntity match, JobRecordEntity job) {
        // jobId is a real FK, but be defensive rather than 500 on an inconsistent row.
        return Optional.ofNullable(job).map(j -> new MatchView(
            job.getId(),
            job.getTitle(),
            job.getCompany(),
            job.getCity(),
            job.getCountry(),
            job.getUrl(),
            match.getStrength(),
            match.getScore(),
            jsonCodec.toStringList(match.getReasons()),
            job.isVisaFlag(),
            job.getSalaryRaw(),
            job.getSource(),
            jsonCodec.toStringList(job.getTechTags()),
            job.getStatus(),
            job.getPostedAt(),
            job.getFirstSeen(),
            job.getLastSeen()));
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Parses an optional, case-insensitive query param into an enum; null input passes through as null. */
    private <E extends Enum<E>> E parseEnum(String raw, Class<E> type, String paramName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + paramName + ": " + raw);
        }
    }
}

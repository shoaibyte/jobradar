package dev.shoaib.jobradar.web;

import dev.shoaib.jobradar.core.JobStatus;
import dev.shoaib.jobradar.core.MatchStrength;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import dev.shoaib.jobradar.core.persistence.MatchResultEntity;
import dev.shoaib.jobradar.core.persistence.MatchResultRepository;
import dev.shoaib.jobradar.web.dto.MatchView;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Backs {@code GET /api/matches?strength=&minScore=&status=} (Section 11). */
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
        @RequestParam(required = false) String status) {

        MatchStrength strengthEnum = parseEnum(strength, MatchStrength.class, "strength");
        JobStatus statusEnum = parseEnum(status, JobStatus.class, "status");

        // MatchResultRepository.search() already orders by score desc.
        return matchResultRepository.search(strengthEnum, minScore, statusEnum).stream()
            .map(this::toView)
            .flatMap(Optional::stream)
            .toList();
    }

    private Optional<MatchView> toView(MatchResultEntity match) {
        // jobId is a real FK, but be defensive rather than 500 on an inconsistent row.
        return jobRecordRepository.findById(match.getJobId()).map(job -> new MatchView(
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
            job.getSalaryRaw()));
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

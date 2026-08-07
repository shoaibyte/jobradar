package dev.shoaib.jobradar.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Small helper for turning the JSON-as-text columns on {@code job_record},
 * {@code match_result} and {@code ingest_run} (tech_tags/benefits/reasons/stats) back
 * into real JSON structures for API responses, instead of leaking the raw stored string
 * to consumers. Uses its own {@link ObjectMapper} instance (classic Jackson 2, still on
 * the classpath transitively) rather than an injected bean -- Spring Boot 4's own Jackson
 * autoconfiguration now produces a {@code tools.jackson.databind.json.JsonMapper} (Jackson
 * 3) bean instead, not a {@code com.fasterxml.jackson.databind.ObjectMapper}, matching the
 * pattern every other adapter in this codebase already uses for the same reason.
 */
@Component
public class JsonCodec {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<String> toStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    public Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}

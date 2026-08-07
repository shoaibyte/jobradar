package dev.shoaib.jobradar.notify;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Posts a single incoming-webhook message per run summarizing all new matches.
 * No-ops silently when {@code app.notify.slack.webhook-url} is blank.
 *
 * <p>Takes a built {@link RestClient} (see {@link NotifyConfig}) rather than a
 * {@code RestClient.Builder} specifically so tests can construct this class
 * directly with a mock {@link RestClient} and assert no calls are made when
 * config is blank, without needing to stub a builder chain.
 */
@Component
public class SlackNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SlackNotificationChannel.class);

    private final RestClient restClient;
    private final String webhookUrl;

    public SlackNotificationChannel(RestClient restClient,
        @Value("${app.notify.slack.webhook-url:}") String webhookUrl) {
        this.restClient = restClient;
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void notify(List<MatchNotification> newMatches) {
        if (newMatches == null || newMatches.isEmpty() || webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        try {
            String text = NotificationFormatter.formatBatch(newMatches);
            restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", text))
                .retrieve()
                .toBodilessEntity();
        } catch (RuntimeException e) {
            log.error("Failed to send Slack notification", e);
        }
    }
}

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
 * Sends a single {@code sendMessage} call per run summarizing all new matches.
 * No-ops silently when either the bot token or chat id is blank.
 */
@Component
public class TelegramNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationChannel.class);

    private final RestClient restClient;
    private final String botToken;
    private final String chatId;

    public TelegramNotificationChannel(RestClient restClient,
        @Value("${app.notify.telegram.bot-token:}") String botToken,
        @Value("${app.notify.telegram.chat-id:}") String chatId) {
        this.restClient = restClient;
        this.botToken = botToken;
        this.chatId = chatId;
    }

    @Override
    public void notify(List<MatchNotification> newMatches) {
        if (newMatches == null || newMatches.isEmpty()
            || botToken == null || botToken.isBlank()
            || chatId == null || chatId.isBlank()) {
            return;
        }
        try {
            String text = NotificationFormatter.formatBatch(newMatches);
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("chat_id", chatId, "text", text))
                .retrieve()
                .toBodilessEntity();
        } catch (RuntimeException e) {
            log.error("Failed to send Telegram notification", e);
        }
    }
}

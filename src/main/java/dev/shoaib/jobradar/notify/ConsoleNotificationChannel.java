package dev.shoaib.jobradar.notify;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Always-on channel: logs the batch of new matches at INFO. */
@Component
public class ConsoleNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(ConsoleNotificationChannel.class);

    @Override
    public void notify(List<MatchNotification> newMatches) {
        if (newMatches == null || newMatches.isEmpty()) {
            return;
        }
        log.info("\n{}", NotificationFormatter.formatBatch(newMatches));
    }
}

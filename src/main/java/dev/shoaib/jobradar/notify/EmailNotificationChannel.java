package dev.shoaib.jobradar.notify;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Disabled by default ({@code app.notify.email.enabled=false}). When enabled,
 * sends a single email per run summarizing all new matches via Spring Boot's
 * auto-configured {@link JavaMailSender} (backed by {@code spring.mail.*}).
 */
@Component
public class EmailNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationChannel.class);

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String to;

    public EmailNotificationChannel(JavaMailSender mailSender,
        @Value("${app.notify.email.enabled:false}") boolean enabled,
        @Value("${app.notify.email.to:}") String to) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.to = to;
    }

    @Override
    public void notify(List<MatchNotification> newMatches) {
        if (!enabled || newMatches == null || newMatches.isEmpty() || to == null || to.isBlank()) {
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject("JobRadar: " + newMatches.size() + " new match"
                + (newMatches.size() == 1 ? "" : "es"));
            message.setText(NotificationFormatter.formatBatch(newMatches));
            mailSender.send(message);
        } catch (RuntimeException e) {
            log.error("Failed to send email notification", e);
        }
    }
}

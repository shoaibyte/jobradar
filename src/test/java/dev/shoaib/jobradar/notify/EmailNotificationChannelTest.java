package dev.shoaib.jobradar.notify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class EmailNotificationChannelTest {

    @Test
    void noOpsWhenDisabled() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailNotificationChannel channel = new EmailNotificationChannel(mailSender, false, "someone@example.com");

        channel.notify(List.of(sampleMatch()));

        verifyNoInteractions(mailSender);
    }

    @Test
    void noOpsWhenToAddressBlankEvenIfEnabled() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailNotificationChannel channel = new EmailNotificationChannel(mailSender, true, "");

        channel.notify(List.of(sampleMatch()));

        verifyNoInteractions(mailSender);
    }

    @Test
    void noOpsWhenThereAreNoNewMatches() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailNotificationChannel channel = new EmailNotificationChannel(mailSender, true, "someone@example.com");

        channel.notify(List.of());

        verifyNoInteractions(mailSender);
    }

    @Test
    void sendsExactlyOneEmailWhenEnabledAndConfigured() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailNotificationChannel channel = new EmailNotificationChannel(mailSender, true, "someone@example.com");

        channel.notify(List.of(sampleMatch(), sampleMatch()));

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    private MatchNotification sampleMatch() {
        return new MatchNotification("PARTIAL", 4.0, "Software Engineer", "Acme", "Vienna", "AT",
            List.of("Kotlin"), "JVM backend role", "50k-65k", "https://example.com/job3");
    }
}

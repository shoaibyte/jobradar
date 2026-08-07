package dev.shoaib.jobradar.notify;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TelegramNotificationChannelTest {

    @Test
    void noOpsWhenBotTokenBlank() {
        RestClient restClient = mock(RestClient.class);
        TelegramNotificationChannel channel = new TelegramNotificationChannel(restClient, "", "12345");

        channel.notify(List.of(sampleMatch()));

        verifyNoInteractions(restClient);
    }

    @Test
    void noOpsWhenChatIdBlank() {
        RestClient restClient = mock(RestClient.class);
        TelegramNotificationChannel channel = new TelegramNotificationChannel(restClient, "bot-token", "");

        channel.notify(List.of(sampleMatch()));

        verifyNoInteractions(restClient);
    }

    @Test
    void noOpsWhenBothBlank() {
        RestClient restClient = mock(RestClient.class);
        TelegramNotificationChannel channel = new TelegramNotificationChannel(restClient, null, null);

        channel.notify(List.of(sampleMatch()));

        verifyNoInteractions(restClient);
    }

    @Test
    void sendsExactlyOneBatchedMessageWhenConfigured() {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        TelegramNotificationChannel channel = new TelegramNotificationChannel(restClient, "bot-token", "12345");

        channel.notify(List.of(sampleMatch(), sampleMatch()));

        verify(restClient, times(1)).post();
    }

    private MatchNotification sampleMatch() {
        return new MatchNotification("MATCH", 5.0, "Platform Engineer", "Acme", "Amsterdam", "NL",
            List.of("Go", "Kubernetes"), "Go microservices role", null, "https://example.com/job2");
    }
}

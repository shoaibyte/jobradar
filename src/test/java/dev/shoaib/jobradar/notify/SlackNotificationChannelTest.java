package dev.shoaib.jobradar.notify;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SlackNotificationChannelTest {

    @Test
    void noOpsWhenWebhookUrlBlank() {
        RestClient restClient = mock(RestClient.class);
        SlackNotificationChannel channel = new SlackNotificationChannel(restClient, "");

        channel.notify(List.of(sampleMatch()));

        verifyNoInteractions(restClient);
    }

    @Test
    void noOpsWhenWebhookUrlNull() {
        RestClient restClient = mock(RestClient.class);
        SlackNotificationChannel channel = new SlackNotificationChannel(restClient, null);

        channel.notify(List.of(sampleMatch()));

        verifyNoInteractions(restClient);
    }

    @Test
    void noOpsWhenThereAreNoNewMatches() {
        RestClient restClient = mock(RestClient.class);
        SlackNotificationChannel channel = new SlackNotificationChannel(restClient, "https://hooks.slack.example/xyz");

        channel.notify(List.of());

        verifyNoInteractions(restClient);
    }

    @Test
    void postsExactlyOneBatchedMessageWhenConfigured() {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        SlackNotificationChannel channel = new SlackNotificationChannel(restClient, "https://hooks.slack.example/xyz");

        channel.notify(List.of(sampleMatch(), sampleMatch()));

        verify(restClient, times(1)).post();
    }

    private MatchNotification sampleMatch() {
        return new MatchNotification("STRONG", 8.0, "Backend Engineer", "Acme", "Berlin", "DE",
            List.of("Java"), "Java backend role", "60k-80k", "https://example.com/job");
    }
}

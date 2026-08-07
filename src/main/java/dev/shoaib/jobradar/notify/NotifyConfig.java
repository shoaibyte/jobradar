package dev.shoaib.jobradar.notify;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Provides the single {@link RestClient} shared by the outbound notification
 * channels (Slack, Telegram). Kept separate from {@code pipeline}'s own
 * {@code HttpFetcher} implementation, which needs per-host throttling/robots
 * handling that notification webhooks don't.
 */
@Configuration
class NotifyConfig {

    @Bean
    RestClient notificationRestClient(RestClient.Builder builder) {
        return builder.build();
    }
}

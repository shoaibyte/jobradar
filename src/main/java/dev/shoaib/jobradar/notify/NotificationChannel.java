package dev.shoaib.jobradar.notify;

import java.util.List;

/**
 * Internal contract between {@code pipeline} (which decides what needs notifying)
 * and {@code notify} (which decides how/where to send it). Not a cross-agent
 * contract -- both packages are owned by pipeline-notify, so this interface is
 * free to change as needed.
 *
 * <p>Implementations must batch all matches from a single call into ONE outbound
 * message (Slack post, Telegram message, email) rather than one per match.
 */
public interface NotificationChannel {

    void notify(List<MatchNotification> newMatches);
}

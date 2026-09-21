package com.shylesh.notification_service.channel;

import com.shylesh.notification_service.persistance.NotificationChannelType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a NotificationChannelType to its NotificationChannel bean. Adding a new channel
 * (SMS, push) means adding a @Component implementing NotificationChannel — this registry
 * picks it up automatically, no changes needed here or in the dispatcher.
 */
@Component
public class NotificationChannelRegistry {

    private final Map<NotificationChannelType, NotificationChannel> channelsByType;

    public NotificationChannelRegistry(List<NotificationChannel> channels) {
        this.channelsByType = channels.stream()
                .collect(Collectors.toMap(NotificationChannel::getType, Function.identity()));
    }

    public NotificationChannel resolve(NotificationChannelType type) {
        NotificationChannel channel = channelsByType.get(type);
        if (channel == null) {
            throw new IllegalStateException("No NotificationChannel registered for type " + type);
        }
        return channel;
    }
}

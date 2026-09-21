package com.shylesh.notification_service.rules;

import com.shylesh.notification_service.persistance.NotificationChannelType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRulesEngineTest {

    private final NotificationRulesEngine rulesEngine = new NotificationRulesEngine();

    @Test
    void resolvesEmailForEachKnownPaymentEventType() {
        assertThat(rulesEngine.resolveChannels("PAYMENT_CREATED"))
                .containsExactly(NotificationChannelType.EMAIL);
        assertThat(rulesEngine.resolveChannels("PAYMENT_COMPLETED"))
                .containsExactly(NotificationChannelType.EMAIL);
        assertThat(rulesEngine.resolveChannels("PAYMENT_FAILED"))
                .containsExactly(NotificationChannelType.EMAIL);
        assertThat(rulesEngine.resolveChannels("PAYMENT_REFUNDED"))
                .containsExactly(NotificationChannelType.EMAIL);
    }

    @Test
    void returnsEmptyListForUnknownEventType() {
        assertThat(rulesEngine.resolveChannels("SOME_UNRELATED_EVENT")).isEmpty();
    }
}

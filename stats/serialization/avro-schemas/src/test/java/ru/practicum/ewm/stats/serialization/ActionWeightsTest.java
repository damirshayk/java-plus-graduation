package ru.practicum.ewm.stats.serialization;

import org.junit.jupiter.api.Test;
import ru.practicum.ewm.stats.avro.ActionTypeAvro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionWeightsTest {
    @Test
    void shouldSelectConfiguredActionWeight() {
        ActionWeights weights = new ActionWeights(0.4, 0.8, 1.0);

        assertThat(weights.weight(ActionTypeAvro.VIEW)).isEqualTo(0.4);
        assertThat(weights.weight(ActionTypeAvro.REGISTER)).isEqualTo(0.8);
        assertThat(weights.weight(ActionTypeAvro.LIKE)).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidConfiguredWeights() {
        assertThatThrownBy(() -> new ActionWeights(-0.1, 0.8, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActionWeights(Double.NaN, 0.8, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActionWeights(0.4, Double.POSITIVE_INFINITY, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActionWeights(0.4, 0.8, 1.1)).isInstanceOf(IllegalArgumentException.class);
    }
}

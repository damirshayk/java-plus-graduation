package ru.practicum.ewm.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class CleanupEventCodecTest {
    private final CleanupEventCodec codec = new CleanupEventCodec(new ObjectMapper());

    @Test
    void snapshotMustBeImmutableDistinctAndSorted() {
        List<Long> ids = new ArrayList<>(List.of(30L, 10L, 30L));
        CommonCleanupEvent event = new CommonCleanupEvent(UUID.randomUUID(),
                CleanupEventType.USER_EVENTS_DELETED, 1L, ids);
        ids.clear();

        assertThat(event.eventIds()).containsExactly(10L, 30L);
        assertThatThrownBy(() -> event.eventIds().add(20L)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(codec.decode(codec.encode(event))).isEqualTo(event);
        assertThat(codec.encode(event)).doesNotContain("schemaVersion");
    }

    @Test
    void invalidIdentifiersAndFirstTypeWithEventsMustBeRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new CommonCleanupEvent(null, CleanupEventType.USER_DELETED, 1L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommonCleanupEvent(id, null, 1L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        for (Long userId : Arrays.asList(null, 0L, -1L)) {
            assertThatThrownBy(() -> new CommonCleanupEvent(id, CleanupEventType.USER_DELETED, userId, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (List<Long> eventIds : Arrays.asList(null, Arrays.asList((Long) null), List.of(0L), List.of(-1L))) {
            assertThatThrownBy(() -> new CommonCleanupEvent(id, CleanupEventType.USER_EVENTS_DELETED, 1L, eventIds))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new CommonCleanupEvent(id, CleanupEventType.USER_DELETED, 1L, List.of(10L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedOrUnknownPayloadMustNeverBecomeAValidCommand() {
        String valid = codec.encode(new CommonCleanupEvent(UUID.randomUUID(), CleanupEventType.USER_DELETED, 1L, List.of()));
        for (String payload : List.of("null", "{}", "broken", valid.replace("USER_DELETED", "UNKNOWN"),
                valid.replace("\"userId\":1", "\"userId\":0"))) {
            assertThatThrownBy(() -> codec.decode(payload)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trailingJsonAndFractionalIdentifiersMustNotBeSilentlyAccepted() {
        String valid = codec.encode(new CommonCleanupEvent(UUID.randomUUID(), CleanupEventType.USER_DELETED, 1L, List.of()));
        assertThatThrownBy(() -> codec.decode(valid + " {}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(valid.replace("\"userId\":1", "\"userId\":1.5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numericEventTypesMustNotBecomeDeletionCommands() {
        String valid = codec.encode(new CommonCleanupEvent(UUID.randomUUID(), CleanupEventType.USER_DELETED, 1L, List.of()));
        for (String value : List.of("0", "\"0\"")) {
            assertThatThrownBy(() -> codec.decode(valid.replace("\"USER_DELETED\"", value)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

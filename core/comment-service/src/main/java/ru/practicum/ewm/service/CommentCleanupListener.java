package ru.practicum.ewm.service;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.practicum.ewm.cleanup.CleanupEventCodec;
import ru.practicum.ewm.cleanup.CommonCleanupEvent;

@Component
@RequiredArgsConstructor
public class CommentCleanupListener {
    private final CleanupEventCodec codec;
    private final CommentDataCleanupService cleanup;

    @KafkaListener(topics = "${ewm.cleanup.topic:ewm.user-data-cleanup.v1}",
            groupId = "${ewm.cleanup.group-id:ewm-comment-cleanup-v1}")
    public void consume(String payload) {
        CommonCleanupEvent event = codec.decode(payload);
        cleanup.deleteUserData(event.userId(), event.eventIds());
    }
}

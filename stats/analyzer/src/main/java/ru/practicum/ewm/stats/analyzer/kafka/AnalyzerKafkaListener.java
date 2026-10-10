package ru.practicum.ewm.stats.analyzer.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.practicum.ewm.stats.analyzer.service.AnalyzerIngestionService;
import ru.practicum.ewm.stats.avro.EventSimilarityAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;

@Component
public class AnalyzerKafkaListener {
    private final AnalyzerIngestionService ingestion;

    public AnalyzerKafkaListener(AnalyzerIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @KafkaListener(id = "analyzer-actions", groupId = "analyzer-actions",
            topics = "${stats.kafka.topics.user-actions}", containerFactory = "analyzerActionsFactory")
    public void onUserAction(UserActionAvro action) {
        ingestion.saveAction(action);
    }

    @KafkaListener(id = "analyzer-similarities", groupId = "analyzer-similarities",
            topics = "${stats.kafka.topics.events-similarity}", containerFactory = "analyzerSimilaritiesFactory")
    public void onSimilarity(EventSimilarityAvro similarity) {
        ingestion.saveSimilarity(similarity);
    }
}

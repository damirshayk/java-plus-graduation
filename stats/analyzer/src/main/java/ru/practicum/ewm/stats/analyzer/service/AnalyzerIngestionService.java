package ru.practicum.ewm.stats.analyzer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.stats.analyzer.repository.InteractionRepository;
import ru.practicum.ewm.stats.analyzer.repository.SimilarityRepository;
import ru.practicum.ewm.stats.avro.EventSimilarityAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;
import ru.practicum.ewm.stats.serialization.ActionWeights;

@Service
public class AnalyzerIngestionService {
    private final InteractionRepository interactions;
    private final SimilarityRepository similarities;
    private final ActionWeights weights;

    public AnalyzerIngestionService(InteractionRepository interactions, SimilarityRepository similarities,
                                    @Value("${stats.weights.view:0.4}") double view,
                                    @Value("${stats.weights.register:0.8}") double register,
                                    @Value("${stats.weights.like:1.0}") double like) {
        this.interactions = interactions;
        this.similarities = similarities;
        this.weights = new ActionWeights(view, register, like);
    }

    @Transactional
    public void saveAction(UserActionAvro action) {
        if (action == null || action.getUserId() <= 0 || action.getEventId() <= 0
                || action.getActionType() == null || action.getTimestamp() == null) {
            throw new IllegalArgumentException("Некорректное действие пользователя");
        }
        double weight = weights.weight(action.getActionType());
        if (interactions.updateMaximum(action.getUserId(), action.getEventId(), weight, action.getTimestamp()) == 0) {
            interactions.insert(action.getUserId(), action.getEventId(), weight, action.getTimestamp());
        }
    }

    @Transactional
    public void saveSimilarity(EventSimilarityAvro similarity) {
        if (similarity == null || similarity.getEventA() <= 0 || similarity.getEventA() >= similarity.getEventB()
                || similarity.getTimestamp() == null || !Double.isFinite(similarity.getScore())
                || similarity.getScore() < 0 || similarity.getScore() > 1) {
            throw new IllegalArgumentException("Некорректное сходство мероприятий");
        }
        if (similarities.update(similarity.getEventA(), similarity.getEventB(),
                similarity.getScore(), similarity.getTimestamp()) == 0) {
            similarities.insert(similarity.getEventA(), similarity.getEventB(),
                    similarity.getScore(), similarity.getTimestamp());
        }
    }
}

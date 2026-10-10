package ru.practicum.ewm.stats.analyzer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.stats.analyzer.model.Interaction;
import ru.practicum.ewm.stats.analyzer.repository.InteractionRepository;
import ru.practicum.ewm.stats.analyzer.repository.SimilarityRepository;
import ru.practicum.ewm.stats.proto.RecommendedEventProto;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RecommendationService {
    private final InteractionRepository interactions;
    private final SimilarityRepository similarities;
    private final RecommendationCalculator calculator;
    private final int historySize;
    private final int neighborsSize;

    public RecommendationService(InteractionRepository interactions, SimilarityRepository similarities,
                                  RecommendationCalculator calculator,
                                  @Value("${stats.recommendations.history-size:10}") int historySize,
                                  @Value("${stats.recommendations.neighbors-size:5}") int neighborsSize) {
        if (historySize <= 0 || neighborsSize <= 0) {
            throw new IllegalArgumentException("Размер истории и число соседей должны быть положительными");
        }
        this.interactions = interactions;
        this.similarities = similarities;
        this.calculator = calculator;
        this.historySize = historySize;
        this.neighborsSize = neighborsSize;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RecommendedEventProto> recommendations(long userId, int maxResults) {
        List<Interaction> history = interactions.findByUserIdOrderByLastInteractionTimeDescEventIdAsc(userId);
        if (history.isEmpty()) {
            return List.of();
        }
        Set<Long> knownEvents = history.stream().map(Interaction::getEventId).collect(Collectors.toSet());
        Set<Long> recentEvents = history.stream().limit(historySize).map(Interaction::getEventId)
                .collect(Collectors.toSet());
        Set<Long> candidates = calculator.candidates(similarities.findByEvents(recentEvents),
                recentEvents, knownEvents, maxResults);
        if (candidates.isEmpty()) {
            return List.of();
        }
        return calculator.predictions(candidates, history, similarities.findCandidateNeighbors(candidates, knownEvents),
                neighborsSize, maxResults);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RecommendedEventProto> similar(long eventId, long userId, int maxResults) {
        Set<Long> knownEvents = new HashSet<>(interactions.findEventIdsByUserId(userId));
        return calculator.similar(eventId, similarities.findByEvent(eventId), knownEvents, maxResults);
    }

    public List<RecommendedEventProto> interactions(List<Long> eventIds) {
        Set<Long> uniqueIds = new LinkedHashSet<>(eventIds);
        if (uniqueIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Double> totals = new HashMap<>();
        for (InteractionRepository.EventWeight total : interactions.sumWeights(uniqueIds)) {
            totals.put(total.getEventId(), total.getWeight());
        }
        return uniqueIds.stream().map(id -> RecommendedEventProto.newBuilder().setEventId(id)
                .setScore(totals.getOrDefault(id, 0.0)).build()).toList();
    }
}

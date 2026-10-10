package ru.practicum.ewm.stats.analyzer.service;

import org.springframework.stereotype.Component;
import ru.practicum.ewm.stats.analyzer.model.EventSimilarity;
import ru.practicum.ewm.stats.analyzer.model.Interaction;
import ru.practicum.ewm.stats.proto.RecommendedEventProto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Класс для вычисления рекомендаций событий на основе сходства событий и истории взаимодействий пользователей.
 */
@Component
public class RecommendationCalculator {
    private static final Comparator<RecommendedEventProto> SCORE_ORDER = Comparator
            .comparingDouble(RecommendedEventProto::getScore).reversed()
            .thenComparingLong(RecommendedEventProto::getEventId);
    private static final Comparator<Map.Entry<Long, Double>> NEIGHBOR_ORDER = Map.Entry
            .<Long, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey());

    /**
     * Вычисляет список похожих событий на основе заданного события и списка сходств.
     *
     * @param eventId     идентификатор события, для которого ищутся похожие события
     * @param edges       список сходств между событиями
     * @param knownEvents множество известных событий, которые не должны быть включены в результаты
     * @param maxResults  максимальное количество результатов
     * @return список похожих событий с их оценками
     */
    public List<RecommendedEventProto> similar(long eventId, List<EventSimilarity> edges,
                                                Set<Long> knownEvents, int maxResults) {
        Map<Long, Double> scores = new HashMap<>();
        for (EventSimilarity edge : edges) {
            if (edge.getEventA() == eventId) {
                addCandidate(scores, edge.getEventB(), edge.getScore(), knownEvents);
            } else if (edge.getEventB() == eventId) {
                addCandidate(scores, edge.getEventA(), edge.getScore(), knownEvents);
            }
        }
        scores.remove(eventId);
        return scores.entrySet().stream().map(entry -> result(entry.getKey(), entry.getValue()))
                .sorted(SCORE_ORDER).limit(maxResults).toList();
    }

    /**
     * Вычисляет кандидатов на рекомендацию по сходствам и последним взаимодействиям текущего пользователя.
     *
     * @param edges       список сходств между событиями
     * @param recentEvents мероприятия, с которыми пользователь недавно взаимодействовал
     * @param knownEvents множество известных событий, которые не должны быть включены в результаты
     * @param limit       максимальное количество результатов
     * @return множество кандидатов на рекомендацию
     */
    public Set<Long> candidates(List<EventSimilarity> edges, Set<Long> recentEvents,
                                 Set<Long> knownEvents, int limit) {
        Map<Long, Double> scores = new HashMap<>();
        for (EventSimilarity edge : edges) {
            if (recentEvents.contains(edge.getEventA())) {
                addCandidate(scores, edge.getEventB(), edge.getScore(), knownEvents);
            }
            if (recentEvents.contains(edge.getEventB())) {
                addCandidate(scores, edge.getEventA(), edge.getScore(), knownEvents);
            }
        }
        return scores.entrySet().stream().sorted(NEIGHBOR_ORDER).limit(limit).map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Вычисляет рекомендации по кандидатам и истории взаимодействий текущего пользователя.
     * Прогноз: sum(similarity * userWeight) / sum(similarity) по ближайшим соседям.
     *
     * @param candidates  множество кандидатов на рекомендацию
     * @param history     история взаимодействий текущего пользователя
     * @param edges       список сходств между событиями
     * @param neighbors   максимальное количество соседей для каждой рекомендации
     * @param maxResults  максимальное количество результатов
     * @return список рекомендаций с их оценками
     */
    public List<RecommendedEventProto> predictions(Set<Long> candidates, List<Interaction> history,
                                                    List<EventSimilarity> edges, int neighbors, int maxResults) {

        Map<Long, Double> weights = new HashMap<>();
        for (Interaction interaction : history) {
            weights.put(interaction.getEventId(), interaction.getWeight());
        }

        Map<Long, Map<Long, Double>> candidateNeighbors = new HashMap<>();
        for (EventSimilarity edge : edges) {
            addNeighbor(candidateNeighbors, candidates, weights, edge.getEventA(), edge.getEventB(), edge.getScore());
            addNeighbor(candidateNeighbors, candidates, weights, edge.getEventB(), edge.getEventA(), edge.getScore());
        }
        List<RecommendedEventProto> predictions = new ArrayList<>();
        for (Map.Entry<Long, Map<Long, Double>> candidate : candidateNeighbors.entrySet()) {
            List<Map.Entry<Long, Double>> nearest = candidate.getValue().entrySet().stream()
                    .sorted(NEIGHBOR_ORDER).limit(neighbors).toList();
            double scale = nearest.getFirst().getValue();
            double weightedSum = 0;
            double similaritySum = 0;
            for (Map.Entry<Long, Double> neighbor : nearest) {
                // Масштабирование сохраняет среднее и не даёт малым коэффициентам исчезнуть при умножении.
                double normalized = neighbor.getValue() / scale;
                weightedSum += normalized * weights.get(neighbor.getKey());
                similaritySum += normalized;
            }
            double score = weightedSum / similaritySum;
            if (score > 0) {
                predictions.add(result(candidate.getKey(), Math.min(1, score)));
            }
        }
        return predictions.stream().sorted(SCORE_ORDER).limit(maxResults).toList();
    }

    private void addCandidate(Map<Long, Double> scores, long eventId, double score, Set<Long> knownEvents) {
        if (score > 0 && !knownEvents.contains(eventId)) {
            scores.merge(eventId, score, Math::max);
        }
    }

    // Для повторной пары «кандидат — известное событие» сохраняем максимальное сходство.
    private void addNeighbor(Map<Long, Map<Long, Double>> neighbors, Set<Long> candidates, Map<Long, Double> weights,
                             long candidate, long knownEvent, double score) {
        if (score > 0 && candidates.contains(candidate) && weights.containsKey(knownEvent)) {
            neighbors.computeIfAbsent(candidate, key -> new HashMap<>()).merge(knownEvent, score, Math::max);
        }
    }

    private RecommendedEventProto result(long eventId, double score) {
        return RecommendedEventProto.newBuilder().setEventId(eventId).setScore(score).build();
    }
}

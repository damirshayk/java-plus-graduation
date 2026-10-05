package ru.practicum.ewm.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.EndpointHitRequestDto;
import ru.practicum.ewm.ViewStats;
import ru.practicum.ewm.exception.ValidationException;
import ru.practicum.ewm.mapper.HitMapper;
import ru.practicum.ewm.repository.HitRepository;
import ru.practicum.ewm.repository.ViewStatsProjection;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private final HitRepository hitRepository;
    private final HitMapper hitMapper;

    /**
     * Сохранение информации о запросе.
     *
     * @param hitDto Данные о запросе.
     */
    @Override
    public void saveHit(EndpointHitRequestDto hitDto) {
        hitRepository.save(hitMapper.toHit(hitDto));
    }

    /**
     * Получение статистики просмотров.
     *
     * @param start  Начало периода.
     * @param end    Конец периода.
     * @param uris   Список URI для фильтрации (может быть null или пустым).
     * @param unique Флаг, указывающий, учитывать ли только уникальные просмотры.
     * @return Список статистики просмотров.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ViewStats> getStats(
            LocalDateTime start,
            LocalDateTime end,
            List<String> uris,
            boolean unique
    ) {
        validatePeriod(start, end);

        List<ViewStatsProjection> stats;
        if (uris == null || uris.isEmpty()) {
            stats = unique
                    ? hitRepository.findUniqueStats(start, end)
                    : hitRepository.findStats(start, end);
        } else {
            stats = unique
                    ? hitRepository.findUniqueStatsByUris(start, end, uris)
                    : hitRepository.findStatsByUris(start, end, uris);
        }

        return stats.stream()
                .map(StatsServiceImpl::toViewStats)
                .toList();
    }

    // Валидация периода времени.
    private static void validatePeriod(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new ValidationException("Начало и конец периода должны быть указаны");
        }
        if (start.isAfter(end)) {
            throw new ValidationException("Начало периода не может быть позже его окончания");
        }
    }

    // Преобразование проекции статистики в объект ViewStats.
    private static ViewStats toViewStats(ViewStatsProjection projection) {
        ViewStats viewStats = new ViewStats();      // Создаем новый объект ViewStats
        viewStats.setApp(projection.getApp());      // Устанавливаем значение поля app из проекции
        viewStats.setUri(projection.getUri());      // Устанавливаем значение поля uri из проекции
        viewStats.setHits(projection.getHits());    // Устанавливаем значение поля hits из проекции
        return viewStats;
    }
}

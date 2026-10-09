package ru.practicum.ewm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.practicum.ewm.model.RequestStatus;
import ru.practicum.ewm.repository.RequestRepository;
import ru.practicum.ewm.service.impl.JpaConfirmedRequestCounter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaConfirmedRequestCounterTest {
    @Mock
    private RequestRepository requestRepository;
    private JpaConfirmedRequestCounter counter;

    @BeforeEach
    void setUp() {
        counter = new JpaConfirmedRequestCounter(requestRepository);
    }

    @Test
    void countShouldIncludeOnlyConfirmedRequests() {
        when(requestRepository.countByEventIdAndStatus(10L, RequestStatus.CONFIRMED)).thenReturn(3L);

        assertEquals(3L, counter.count(10L));

        verify(requestRepository).countByEventIdAndStatus(10L, RequestStatus.CONFIRMED);
        verifyNoMoreInteractions(requestRepository);
    }

    @Test
    void countAllShouldAggregateConfirmedRequestsInOneQuery() {
        List<Long> eventIds = List.of(10L, 20L, 30L);
        when(requestRepository.countByEventIdsAndStatus(eventIds, RequestStatus.CONFIRMED))
                .thenReturn(List.of(new Object[]{10L, 2L}, new Object[]{20L, 4L}));

        assertEquals(Map.of(10L, 2L, 20L, 4L), counter.countAll(eventIds));

        verify(requestRepository).countByEventIdsAndStatus(eventIds, RequestStatus.CONFIRMED);
        verifyNoMoreInteractions(requestRepository);
    }

    @Test
    void countAllShouldReturnEmptyMapWhenNoConfirmedRequestsExist() {
        List<Long> eventIds = List.of(10L);
        when(requestRepository.countByEventIdsAndStatus(eventIds, RequestStatus.CONFIRMED)).thenReturn(List.of());

        assertEquals(Map.of(), counter.countAll(eventIds));

        verify(requestRepository).countByEventIdsAndStatus(eventIds, RequestStatus.CONFIRMED);
        verifyNoMoreInteractions(requestRepository);
    }

    @Test
    void countAllShouldSkipQueryForEmptyEventIds() {
        assertEquals(Map.of(), counter.countAll(List.of()));

        verifyNoInteractions(requestRepository);
    }
}

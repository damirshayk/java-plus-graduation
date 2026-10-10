package ru.practicum.ewm.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.practicum.ewm.stats.client.CollectorClient;
import ru.practicum.ewm.controller.publ.PublicEventController;
import ru.practicum.ewm.exception.ErrorHandler;
import ru.practicum.ewm.service.EventService;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RecommendationApiTest {
    @Mock
    private EventService eventService;
    @Mock
    private CollectorClient statsClient;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PublicEventController(eventService, statsClient))
                .setControllerAdvice(new ErrorHandler()).build();
    }

    @Test
    void catalogDoesNotPublishView() throws Exception {
        mvc.perform(get("/events")).andExpect(status().isOk());
        verifyNoInteractions(statsClient);
    }

    @Test
    void singleEventRequiresUserHeader() throws Exception {
        mvc.perform(get("/events/10")).andExpect(status().isBadRequest());
        verifyNoInteractions(eventService, statsClient);
    }

    @Test
    void recommendationsHaveSeparateRoute() throws Exception {
        mvc.perform(get("/events/recommendations").header("X-EWM-USER-ID", 5))
                .andExpect(status().isOk());
    }

    @Test
    void recommendationsRequireUserHeader() throws Exception {
        mvc.perform(get("/events/recommendations")).andExpect(status().isBadRequest());
        verifyNoInteractions(eventService, statsClient);
    }
}

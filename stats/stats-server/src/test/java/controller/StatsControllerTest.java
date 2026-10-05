package controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.ewm.EndpointHitRequestDto;
import ru.practicum.ewm.ViewStats;
import ru.practicum.ewm.controller.StatsController;
import ru.practicum.ewm.service.StatsService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = StatsController.class)
@ContextConfiguration(classes = StatsController.class)
class StatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StatsService statsService;

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private EndpointHitRequestDto validHitDto;

    @BeforeEach
    void setUp() {
        validHitDto = new EndpointHitRequestDto();
        validHitDto.setApp("ewm-main-service");
        validHitDto.setUri("/events/1");
        validHitDto.setIp("192.168.1.1");
        validHitDto.setTimestamp(LocalDateTime.now());
    }

    @Test
    void createHit_withValidDto_shouldReturnStatus201Created() throws Exception {
        mockMvc.perform(post("/hit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validHitDto)))
                .andExpect(status().isCreated());

        Mockito.verify(statsService, Mockito.times(1)).saveHit(any(EndpointHitRequestDto.class));
    }

    @Test
    void getStats_withValidParameters_shouldReturnListOfViewStatsAndStatus200Ok() throws Exception {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now();
        List<String> uris = List.of("/events/1");
        boolean unique = true;

        ViewStats fakeStats = new ViewStats();
        fakeStats.setApp("ewm-main-service");
        fakeStats.setUri("/events/1");
        fakeStats.setHits(10L);
        List<ViewStats> expectedList = List.of(fakeStats);

        Mockito.when(statsService.getStats(any(LocalDateTime.class), any(LocalDateTime.class), eq(uris), eq(unique)))
                .thenReturn(expectedList);

        mockMvc.perform(get("/stats")
                        .param("start", start.format(formatter))
                        .param("end", end.format(formatter))
                        .param("uris", "/events/1")
                        .param("unique", String.valueOf(unique))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(1))
                .andExpect(jsonPath("$[0].app").value("ewm-main-service"))
                .andExpect(jsonPath("$[0].uri").value("/events/1"))
                .andExpect(jsonPath("$[0].hits").value(10));
    }

    @Test
    void getStats_withoutOptionalParams_shouldUseDefaultsAndReturnStatus200Ok() throws Exception {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now();

        Mockito.when(statsService.getStats(any(LocalDateTime.class), any(LocalDateTime.class), eq(null), eq(false)))
                .thenReturn(List.of());

        mockMvc.perform(get("/stats")
                        .param("start", start.format(formatter))
                        .param("end", end.format(formatter)))
                .andExpect(status().isOk());
    }
}

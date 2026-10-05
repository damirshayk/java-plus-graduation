package ru.practicum.ewm.dto;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;
import org.springframework.boot.test.json.JsonContent;
import ru.practicum.ewm.dto.request.ParticipationRequestDto;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class ParticipationRequestDtoTest {

    @Autowired
    private JacksonTester<ParticipationRequestDto> json;

    @Test
    void testParticipationRequestDtoSerialization() throws Exception {
        LocalDateTime createdTime = LocalDateTime.of(2026, 9, 9, 11, 0, 0, 123000000);

        ParticipationRequestDto dto = ParticipationRequestDto.builder()
                .id(1L)
                .event(10L)
                .requester(5L)
                .status("CONFIRMED")
                .created(createdTime)
                .build();

        JsonContent<ParticipationRequestDto> result = json.write(dto);


        assertThat(result).hasJsonPathNumberValue("$.id", 1);
        assertThat(result).hasJsonPathNumberValue("$.event", 10);
        assertThat(result).hasJsonPathNumberValue("$.requester", 5);
        assertThat(result).hasJsonPathStringValue("$.status", "CONFIRMED");

        assertThat(result).hasJsonPathStringValue("$.created", "2026-09-09T11:00:00.123");
    }

    @Test
    void testParticipationRequestDtoDeserialization() throws Exception {
        String jsonContent = "{\n" +
                "  \"id\": 1,\n" +
                "  \"event\": 10,\n" +
                "  \"requester\": 5,\n" +
                "  \"status\": \"PENDING\",\n" +
                "  \"created\": \"2026-09-09T11:00:00.123\"\n" +
                "}";

        ParticipationRequestDto dto = json.parse(jsonContent).getObject();

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getEvent()).isEqualTo(10L);
        assertThat(dto.getRequester()).isEqualTo(5L);
        assertThat(dto.getStatus()).isEqualTo("PENDING");
        assertThat(dto.getCreated()).isEqualTo(LocalDateTime.of(2026, 9, 9, 11, 0, 0, 123000000));
    }
}


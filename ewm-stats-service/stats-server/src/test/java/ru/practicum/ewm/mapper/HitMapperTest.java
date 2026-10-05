package ru.practicum.ewm.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.practicum.ewm.EndpointHitDto;
import ru.practicum.ewm.EndpointHitRequestDto;
import ru.practicum.ewm.model.Hit;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HitMapperTest {

    @Autowired
    private HitMapper hitMapper;

    @Test
    void shouldMapRequestDtoToEntity() {
        LocalDateTime timestamp = LocalDateTime.now();

        EndpointHitRequestDto requestDto = EndpointHitRequestDto.builder()
                .app("ewm-main-service")
                .uri("/events/1")
                .ip("192.168.0.1")
                .timestamp(timestamp)
                .build();

        Hit hit = hitMapper.toHit(requestDto);

        assertThat(hit).isNotNull();
        assertThat(hit.getId()).isNull();
        assertThat(hit.getApp()).isEqualTo(requestDto.getApp());
        assertThat(hit.getUri()).isEqualTo(requestDto.getUri());
        assertThat(hit.getIp()).isEqualTo(requestDto.getIp());
        assertThat(hit.getTimestamp()).isEqualTo(requestDto.getTimestamp());
    }

    @Test
    void shouldMapEntityToDto() {
        LocalDateTime timestamp = LocalDateTime.now();

        Hit hit = Hit.builder()
                .id(1L)
                .app("ewm-main-service")
                .uri("/events/1")
                .ip("192.168.0.1")
                .timestamp(timestamp)
                .build();

        EndpointHitDto dto = hitMapper.toDto(hit);

        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo(hit.getId());
        assertThat(dto.getApp()).isEqualTo(hit.getApp());
        assertThat(dto.getUri()).isEqualTo(hit.getUri());
        assertThat(dto.getIp()).isEqualTo(hit.getIp());
        assertThat(dto.getTimestamp()).isEqualTo(hit.getTimestamp());
    }
}

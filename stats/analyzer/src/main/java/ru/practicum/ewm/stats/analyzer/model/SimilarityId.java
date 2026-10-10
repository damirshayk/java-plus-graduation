package ru.practicum.ewm.stats.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SimilarityId implements Serializable {
    private Long eventA;
    private Long eventB;
}

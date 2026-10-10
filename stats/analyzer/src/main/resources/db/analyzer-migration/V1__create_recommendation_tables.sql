CREATE TABLE user_interactions (
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    event_id BIGINT NOT NULL CHECK (event_id > 0),
    weight DOUBLE PRECISION NOT NULL CHECK (weight >= 0 AND weight <= 1),
    last_interaction_time TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, event_id)
);

CREATE INDEX idx_interactions_history
    ON user_interactions (user_id, last_interaction_time DESC, event_id);
CREATE INDEX idx_interactions_event ON user_interactions (event_id);

CREATE TABLE event_similarities (
    event_a BIGINT NOT NULL CHECK (event_a > 0),
    event_b BIGINT NOT NULL,
    score DOUBLE PRECISION NOT NULL CHECK (score >= 0 AND score <= 1),
    source_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (event_a, event_b),
    CONSTRAINT ordered_similarity_pair CHECK (event_a < event_b)
);

CREATE INDEX idx_similarities_second_event ON event_similarities (event_b, event_a);

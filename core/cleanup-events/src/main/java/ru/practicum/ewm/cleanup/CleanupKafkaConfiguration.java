package ru.practicum.ewm.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonDelegatingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
@EnableScheduling
public class CleanupKafkaConfiguration {

    @Bean
    public CleanupEventCodec cleanupEventCodec(ObjectMapper mapper) {
        return new CleanupEventCodec(mapper);
    }

    @Bean
    public DefaultKafkaProducerFactory<String, String> cleanupProducerFactory(KafkaProperties properties) {
        Map<String, Object> producer = new HashMap<>(properties.buildProducerProperties(null));
        producer.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producer.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producer.put(ProducerConfig.ACKS_CONFIG, "all");
        producer.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producer.putIfAbsent(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);
        producer.putIfAbsent(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        producer.putIfAbsent(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        return new DefaultKafkaProducerFactory<>(producer);
    }

    @Bean
    public KafkaTemplate<String, String> cleanupKafkaTemplate(ProducerFactory<String, String> cleanupProducerFactory) {
        return new KafkaTemplate<>(cleanupProducerFactory);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            KafkaProperties properties, @Value("${ewm.cleanup.retry-backoff-ms:1000}") long backoffMs,
            @Value("${ewm.cleanup.listener-enabled:true}") boolean listenerEnabled) {
        Map<String, Object> consumer = new HashMap<>(properties.buildConsumerProperties(null));
        consumer.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumer.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer.putIfAbsent(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumer));
        factory.setAutoStartup(listenerEnabled);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        var retry = new DefaultErrorHandler(new FixedBackOff(backoffMs, FixedBackOff.UNLIMITED_ATTEMPTS));
        retry.setClassifications(Map.of(), true);
        var handler = new CommonDelegatingErrorHandler(retry);
        handler.addDelegate(IllegalArgumentException.class, new CommonContainerStoppingErrorHandler());
        handler.setCauseChainTraversing(true);
        factory.setCommonErrorHandler(handler);
        return factory;
    }

    @Bean
    public KafkaAdmin cleanupKafkaAdmin(KafkaProperties properties,
                                       @Value("${spring.kafka.admin.auto-create:true}") boolean autoCreate) {
        Map<String, Object> adminProperties = new HashMap<>(properties.buildAdminProperties(null));
        adminProperties.putIfAbsent(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
        adminProperties.putIfAbsent(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        KafkaAdmin admin = new KafkaAdmin(adminProperties);
        admin.setAutoCreate(autoCreate);
        admin.setFatalIfBrokerNotAvailable(false);
        admin.setModifyTopicConfigs(true);
        admin.setOperationTimeout(5);
        return admin;
    }

    @Bean
    @ConditionalOnProperty(name = "spring.kafka.admin.auto-create", havingValue = "true", matchIfMissing = true)
    public CleanupTopicInitializer cleanupTopicInitializer(KafkaAdmin admin, PlatformTransactionManager manager) {
        return new CleanupTopicInitializer(admin, manager);
    }

    @Bean
    public NewTopic cleanupTopic(@Value("${ewm.cleanup.topic:ewm.user-data-cleanup.v1}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
                .config(TopicConfig.RETENTION_BYTES_CONFIG, "-1")
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE).build();
    }

    @Bean
    @ConditionalOnProperty(name = "ewm.cleanup.publisher-enabled", havingValue = "true")
    public SharedOutboxJdbc cleanupOutbox(JdbcTemplate jdbc, CleanupEventCodec codec, PlatformTransactionManager manager) {
        return new SharedOutboxJdbc(jdbc, codec, manager);
    }

    @Bean
    @ConditionalOnProperty(name = {"ewm.cleanup.publisher-enabled", "ewm.cleanup.relay-enabled"}, havingValue = "true")
    public SharedOutboxRelay cleanupOutboxRelay(SharedOutboxJdbc outbox, KafkaTemplate<String, String> kafka,
                                               PlatformTransactionManager manager,
                                               @Value("${ewm.cleanup.topic:ewm.user-data-cleanup.v1}") String topic,
                                               @Value("${ewm.cleanup.batch-size:50}") int batchSize,
                                               @Value("${ewm.cleanup.send-timeout-ms:12000}") long timeoutMs) {
        return new SharedOutboxRelay(outbox, kafka, manager, topic, batchSize, timeoutMs);
    }
}

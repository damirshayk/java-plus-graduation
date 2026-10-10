package ru.practicum.ewm;

import net.devh.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration;
import net.devh.boot.grpc.client.autoconfigure.GrpcDiscoveryClientAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import ru.practicum.ewm.stats.client.AnalyzerClient;
import ru.practicum.ewm.stats.client.CollectorClient;
import ru.practicum.ewm.stats.client.StatsClientConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GrpcDiscoveryStartupTest {

    @Test
    void registersDiscoveryResolverBeforeCreatingChannelsWithoutAvailableInstances() {
        new ApplicationContextRunner()
                .withBean(DiscoveryClient.class, () -> mock(DiscoveryClient.class))
                .withConfiguration(AutoConfigurations.of(GrpcClientAutoConfiguration.class,
                        GrpcDiscoveryClientAutoConfiguration.class))
                .withUserConfiguration(StatsClientConfiguration.class)
                .withPropertyValues("grpc.client.collector.address=discovery:///collector",
                        "grpc.client.collector.negotiation-type=plaintext",
                        "grpc.client.analyzer.address=discovery:///analyzer",
                        "grpc.client.analyzer.negotiation-type=plaintext")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CollectorClient.class);
                    assertThat(context).hasSingleBean(AnalyzerClient.class);
                });
    }
}

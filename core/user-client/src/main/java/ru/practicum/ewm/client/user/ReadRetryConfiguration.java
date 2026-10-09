package ru.practicum.ewm.client.user;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalancedRetryFactory;
import org.springframework.cloud.loadbalancer.blocking.retry.BlockingLoadBalancedRetryFactory;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ewm.read-retry", name = "enabled", havingValue = "true")
public class ReadRetryConfiguration {
    @Bean
    public LoadBalancedRetryFactory readRetryFactory(LoadBalancerClientFactory clientFactory) {
        return new BlockingLoadBalancedRetryFactory(clientFactory) {
            @Override
            public BackOffPolicy createBackOffPolicy(String serviceId) {
                FixedBackOffPolicy backoff = new FixedBackOffPolicy();
                backoff.setBackOffPeriod(clientFactory.getProperties(serviceId).getRetry().getBackoff()
                        .getMinBackoff().toMillis());
                return backoff;
            }
        };
    }
}

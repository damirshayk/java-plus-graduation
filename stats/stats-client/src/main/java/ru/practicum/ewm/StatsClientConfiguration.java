package ru.practicum.ewm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StatsClientProperties.class)
public class StatsClientConfiguration {
}

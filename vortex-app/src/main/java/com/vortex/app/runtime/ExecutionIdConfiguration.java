package com.vortex.app.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class ExecutionIdConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExecutionIdStore executionIdStore(
            ExecutionIdProperties properties,
            ObjectMapper objectMapper,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        if (properties.getBackend() == ExecutionIdProperties.Backend.REDIS) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate == null) {
                throw new IllegalStateException("Redis execution-id backend requires StringRedisTemplate");
            }
            return new RedisExecutionIdStore(redisTemplate, objectMapper, properties);
        }
        return new InMemoryExecutionIdStore();
    }
}

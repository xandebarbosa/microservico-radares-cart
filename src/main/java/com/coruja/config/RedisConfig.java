package com.coruja.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class RedisConfig {

    @Value("${spring.cache.redis.time-to-live:86400000}") // Padrão: 24 horas (em ms)
    private long timeToLive;
    /**
     * Configura o Serializador JSON com suporte a Page e Datas (Java 8).
     */
    @Bean
    public RedisCacheConfiguration cacheConfiguration() {
        // 1. Configura o ObjectMapper para lidar com Page e Datas
        ObjectMapper objectMapper = new ObjectMapper();

        // Habilita conversão correta de LocalDate/LocalDateTime
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // ✅ CRUCIAL: Habilita tipagem dinâmica.
        // Isso permite que o Redis saiba que o JSON salvo é um PageImpl, e não um LinkedHashMap.
        objectMapper.activateDefaultTyping(
                objectMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        // 2. Cria o serializador usando o Mapper configurado
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        // 3. Retorna a configuração padrão usando esse serializador
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMillis(timeToLive))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, RedisCacheConfiguration cacheConfiguration) {
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfiguration)
                .build();
    }

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(RedisCacheConfiguration cacheConfiguration) {
        return (builder) -> builder
                .withCacheConfiguration("opcoes-filtro-cart-v2",
                        cacheConfiguration.entryTtl(Duration.ofHours(30))) // Filtros duram 30 horas (ajustado de minutos para horas conforme seu comentário anterior parecia indicar longa duração, ou mantenha Duration.ofMinutes(30) se preferir)
                .withCacheConfiguration("kms-rodovia-cart-v2",
                        cacheConfiguration.entryTtl(Duration.ofSeconds(30)));
        // Adicione aqui a configuração para o cache de radares, se quiser um tempo específico
        // .withCacheConfiguration("radars-search", cacheConfiguration.entryTtl(Duration.ofMinutes(10)));
    }
}

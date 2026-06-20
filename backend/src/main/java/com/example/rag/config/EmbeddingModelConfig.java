package com.example.rag.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

@Configuration
public class EmbeddingModelConfig {

    @Bean(name = "embeddingOpenAiApi")
    public OpenAiApi embeddingOpenAiApi(
            @Value("${spring.ai.openai.embedding.api-key:}") String embeddingApiKey,
            @Value("${spring.ai.openai.api-key:}") String fallbackApiKey,
            @Value("${spring.ai.openai.embedding.base-url:}") String embeddingBaseUrl,
            @Value("${spring.ai.openai.base-url:}") String fallbackBaseUrl,
            @Value("${spring.ai.openai.embedding.embeddings-path:/v1/embeddings}") String embeddingsPath) {

        return OpenAiApi.builder()
                .apiKey(required(firstText(embeddingApiKey, fallbackApiKey), "OpenAI embedding api-key"))
                .baseUrl(normalizeBaseUrl(firstText(embeddingBaseUrl, fallbackBaseUrl)))
                .embeddingsPath(required(embeddingsPath, "OpenAI embedding embeddings-path"))
                .build();
    }

    @Bean(name = "openAiEmbeddingOptions")
    public OpenAiEmbeddingOptions openAiEmbeddingOptions(
            @Value("${spring.ai.openai.embedding.options.model}") String model,
            @Value("${spring.ai.openai.embedding.options.dimensions:1536}") Integer dimensions) {

        return OpenAiEmbeddingOptions.builder()
                .model(required(model, "OpenAI embedding model"))
                .dimensions(dimensions)
                .build();
    }

    @Primary
    @Bean
    public EmbeddingModel embeddingModel(
            @Autowired @Qualifier("embeddingOpenAiApi") OpenAiApi openAiApi,
            @Autowired @Qualifier("openAiEmbeddingOptions") OpenAiEmbeddingOptions options) {

        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options);
    }

    private String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String required(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(name + " is not configured");
        }
        return value;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = required(baseUrl, "OpenAI embedding base-url");
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            return normalized.substring(0, normalized.length() - "/v1".length());
        }
        return normalized;
    }
}

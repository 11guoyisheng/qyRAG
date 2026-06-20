package com.example.rag.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ChatModelConfig {

    @Bean(name = "deepSeekChatOpenAiApi")
    public OpenAiApi deepSeekChatOpenAiApi() {
        return OpenAiApi.builder()
                .apiKey("")
                .baseUrl("")
                .build();
    }

    @Bean(name="deepSeekOpenAiChatOptions")
    public OpenAiChatOptions deepSeekOpenAiChatOptions() {
        return OpenAiChatOptions.builder()
                .model("deepseek-v4-flash")
                .temperature(0.2)
                .build();
    }

    @Primary
    @Bean(name = {"chatModel", "deepSeekOpenAiChatModel"})
    public OpenAiChatModel chatModel(@Autowired @Qualifier("deepSeekChatOpenAiApi") OpenAiApi openAiApi,
                                           @Autowired @Qualifier("deepSeekOpenAiChatOptions") OpenAiChatOptions options) {


        return OpenAiChatModel.builder().openAiApi(openAiApi)
                .defaultOptions(options).build();
    }

}

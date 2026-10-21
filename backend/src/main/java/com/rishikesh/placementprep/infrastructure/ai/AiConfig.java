package com.rishikesh.placementprep.infrastructure.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The model client every module shares.
 *
 * <p>spring-ai-starter-model-anthropic already builds an AnthropicChatModel from the
 * spring.ai.anthropic.* settings in application.yml, and offers a ChatClient.Builder
 * wired to it. Building the ChatClient once here, rather than inside each service that
 * needs one, means anything added to it later - a default system prompt, logging of every
 * call - is configured in one place for resume feedback and the agent module alike.
 */
@Configuration
public class AiConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}

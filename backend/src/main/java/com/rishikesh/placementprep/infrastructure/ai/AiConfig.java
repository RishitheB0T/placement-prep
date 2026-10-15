package com.rishikesh.placementprep.infrastructure.ai;

import org.springframework.context.annotation.Configuration;

/**
 * Deliberately empty. spring-ai-starter-model-anthropic auto-configures an
 * AnthropicChatModel from spring.ai.anthropic.api-key already; a ChatClient bean built
 * from it - with whichever default options and tools the agent needs - is a design
 * decision that waits until that is worked through, same as AgentController and
 * AgentService.
 */
@Configuration
public class AiConfig {
}

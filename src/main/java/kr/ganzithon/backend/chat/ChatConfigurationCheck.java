package kr.ganzithon.backend.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** Check local settings at startup without contacting the provider. */
@Component
@ConditionalOnProperty(name = "ganzi.chat.enabled", havingValue = "true")
final class ChatConfigurationCheck {
    private static final Logger log = LoggerFactory.getLogger(ChatConfigurationCheck.class);

    ChatConfigurationCheck(@Nullable ChatProvider provider) {
        String issue = null;
        if (provider == null) issue = "CHAT_PROVIDER_UNAVAILABLE";
        else if (provider instanceof OpenAiChatProvider openAi) issue = openAi.configurationIssue();
        if (issue != null) log.warn("AI chat is enabled but unavailable: {}", issue);
    }
}

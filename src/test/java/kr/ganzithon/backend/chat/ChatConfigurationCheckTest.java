package kr.ganzithon.backend.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.ganzithon.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ChatConfigurationCheckTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(ChatConfigurationCheck.class, OpenAiChatProvider.class)
            .withPropertyValues("ganzi.chat.provider=openai");

    @Test
    void disabledByDefault(CapturedOutput logs) {
        context.run(app -> {
            assertTrue(app.getBeansOfType(ChatConfigurationCheck.class).isEmpty());
            assertTrue(app.getBeansOfType(ChatProvider.class).isEmpty());
        });
        assertFalse(logs.getAll().contains("AI chat is enabled but unavailable"));
    }

    @Test
    void invalidEnabledSettingsKeepApplicationRunningAndOnlyLogReason(CapturedOutput logs) {
        context.withPropertyValues("ganzi.chat.enabled=true", "ganzi.openai.api-key=private-test-key",
                "ganzi.openai.timeout-seconds=private-invalid-setting").run(app -> {
            var provider = app.getBean(ChatProvider.class);
            assertEquals("CHAT_NOT_CONFIGURED", assertThrows(ApiException.class, provider::ensureConfigured).code());
        });
        assertTrue(logs.getAll().contains("OPENAI_TIMEOUT_INVALID"));
        assertFalse(logs.getAll().contains("private-invalid-setting"));
        assertFalse(logs.getAll().contains("private-test-key"));
    }

    @Test
    void missingKeyAndMissingProviderProduceDistinctWarnings(CapturedOutput logs) {
        context.withPropertyValues("ganzi.chat.enabled=true").run(app -> assertNotNull(app.getBean(ChatProvider.class)));
        context.withPropertyValues("ganzi.chat.enabled=true", "ganzi.chat.provider=private-unknown-provider")
                .run(app -> assertTrue(app.getBeansOfType(ChatProvider.class).isEmpty()));
        assertTrue(logs.getAll().contains("OPENAI_API_KEY_MISSING"));
        assertTrue(logs.getAll().contains("CHAT_PROVIDER_UNAVAILABLE"));
        assertFalse(logs.getAll().contains("private-unknown-provider"));
    }

    @Test
    void validLocalSettingsPassWithoutAProviderCall(CapturedOutput logs) {
        context.withPropertyValues("ganzi.chat.enabled=true", "ganzi.openai.api-key=synthetic-key")
                .run(app -> app.getBean(ChatProvider.class).ensureConfigured());
        assertFalse(logs.getAll().contains("AI chat is enabled but unavailable"));
    }
}

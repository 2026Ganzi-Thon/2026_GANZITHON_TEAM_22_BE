package kr.ganzithon.backend.chat;

import java.util.List;

/** One bounded conversation turn; the service owns history and persistence. */
public interface ChatProvider {
    default void ensureConfigured() { }

    Reply generate(String userContent, String previousUserContent,
                   String previousAssistantContent, List<String> characterKeys);

    default Reply generate(String userContent, String previousUserContent,
                           String previousAssistantContent, List<String> characterKeys,
                           String studyState) {
        return generate(userContent, previousUserContent, previousAssistantContent, characterKeys);
    }

    /** Bounded, server-selected historical records; never client-supplied instructions. */
    default Reply generate(String userContent, String previousUserContent,
                           String previousAssistantContent, List<String> characterKeys,
                           String studyState, String memoryContext) {
        return generate(userContent, previousUserContent, previousAssistantContent, characterKeys, studyState);
    }

    /** Optional preflight estimate. Legacy providers may return null. */
    default CostQuote quote(String userContent, String previousUserContent,
                            String previousAssistantContent, List<String> characterKeys) {
        return null;
    }

    default CostQuote quote(String userContent, String previousUserContent,
                           String previousAssistantContent, List<String> characterKeys,
                           String studyState) {
        return quote(userContent, previousUserContent, previousAssistantContent, characterKeys);
    }

    default CostQuote quote(String userContent, String previousUserContent,
                           String previousAssistantContent, List<String> characterKeys,
                           String studyState, String memoryContext) {
        return quote(userContent, previousUserContent, previousAssistantContent, characterKeys, studyState);
    }

    record Utterance(String characterKey, String content) {}
    record Usage(long inputTokens, long cachedInputTokens, long outputTokens) {}
    record CostQuote(String model, long maxInputTokens, int maxOutputTokens) {}
    record Reply(List<Utterance> utterances, Usage usage) {
        public Reply(List<Utterance> utterances) { this(utterances, null); }
    }
}

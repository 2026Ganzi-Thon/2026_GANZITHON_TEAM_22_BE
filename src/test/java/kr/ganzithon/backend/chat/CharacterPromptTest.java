package kr.ganzithon.backend.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CharacterPromptTest {
    @Test
    void preservesCharacterIdentityAndConversationContract() {
        String prompt = CharacterPrompt.systemPrompt();

        assertTrue(prompt.contains("JOY=기쁨이, ANGRY=화남이, SAD=슬픔이"));
        assertTrue(prompt.contains("사용자도 친구 모임의 동등한 네 번째 친구"));
        assertTrue(prompt.contains("요청에 주어진 characterKeys에 들어 있는 친구만 발화"));
        assertTrue(prompt.contains("총 1~4개의 짧은 발화"));
        assertTrue(prompt.contains("발언 순서와 등장 인원은 고정하지"));
        assertTrue(prompt.contains("예시 대사를 그대로 반복하지"));
        assertTrue(prompt.contains("JSON 객체 하나만"));
        assertFalse(prompt.contains("당신은 공부 시작·중단·재시작을 돕는 양 친구"));
    }

    @Test
    void preservesUserControlAndGroundedMemory() {
        String prompt = CharacterPrompt.systemPrompt();

        assertTrue(prompt.contains("모든 대화를 공부나 해결책으로 돌리지"));
        assertTrue(prompt.contains("사용자가 거절하면 재촉하거나 반복 권유하지"));
        assertTrue(prompt.contains("일시정지하면 함께 쉬고, 중단하면 결정을 존중"));
        assertTrue(prompt.contains("할 일·공부 세션의 상태를 자동 변경하거나 알림을 설정했다고"));
        assertTrue(prompt.contains("제공되지 않은 과거 경험을 기억하는 척하지"));
        assertTrue(prompt.contains("공부 내용을 분석·분해하거나 자동 학습 계획"));
        assertTrue(prompt.contains("소크라테스식 문답"));
        assertTrue(prompt.contains("수식의 조작 방법이나"));
        assertTrue(prompt.contains("그때의 감정이나 고생을 추정해 덧붙이지"));
        assertTrue(prompt.contains("동행하는 자기 역할에 자신감"));
        assertTrue(prompt.contains("'자신 있어' 같은 문구를 말버릇처럼 되풀이하지"));
        assertTrue(prompt.contains("'기록', '데이터', '시스템'처럼 내부 처리 과정을 드러내는 말 대신"));
        assertTrue(prompt.contains("들은 적이 없다고 단정하거나 빈자리를 지어낸 기억으로 채우지"));
        assertTrue(prompt.contains("언급되지 않은 장면·행동 순서·원인·"));
        assertTrue(prompt.contains("지어낸 물리적 장면"));
    }

    @Test
    void focusedPromptIncludesOnlySelectedCharacterAndSharedSafetyRules() {
        for (String key : java.util.List.of("JOY", "ANGRY", "SAD")) {
            String prompt = CharacterPrompt.focusedPrompt(key);
            assertTrue(prompt.contains("사용자에게 먼저 반응"));
            assertTrue(prompt.contains("serverStudyState"));
            assertTrue(prompt.contains("memoryContext"));
            assertTrue(prompt.contains("학습 개념 설명"));
            assertTrue(prompt.contains("의학적 진단·치료"));
            assertTrue(prompt.contains("제공되지 않은 과거 경험"));
            assertTrue(prompt.contains("들은 적이 없다고 단정하거나 빈자리를 지어낸 기억으로 채우지"));
            assertTrue(prompt.contains("각 characterKey는 허용된 키 중 하나"));
            assertTrue(prompt.contains("이번 응답의 characterKey는 " + key));
            assertTrue(prompt.contains("{\"characterKey\":\"" + key + "\""));
            if (!key.equals("JOY")) assertFalse(prompt.contains("기쁨이는 자신감"));
            if (!key.equals("ANGRY")) assertFalse(prompt.contains("화남이는 직설적"));
            if (!key.equals("SAD")) assertFalse(prompt.contains("슬픔이는 감수성"));
        }
        assertThrows(IllegalArgumentException.class, () -> CharacterPrompt.focusedPrompt("UNKNOWN"));
    }
}

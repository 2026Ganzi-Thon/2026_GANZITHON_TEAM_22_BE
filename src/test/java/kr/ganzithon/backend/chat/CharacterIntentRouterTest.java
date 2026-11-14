package kr.ganzithon.backend.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CharacterIntentRouterTest {
    private static final List<String> ALL = List.of("ANGRY", "JOY", "SAD");

    @Test
    void keepsSingleCandidate() {
        assertEquals(List.of("SAD"), CharacterIntentRouter.select("기쁨아, 안녕", List.of("SAD")));
    }

    @Test
    void routesOnlyClearDirectAddressToAvailableCharacter() {
        assertEquals(List.of("JOY"), CharacterIntentRouter.select("기쁨아, 같이 있어 줘", ALL));
        assertEquals(List.of("ANGRY"), CharacterIntentRouter.select("화남아! 얘기 좀 해 줘", ALL));
        assertEquals(List.of("SAD"), CharacterIntentRouter.select("@슬픔이, 안녕", ALL));
        assertEquals(List.of("SAD"), CharacterIntentRouter.select("안녕, @슬픔이", ALL));
        assertEquals(List.of("JOY"), CharacterIntentRouter.select("안녕 기쁨아", ALL));
        assertEquals(List.of("SAD"), CharacterIntentRouter.select("야 슬픔아", ALL));
        assertEquals(List.of("ANGRY"), CharacterIntentRouter.select("저기 화남아", ALL));
        assertEquals(List.of("SAD"), CharacterIntentRouter.select("@슬픔이… 안녕", ALL));
        assertEquals(List.of("SAD"), CharacterIntentRouter.select("슬픔아… 나 힘들어", ALL));
        assertEquals(List.of("SAD"), CharacterIntentRouter.select("슬픔아ㅠㅠ", ALL));
        assertEquals(List.of("SAD"), CharacterIntentRouter.select("슬픔아.", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("기쁨아, 화남아, 안녕", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("기쁨아 화남아, 안녕", ALL));
        assertEquals(List.of("ANGRY", "SAD"),
                CharacterIntentRouter.select("기쁨아, 안녕", List.of("ANGRY", "SAD")));
        assertEquals(ALL, CharacterIntentRouter.select("기쁨아, @모르는이", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("기쁨아, 친구가 '화남아'라고 했어", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("친구가 말했어: 슬픔아, 괜찮아", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("안녕, 친구가 화남아라고 했어", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("슬픔아. 화남아, 안녕", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("슬픔아… 다들 어떻게 생각해?", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("기쁨아 말고 슬픔아", ALL));
    }

    @Test
    void doesNotMistakeOrdinaryCharacterMentionsForAddress() {
        assertEquals(ALL, CharacterIntentRouter.select("기쁨이가 뭐라고 했어?", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("나는 화남이보다 슬픔이가 좋아", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("오늘 @기쁨이 이야기를 들었어", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("@모르는이, 안녕", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("화남이야!", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("'슬픔아'라고 말했어", ALL));
    }

    @Test
    void explicitAllRequestKeepsAllCandidates() {
        assertEquals(ALL, CharacterIntentRouter.select("세 명 다 답해 줘!", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("모두 말해 줘", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("셋 다 대답해 줘", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("기쁨아, 다 같이 말해 줘", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("화남아, 다들 어떻게 생각해?", ALL));
        assertEquals(List.of("JOY", "SAD"),
                CharacterIntentRouter.select("화남아, 다들 어떻게 생각해?", List.of("JOY", "SAD")));
    }

    @Test
    void routesOnlyUnambiguousSelfReports() {
        assertEquals(List.of("JOY"), CharacterIntentRouter.select("나 너무 기뻐!", ALL));
        assertEquals(List.of("JOY"), CharacterIntentRouter.select("시험에 합격했어", ALL));
        assertEquals(List.of("SAD"), CharacterIntentRouter.select("오늘 정말 속상해.", ALL));
        assertEquals(List.of("ANGRY"), CharacterIntentRouter.select("나 너무 억울해!", ALL));
        assertEquals(List.of("ANGRY"), CharacterIntentRouter.select("짜증나", ALL));
        assertEquals(List.of("JOY"), CharacterIntentRouter.select("우리 같이 공부 시작하자!", ALL));
    }

    @Test
    void keepsCandidatesForAmbiguousMixedNegatedOrQuotedInput() {
        assertEquals(ALL, CharacterIntentRouter.select("기뻐?", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("속상해?", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("나 너무 기뻐?", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("합격했는데 속상해", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("기쁘지만 억울해", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("속상하지 않아", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("친구가 '짜증나'라고 했어", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("어떻게 해야 할까?", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("오늘 힘들었어", ALL));
        assertEquals(ALL, CharacterIntentRouter.select("", ALL));
        assertEquals(List.of("JOY", "SAD"), CharacterIntentRouter.select("억울해", List.of("JOY", "SAD")));
    }
}

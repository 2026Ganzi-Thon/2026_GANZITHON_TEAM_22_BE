package kr.ganzithon.backend.chat;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import kr.ganzithon.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatSafetySupportTest {
    @Test
    void recognizesDirectAndIndirectKoreanCrisisLanguage() {
        String[] cases = {
            "죽고 싶어", "죽고 싶다는 생각이 들어", "죽고 싶은 생각이 자꾸 나",
            "죽고 싶어서 연락했어", "요즘 계속 죽고 싶었어", "죽고 싶을 때가 많아",
            "죽고 싶은데 어떡하지", "죽고 싶어져", "죽고싶음", "죽고 싶어여",
            "죽을래", "죽어버릴까", "죽었으면 좋겠어", "살고 싶지 않아", "살기 싫어",
            "사라지고 싶어", "자살 생각이 나", "자살할까 봐", "자살하려고",
            "극단적 선택을 하고 싶어", "뛰어내리고 싶어", "손목을 긋고 싶어",
            "나를 해치고 싶어", "시험 망쳐서 죽고 싶다ㅋㅋ"
        };
        for (String content : cases) {
            assertSafetyResponse(content);
        }
    }

    @Test
    void recognizesSpacingPunctuationAndEnglishVariants() {
        String[] cases = {
            "죽 고 싶 어", "죽고  싶어", "죽고\n싶어", "죽고\u200B싶어",
            "죽.고.싶.어", "죽고 싶어!!!", "죽고 싶다...",
            "I want to die", "I wanna die", "I want to end my life",
            "I'm going to kill myself", "Im going to kill myself", "kill myself"
        };
        for (String content : cases) {
            assertSafetyResponse(content);
        }
    }

    @Test
    void avoidsClearNegationResolvedHistoryAndFiction() {
        String[] cases = {
            "죽고 싶지 않아요", "죽고 싶은 건 아니야", "안 죽고 싶어",
            "예전엔 죽고 싶었는데 지금은 괜찮아", "배고파 죽겠다", "힘들어 죽겠어",
            "웃겨 죽겠네", "과제가 많아서 힘들어", "오늘 공부 시작할래",
            "드라마 주인공이 자살하려고 해", "'죽고 싶다'는 말은 쓰면 안 돼?",
            "게임에서 일부러 죽고 싶어"
        };
        for (String content : cases) {
            assertNull(ChatSafetySupport.responseFor(content), content);
        }
    }

    @Test
    void supportsThirdPersonDisclosuresWithoutMissingFirstPersonCrisis() {
        assertSafetyResponse("친구가 죽고 싶다고 했어");
        assertSafetyResponse("죽고 싶어 하는 친구가 있어");
        assertSafetyResponse("죽고 싶다는 친구를 어떻게 도와?");
        assertSafetyResponse("친구가 힘들어. 나도 죽고 싶어");
        assertSafetyResponse("예전엔 죽고 싶었는데 지금은 괜찮아. 그런데 다시 죽고 싶어");
        assertSafetyResponse("'죽고 싶다'는 말밖에 생각 안 나");
    }

    @Test
    void keepsCurrentRiskOutsideNarrowNegationAndPastRecoveryExceptions() {
        assertSafetyResponse("미안 죽고 싶어");
        assertSafetyResponse("오랫동안 죽고 싶다는 생각을 했어");
        assertSafetyResponse("지금 죽고 싶어. 예전엔 힘들었지만 지금은 괜찮아.");
        assertSafetyResponse("죽고 싶어.\n예전엔 힘들어도 버텼고 지금은 괜찮아 보이려고 애쓰는 중이야");
        assertSafetyResponse("예전엔 괜찮았지만 아직도 죽고 싶어 지금은 괜찮아");
        assertSafetyResponse("오래 살고 싶지 않아요");
        assertSafetyResponse("자살 충동이 들어요");
        assertSafetyResponse("I don't want to live anymore");
        assertNull(ChatSafetySupport.responseFor("예전엔 죽고 싶었는데 지금은 괜찮아요."));
        assertNull(ChatSafetySupport.responseFor("정말 안 죽고 싶어"));
        assertNull(ChatSafetySupport.responseFor("죽고 싶다는 생각은 안 해"));
    }

    @Test
    void recognizesIntentReportedSpeechAndRecentSelfInjury() {
        for (String content : new String[] {
            "요즘 살기 싫어요", "정말 이 세상에서 살기 싫어", "죽어야겠어요", "없어지고 싶어요",
            "동생이 또 죽고 싶대요", "지난밤에 자해를 했어요", "I wanna kms",
            "내 자신을 해치고 싶어요", "친구가 목숨 끊으려고 한대"
        }) {
            assertSafetyResponse(content);
        }
    }

    @Test
    void limitsContextExceptionsToTheAssociatedMention() {
        for (String content : new String[] {
            "그 동네에서 살기 싫다", "너는 죽을래?", "영화에서 남자 주인공이 죽고 싶다고 해",
            "‘죽고 싶어’라는 가사가 기억나", "작년엔 죽고 싶었는데 지금은 잘 지내요.",
            "화초가 죽을까 봐 걱정돼"
        }) {
            assertNull(ChatSafetySupport.responseFor(content), content);
        }
        assertSafetyResponse("그 동네에서 살기 싫어. 사실 나도 죽고 싶어");
        assertSafetyResponse("화초가 죽을까 봐 걱정돼. 그런데 나도 죽을까");
        assertSafetyResponse("혼자 살기 싫어. 내일이 두려워");
        assertSafetyResponse("‘죽고 싶어’라는 가사가 지금 내 마음이야");
        assertSafetyResponse("‘죽고 싶어’라는 가사가 요즘 딱 내 마음이야. 나도 죽고 싶어.");
    }

    @Test
    void distinguishesAttributedLinesFromCurrentDanger() {
        assertSafetyResponse("제 인생을 끝내고 싶습니다");
        assertSafetyResponse("오늘 자해할 것 같아요");
        assertSafetyResponse("게임에서 죽고 싶다는 생각이 들었어");
        assertSafetyResponse("친구의 마지막 대사가 ‘죽고 싶어’였어");
        assertNull(ChatSafetySupport.responseFor("각본에 적힌 ‘죽고 싶다’는 몇 쪽이야?"));
        assertNull(ChatSafetySupport.responseFor("죽고 싶은 마음은 전혀 없습니다"));
        assertSafetyResponse("각본에 적힌 ‘죽고 싶다’가 떠올라. 지금 나도 죽고 싶어");
        assertSafetyResponse("죽고 싶은 마음은 없어. 하지만 오늘 자해할 것 같아");
    }

    @Test
    void distinguishesCurrentUrgesAndPreparationFromRoutineMedication() {
        for (String content : new String[] {
            "친구가 자해 충동이 있다고 했어", "지금 자해 충동이 있어요", "자살 충동이 있습니다",
            "스스로를 해칠까 봐 무섭다", "자살하고 싶어요",
            "약을 한꺼번에 먹으려고 모아 뒀어", "약을 한 번에 삼키려고 모았어요"
        }) {
            assertSafetyResponse(content);
        }
        for (String content : new String[] {
            "오늘 먹을 약을 모아 뒀어", "처방받은 약을 한꺼번에 먹어도 되는지 물어봤어",
            "약을 한꺼번에 먹으려고 모아 둔 건 아니야", "지금 자해 충동이 없어요",
            "소설에서 주인공이 약을 한꺼번에 먹으려고 모아 뒀어",
            "영화에서 주인공이 스스로를 해칠까 봐 무섭대"
        }) {
            assertNull(ChatSafetySupport.responseFor(content), content);
        }
    }

    @Test
    void recognizesAffirmedPlansDecisionsAndSelfDirectedHarm() {
        for (String content : new String[] {
            "오늘 목숨을 끊을 생각이야", "친구가 힘들대. 나는 자살할 계획이 있어",
            "그날 죽기로 마음먹었어", "제 몸을 다치게 할 것 같아요",
            "스스로를 해칠 거 같아", "내 몸에 상처를 내고 싶어",
            "자해 충동이 다시 와요", "지금 자해 충동이 느껴져요",
            "예방교육은 끝났고 지금 나는 자살할 생각이야"
        }) {
            assertSafetyResponse(content);
        }
        for (String content : new String[] {
            "자살할 계획은 없어", "죽기로 결심한 건 아니야",
            "예방교육에서 자살 계획이 있는지 어떻게 물어보는지 배웠어",
            "영화에서 주인공이 자살하기로 결심했어",
            "지금 자해 충동이 느껴지지 않아요", "내 몸에 상처를 내고 싶지 않아"
        }) {
            assertNull(ChatSafetySupport.responseFor(content), content);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "제 목숨을 끊을까 봐 혼자 있기가 두려워요",
        "지금 스스로를 해치지 않을 자신이 없어요",
        "조금 전에 죽을 생각으로 약을 삼켰어요",
        "숙제를 하다가 멈췄어. 지금 일부러 내 몸에 상처를 내는 중이야",
        "나는 이제 내 삶을 끝내기로 결심했어",
        "친구가 지금 자신을 해칠 것 같다고 연락했어",
        "소설에서는 주인공이 살아남았지만 나는 지금 내 목숨을 끊으려 해",
        "예방교육을 듣고 왔어. 그런데 지금은 자해 충동을 참을 수가 없어",
        "예방교육에서 그런 말을 들었고 나도 죽고 싶어",
        "죽고 싶지는 않아. 그래도 지금 나를 다치게 할까 봐 겁나",
        "내 생을 끝내려는 마음으로 지금 유서를 적고 있어"
    })
    void routesImmediateIntentRecentActsAndDisclosuresToSupport(String content) {
        assertSafetyResponse(content);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "지금 혼자 있기가 두려워요. 정전이거든요",
        "졸지 않고 끝까지 버틸 자신이 없어요",
        "조금 전에 의사가 처방한 대로 약을 삼켰어요",
        "내 몸에 상처를 내는 중인 건 아니야",
        "나는 자해하지 않기로 결심했어",
        "나는 죽고 싶다는 뜻은 아니야",
        "나는 이제 기말 과제를 끝내기로 결심했어",
        "소설 속 주인공이 자신을 해칠 것 같다는 대목을 분석해 보자",
        "예방교육에서 자해 충동을 참을 수 없다는 학생을 어떻게 도울지 토론했어",
        "오늘은 그냥 다 접고 싶다는 생각이 드네",
        "이 도시가 얼마나 유서 깊은 곳인지 적고 있어"
    })
    void keepsStudyFictionDenialAndUnspecifiedDistressInChat(String content) {
        assertNull(ChatSafetySupport.responseFor(content), content);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "오늘 밤 내 삶을 바꿀 결심을 했어. 사직서는 이미 써 뒀어.",
        "지금 당장 때려치우고 싶은 충동을 참기 힘들어. 팀장이 또 내 공을 가져갔어.",
        "자살 충동에 대해 쓴 내 수기를 다시 읽었어. 십 년 전의 나한테 지금 잘 산다고 말해 주고 싶네.",
        "지금 귀가 중이야. 오늘 자살 유가족 모임에 다녀왔더니 아빠가 보고 싶네."
    })
    void keepsEmploymentDecisionsRetrospectiveWritingAndBereavementInChat(String content) {
        assertNull(ChatSafetySupport.responseFor(content), content);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "내가 곧 내 삶을 끝낼 거라서 친구들한테 작별 문자 보내는 중이야.",
        "오늘 밤 내 삶을 바꿀 결심을 했어. 사직서는 이미 써 뒀어. 그런데 지금은 죽고 싶어.",
        "지금 당장 때려치우고 싶은 충동을 참기 힘들어. 팀장이 또 내 공을 가져갔어. 나도 죽고 싶어.",
        "자살 충동에 대해 쓴 내 수기를 다시 읽었어. 십 년 전의 나한테 지금 잘 산다고 말해 주고 싶네. 하지만 오늘 자살할 생각이 들어.",
        "지금 귀가 중이야. 오늘 자살 유가족 모임에 다녀왔더니 아빠가 보고 싶네. 그런데 나도 죽고 싶어."
    })
    void preservesCurrentRiskAfterOrdinaryOrHistoricalContext(String content) {
        assertSafetyResponse(content);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "오늘 저녁 프로젝트를 미루기로 결정했어요. 마감은 다음 주예요.",
        "회사를 그만두고 싶은 마음을 억누르기 힘들어.",
        "이직하면서 동료들에게 작별 문자를 보냈어요.",
        "유족을 위한 추모 행사에 참석하고 돌아왔어.",
        "자살로 떠난 친구를 기리려고 편지를 낭독했어.",
        "교재에 쓰인 ‘죽고 싶어’라는 문장을 번역했어.",
        "예전엔 죽고 싶었는데 지금은 잘 지내요."
    })
    void keepsScopedOrdinaryPredicatesInChat(String content) {
        assertNull(ChatSafetySupport.responseFor(content), content);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "내가 곧 세상을 떠날 예정이라 작별 편지를 쓰고 있어.",
        "자살 유가족 모임에 참석했어. 지금은 스스로를 해치지 않을 자신이 없어.",
        "회사를 그만두고 싶은 마음을 억누르기 힘들고, 지금 내 목숨을 끊으려 해.",
        "교재에서 읽은 ‘죽고 싶어’라는 구절을 분석했고 제가 제 몸을 해치기 전에 지금 곁에 와 줄 사람이 필요해요.",
        "예전엔 죽고 싶었는데 지금은 잘 지내요. 그런데 어젯밤 정말로 목숨을 끊으려다 그만뒀어. 아직도 그때 마음 그대로야."
    })
    void preservesOtherDisclosuresInTheSameMessage(String content) {
        assertSafetyResponse(content);
    }

    private static void assertSafetyResponse(String content) {
        ApiException error = ChatSafetySupport.responseFor(content);
        assertNotNull(error, content);
        assertEquals(422, error.status(), content);
        assertEquals(ChatSafetySupport.CODE, error.code(), content);
        assertFalse(error.retryable(), content);
    }
}

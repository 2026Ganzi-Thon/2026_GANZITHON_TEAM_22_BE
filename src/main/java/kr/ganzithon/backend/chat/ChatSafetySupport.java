package kr.ganzithon.backend.chat;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ganzithon.backend.common.ApiException;

/** Screens explicit crisis language before sending a message to the model. */
final class ChatSafetySupport {
    static final String CODE = "CHAT_SAFETY_SUPPORT";
    private static final String MESSAGE = "본인이나 주변 사람이 지금 위험하다고 느낀다면 가까운 사람에게 상황을 알리고 "
        + "함께 도움을 요청해 주세요. 한국에서는 자살예방상담전화 109에 연락할 수 있어요.";

    private static final int CONTEXT_LENGTH = 60;
    private static final String END = "(?=$|[^\\p{L}]|[ᅮᅲᄏᄒ])";
    private static final String DESIRE = "[\\s.·]*싶[\\s.·]*(?:어요|어|다|단|습니다|네요|네|다는|다고(?:요)?|은|어서(?:요)?|었어요|었어|었는데|을|은데(?:요)?|어져|어졌어|지만|어도|음|어여|당)" + END;
    private static final String SELF = "(?:(?:내|제|자기)\\s*몸|(?:나|내)\\s*자신|스스로|나)(?:을|를)?\\s*";
    private static final String WOUND = "(?:내|제|자기)\\s*몸에\\s*상처(?:를)?\\s*";
    private static final String POSSIBILITY = "\\s*(?:것|거)\\s*같(?:아요|아|아서|습니다)";
    private static final String PLAN = "(?:생각|계획)(?:이야|이에요|입니다|(?:이|은)?\\s*있(?:어요|어|다|습니다))";
    private static final String DECISION = "기로\\s*(?:결심(?:을)?\\s*했|마음(?:을)?\\s*먹었|했)(?:어요|어|다|습니다)";
    private static final Pattern CRISIS = Pattern.compile(
        "(?:죽\\s*[.·]?\\s*고(?:만)?|죽어\\s*버리고|목숨(?:을)?\\s*끊고|" + SELF + "해치고|" + WOUND + "내고|"
            + "(?:내|나의|제)\\s*(?:삶|인생|생명)(?:을)?\\s*끝내고|"
            + "자해(?:를)?\\s*하고|자살(?:을)?\\s*하고|극단적(?:인)?\\s*선택을\\s*하고|"
            + "뛰어내리고|손목(?:을)?\\s*긋고|(?:사라|없어)지고)" + DESIRE
            + "|(?:죽고\\s*(?:시퍼|싶대)(?:요)?" + END + "|주거\\s*버리고" + DESIRE + ")"
            + "|(?:살고\\s*싶지\\s*않(?:아요|아|다)|살기\\s*싫(?:어요|어|다)|죽을래(?:요)?|죽을까(?:요)?|죽을\\s*거(?:야|예요)|"
            + "죽어야겠(?:어요|어|다)|뛰어내릴까(?:요)?|자해(?:를)?\\s*했(?:어요|어|다)|죽어\\s*버릴까|죽었으면\\s*좋겠(?:어요|어|다)|"
            + "(?:(?:자살|자해)(?:을|를)?\\s*할|" + SELF + "(?:해칠|다치게\\s*할)|" + WOUND + "낼)" + POSSIBILITY + "|"
            + "(?:(?:자살|자해)(?:(?:을|를)?\\s*할)?|죽을|목숨(?:을)?\\s*끊을)\\s*" + PLAN + "|"
            + "(?:(?:자살|자해)(?:을|를)?\\s*하|죽|목숨(?:을)?\\s*끊)" + DECISION + "|"
            + "(?:자살|자해)\\s*충동(?:이|은)?\\s*(?:다시\\s*|자꾸\\s*)?"
            + "(?:있(?:어요|어|다|다고|대(?:요)?|습니다)|들(?:어요|어)|와(?:요)?|온다|옵니다|느껴(?:져(?:요)?|진다|집니다))|"
            + SELF + "(?:해칠까|다치게\\s*할까)\\s*(?:봐|무서워(?:요)?|두려워(?:요)?)|"
            + WOUND + "내려고\\s*(?:해(?:요)?|합니다)|"
            + "약(?:을)?\\s*(?:한꺼번에|한\\s*번에)\\s*(?:먹으|삼키)려고\\s*"
            + "(?:모았|모아\\s*(?:뒀|두었|놨|놓았))(?:어요|어|다)|"
            + "자살(?:을)?\\s*(?:할\\s*거(?:야|예요)|하려고(?:\\s*해(?:요)?)?|할까\\s*봐)|"
            + "자해(?:를)?\\s*(?:할\\s*거(?:야|예요)|하려고(?:\\s*해(?:요)?)?)|자살\\s*생각이\\s*나|"
            + "자살할래|목숨(?:을)?\\s*끊(?:을래(?:요)?|으려고(?:요)?|을\\s*거(?:야|예요)))" + END
            + "|\\b(?:i\\s+(?:want\\s+to|wanna)\\s+die|i\\s+want\\s+to\\s+(?:end\\s+my\\s+life|(?:hurt|kill)\\s+myself)|"
            + "i(?:'|’)?m\\s+going\\s+to\\s+kill\\s+myself|kill\\s+myself|"
            + "i\\s+(?:want\\s+to|wanna)\\s+(?:kms|disappear\\s+forever)|i\\s+feel\\s+like\\s+dying|"
            + "i\\s+don['’]?t\\s+want\\s+to\\s+live\\s+anymore)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PRESENT_SUBJECT = Pattern.compile("(?:(?:나는|저는|내가|제가|나|난|전)\\s*(?:(?:지금|현재)(?:은|는)?\\s*)?|(?:지금|현재)(?:은|는)?\\s*)$");
    private static final Pattern NEGATED = Pattern.compile("(?<![\\p{L}\\p{N}])안\\s*$");
    private static final Pattern DISAVOWED = Pattern.compile(
        "^\\s*(?:(?:건|것은|게|뜻은|말은)\\s*(?:아니|아닌)|생각(?:은|을)\\s*안\\s*해(?:요)?" + END
            + "|(?:마음|생각|의향)(?:은|이|도)\\s*(?:(?:전혀|절대)\\s*)?없(?:어(?:요)?|다|습니다)" + END + ")");
    private static final Pattern PAST_RESOLVED = Pattern.compile(
        "(?<![\\p{L}\\p{N}])(?:예전|과거|전|옛날|작년)(?:엔|에는)"
            + "(?<past>[^.!?。！？\\r\\n]*?)(?:지금은|현재는)\\s*"
            + "(?:괜찮아(?:요|졌어(?:요)?|졌습니다)?|괜찮습니다|잘\\s*지내(?:요)?)[\\t ]*(?=$|[.!?。！？\\r\\n])");
    private static final Pattern CURRENT_CONTEXT = Pattern.compile("(?<![\\p{L}\\p{N}])(?:지금|현재|여전히|아직도|오늘|다시|하지만|그런데|사실은)(?:은|도)?(?=\\s)");
    private static final Pattern FICTION = Pattern.compile("(?:게임에서\\s*(?:일부러\\s*|(?:내\\s*)?캐릭터가\\s*)|(?:드라마|영화|소설|웹툰)(?:의|에서)?\\s*(?:여자|남자)?\\s*주인공이\\s*)$");
    private static final Pattern RESIDENCE = Pattern.compile("(?:기숙사|아파트|원룸|집|동네|도시)에서\\s*$");
    private static final Pattern RELOCATION = Pattern.compile("^\\s*(?:이사|룸메이트|룸메|다른\\s*(?:집|동네))");
    private static final Pattern NONHUMAN_SUBJECT = Pattern.compile("(?:화분|화초|식물|나무|꽃|배터리|캐릭터)(?:이|가|는|은)\\s*$");
    private static final Pattern ADDRESSEE = Pattern.compile("(?:^|\\s)(?:너|너희|당신)(?:는|도)?\\s*$");
    private static final Pattern SONG_REFERENCE = Pattern.compile("^[\"'’”]?\\s*(?:라는|이라는)\\s*(?:노래|가사|곡)(?:가|를|이|을)?\\s*"
        + "(?:알아|기억나|제목|(?:나오는|나온)\\s*(?:곡|노래))");
    private static final Pattern QUOTED_META = Pattern.compile(
        "[\"'‘“][^\"'‘“’”\\r\\n]*[\"'’”]\\s*(?:는|이라는)?\\s*(?:말|표현|대사)(?:은|을)?\\s*"
            + "(?:쓰면|사용하면|표현하면|무슨\\s*뜻|뜻이|의미가)"
            + "|(?:대본|각본|가사|(?:영화|드라마|연극|소설)(?:\\s*속)?(?:\\s*주인공)?\\s*대사)"
            + "(?:에\\s*(?:나오는|적힌|쓰인)|가|는|로)\\s*"
            + "[\"'‘“][^\"'‘“’”\\r\\n]*[\"'’”]");

    // Literary source before the quotation, or an explicit reading/writing predicate after it.
    private static final String QUOTE = "[\"'‘“][^\"'‘“’”\\r\\n]{1,100}[\"'’”]";
    private static final Pattern LITERARY_QUOTE = Pattern.compile(
        "(?:소설|시집|대본|각본|작품|교재)(?:에서|에)\\s*(?:읽은|나오는|적힌|쓰인)\\s*" + QUOTE
            + "|" + QUOTE + "\\s*(?:라는|이라는|란)\\s*(?:대사|구절|문장|시구)(?:을|를|의)?\\s*"
            + "(?:분석|해석|번역|고쳐|수정|읽|인용|낭독)");
    private static final Pattern MODIFIED_WORD = Pattern.compile("^\\s*(?:단어|문구|글자|표현)(?:는|은|가|이|를|을)");
    private static final Pattern EDUCATIONAL_FRAME = Pattern.compile(
        "(?:(?:예방교육|상담교육|사례지|예시문|교육자료|교재|대본|각본)(?:에서?|에는|에\\s*적힌)|게임\\s*캐릭터(?:가|는)|(?:소설|영화|드라마)\\s*(?:속|에서)\\s*(?:화자|주인공|등장인물)(?:가|이|는|은))"
            + "[^.!?。！？,\\r\\n]{0,38}$");
    private static final Pattern PERSONAL_TURN = Pattern.compile(
        "(?:하지만|그런데|반면|끝났고|배웠고|사실은|별개로|(?:했고|들었고|는데|봤고|읽고)\\s*(?:나|저|내|제)|(?:지금|실제로)\\s*(?:나는|저는|내가|제가|나도)|(?:나는|저는|나도)\\s*지금)");
    private static final Pattern DENIED_PREDICATE = Pattern.compile(
        "^\\s*(?:(?:봐\\s*)?(?:두렵|무섭|겁난)(?:다는|단)\\s*(?:건|게|뜻은)\\s*아니|(?:진|지는|지)\\s*않|(?:다는|단|다는\\s*건|던|었던)\\s*(?:건|게|말은|뜻은|것은)?\\s*아니|"
            + "(?:은|는|던|다는|이라는)\\s*(?:건|게|것은|뜻은|말은)\\s*아니)");
    private static final Pattern FIGURATIVE_OR_DENIED = Pattern.compile(
        "(?:웃겨(?:서)?|배고파(?:서)?|귀여워(?:서)?)\\s*죽(?:겠|을\\s*(?:것|거)\\s*같)"
            + "|(?:(?:지금|현재)(?:은|는)?\\s*)?(?:자해|자살)\\s*(?:충동|생각|계획|의향)(?:은|는|이|을|도)?\\s*"
            + "(?:(?:이제|지금|전혀)\\s*)?(?:없(?:어요|어|다|습니다)?|(?:느껴지|들|생기)지\\s*않(?:아요|아|다|습니다)?)");
    // Match a governed predicate and its argument, leaving subsequent predicates available for scoring.
    private static final String TIME = "(?:(?:지금|당장|오늘|내일)(?:\\s*(?:밤|저녁|아침))?\\s*){0,2}";
    private static final String ORDINARY_DECISION = "(?:결정|결심|마음)(?:을)?\\s*(?:먹|먹었|했|해|굳혔)";
    private static final String URGE = "(?:고\\s*싶은|려는)\\s*(?:생각|충동|마음)(?:을|이|은)?\\s*"
        + "(?:(?:참|억누르|견디|누르)기(?:가)?\\s*(?:힘들|어려)|(?:참|억누를|견딜)\\s*수(?:가)?\\s*없)";
    private static final String WORK = "(?:직장|회사|일|직업|과제|수업|일정|계획|약속|프로젝트)";
    private static final String RECORD = "(?:수기|일기|글|기록|편지|원고)";
    private static final String RISK_TOPIC = "(?:자살|자해)(?:\\s*(?:생각|충동|시도))?";
    private static final String MEMORIAL = "(?:유가족|유족|추모|기일|장례)";
    private static final Pattern ORDINARY_PREDICATE = Pattern.compile(
        TIME + "(?:(?:내|제)\\s*)?(?:삶|인생|" + WORK + ")(?:을|를)\\s*"
            + "(?:바꿀|바꾸기로|옮길|옮기기로|미룰|미루기로|그만둘|그만두기로)\\s*" + ORDINARY_DECISION
            + "|" + TIME + "(?:(?:" + WORK + ")(?:을|를)\\s*)?(?:때려치우|퇴사하|" + WORK + "(?:을|를)\\s*그만두)" + URGE
            + "|" + RISK_TOPIC + "(?:에\\s*(?:대해|관해)|을|를)\\s*(?:썼던|쓴|기록한|적었던)\\s*"
            + "(?:(?:내|제|옛)\\s*)?" + RECORD + "(?:를|을)\\s*(?:(?:다시|꺼내)\\s*)?(?:읽|보았|봤|펼쳤)"
            + "|(?:년|달|주)\\s*전(?:의)?\\s*(?:나|저)(?:한테|에게)\\s*(?:지금|이제)(?:은|는)?\\s*"
            + "(?:잘\\s*(?:산다|살고\\s*있다|지낸다)|괜찮아졌다|회복했다)고\\s*(?:말|전)"
            + "|" + TIME + "(?:" + RISK_TOPIC + "(?:로\\s*인한)?\\s*)?" + MEMORIAL
            + "(?:의|을\\s*위한)?\\s*(?:자조\\s*)?(?:모임|행사|미사|식|집회)(?:에|을|를)?\\s*"
            + "(?:다녀|참석|찾|갔|준비|열|가졌)"
            + "|" + RISK_TOPIC + "(?:로|으로)\\s*(?:떠난|잃은|세상을\\s*떠난)\\s*"
            + "(?:가족|친구|동료|고인|아버지|어머니)(?:을|를)\\s*(?:기리|추모|그리워)");
    private ChatSafetySupport() { }

    static ApiException responseFor(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String normalized = Normalizer.normalize(content, Normalizer.Form.NFKC)
            .replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
        Matcher matches = CRISIS.matcher(normalized);
        while (matches.find()) {
            String before = normalized.substring(Math.max(0, matches.start() - CONTEXT_LENGTH), matches.start());
            String after = normalized.substring(matches.end(), Math.min(normalized.length(), matches.end() + CONTEXT_LENGTH));
            String phrase = matches.group();
            if ((phrase.startsWith("살기") && (RESIDENCE.matcher(before).find() || RELOCATION.matcher(after).find()))
                || (phrase.startsWith("죽을") && NONHUMAN_SUBJECT.matcher(before).find())
                || (phrase.startsWith("죽을래") && (ADDRESSEE.matcher(before).find() || after.stripLeading().startsWith("살래")))
                || (phrase.startsWith("사라지고") && MODIFIED_WORD.matcher(after).find())
                || framedExample(before) || SONG_REFERENCE.matcher(after).find()) {
                continue;
            }
            if (NEGATED.matcher(before).find() || DISAVOWED.matcher(after).find() || DENIED_PREDICATE.matcher(after).find()) {
                continue;
            }
            if (isResolvedPast(normalized, matches.start(), matches.end()) || isQuotedMeta(normalized, matches.start(), matches.end())
                || FICTION.matcher(before).find()) {
                continue;
            }
            return new ApiException(422, CODE, MESSAGE, false);
        }
        if (hasComposedSignal(normalized) || needsSupplementarySupport(normalized)) {
            return new ApiException(422, CODE, MESSAGE, false);
        }
        return null;
    }

    private static boolean needsSupplementarySupport(String content) {
        // Explicit rules already examined the original text. Preserve its offsets and
        // remove only ineligible evidence before applying the supplementary model.
        char[] eligible = content.toCharArray();
        mask(eligible, ORDINARY_PREDICATE.matcher(content));
        Matcher direct = CRISIS.matcher(content);
        while (direct.find()) {
            Matcher denial = DISAVOWED.matcher(content).region(direct.end(), content.length());
            int from = direct.start();
            int to = direct.end();
            if (denial.lookingAt()) {
                Matcher subject = PRESENT_SUBJECT.matcher(content).region(0, from);
                if (subject.find()) {
                    from = subject.start();
                }
                to = denial.end();
            }
            Arrays.fill(eligible, from, to, ' ');
        }
        mask(eligible, FIGURATIVE_OR_DENIED.matcher(content));
        mask(eligible, QUOTED_META.matcher(content));
        mask(eligible, LITERARY_QUOTE.matcher(content));
        Matcher history = PAST_RESOLVED.matcher(content);
        while (history.find()) {
            int from = history.start("past");
            int to = history.end("past");
            if (!CURRENT_CONTEXT.matcher(content).region(from, to).find()) {
                Arrays.fill(eligible, history.start(), history.end(), ' ');
            }
        }
        visitComposedSignals(content, (from, to) -> {
            Arrays.fill(eligible, from, to, ' ');
            return false;
        });
        return KoreanCrisisSimilarity.needsSupport(new String(eligible));
    }

    private static void mask(char[] text, Matcher spans) {
        while (spans.find()) {
            Arrays.fill(text, spans.start(), spans.end(), ' ');
        }
    }
    private static boolean framedExample(String before) {
        Matcher frame = EDUCATIONAL_FRAME.matcher(before);
        return frame.find() && !PERSONAL_TURN.matcher(frame.group()).find();
    }

    /* Preserve original offsets for scope checks while accepting colloquial spacing. */
    private static boolean hasComposedSignal(String content) {
        return visitComposedSignals(content, (start, end) -> {
            String before = content.substring(Math.max(0, start - CONTEXT_LENGTH), start);
            String after = content.substring(end, Math.min(content.length(), end + CONTEXT_LENGTH));
            return !NEGATED.matcher(before).find() && !DISAVOWED.matcher(after).find()
                && !DENIED_PREDICATE.matcher(after).find() && !framedExample(before)
                && !FICTION.matcher(before).find() && !isQuotedMeta(content, start, end)
                && !isResolvedPast(content, start, end);
        });
    }

    private static boolean visitComposedSignals(String content, BiPredicate<Integer, Integer> visitor) {
        StringBuilder compact = new StringBuilder(content.length());
        int[] offsets = new int[content.length()];
        for (int i = 0; i < content.length(); i++) {
            char value = content.charAt(i);
            if (!Character.isWhitespace(value) || value == '\n' || value == '\r') {
                offsets[compact.length()] = i;
                compact.append(value);
            }
        }
        return KoreanCrisisSignals.anyMatch(compact.toString(), (from, to) -> {
            int start = offsets[from];
            int end = offsets[to - 1] + 1;
            return visitor.test(start, end);
        });
    }

    private static boolean isResolvedPast(String content, int matchStart, int matchEnd) {
        Matcher resolved = PAST_RESOLVED.matcher(content);
        while (resolved.find()) {
            int pastStart = resolved.start("past");
            if (pastStart <= matchStart && matchEnd <= resolved.end("past")
                && !CURRENT_CONTEXT.matcher(content).region(pastStart, matchStart).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isQuotedMeta(String content, int matchStart, int matchEnd) {
        return containedIn(QUOTED_META, content, matchStart, matchEnd)
            || containedIn(LITERARY_QUOTE, content, matchStart, matchEnd);
    }

    private static boolean containedIn(Pattern frame, String content, int matchStart, int matchEnd) {
        Matcher quoted = frame.matcher(content);
        while (quoted.find()) {
            if (quoted.start() <= matchStart && quoted.end() >= matchEnd) {
                return true;
            }
        }
        return false;
    }
}

package kr.ganzithon.backend.chat;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative routing hint. Uncertain input keeps the caller's full candidate set. */
final class CharacterIntentRouter {
    private static final Map<String, String> NAMES = Map.of(
            "기쁨이", "JOY", "화남이", "ANGRY", "슬픔이", "SAD");
    private static final Pattern ADDRESS = Pattern.compile(
            "^(?:(?:안녕(?:하세요)?|야|하이|저기)(?:[,!]\\s*|\\s+))?(?:@(?<tag>기쁨이|화남이|슬픔이)|"
            + "(?<vocative>기쁨아|화남아|슬픔아))(?=$|[\\s,;:!?~.…ㅠㅜ])");
    private static final Pattern ANY_VOCATIVE = Pattern.compile(
            "(?:기쁨아|화남아|슬픔아)(?=$|[\\s,;:!?~.…ㅠㅜ'\"])");
    private static final Pattern ANY_TAG = Pattern.compile("@[^\\s,;:!?~.…ㅠㅜ'\"]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ALL = Pattern.compile(
            "^(?:셋\\s*다|세\\s*명\\s*다|모두|다\\s*같이)\\s*"
            + "(?:대답해\\s*줘|답해\\s*줘|말해\\s*줘|얘기해\\s*줘)[.!?~]*$");
    private static final Pattern GROUP_ADDRESS = Pattern.compile(
            "^(?:다들|모두|셋\\s*다|세\\s*명\\s*다)(?=$|[\\s,;:!?~])");
    private static final Pattern STUDY_START = Pattern.compile(
            "^(?:우리\\s+)?같이\\s+공부\\s+시작하자[.!~]*$");
    private static final Pattern JOY = Pattern.compile(
            "^(?:(?:나(?:는)?|내가|오늘)\\s+)?(?:(?:너무|진짜|정말)\\s+)?"
            + "(?:기뻐|신나|행복해|해냈어|(?:시험에\\s+)?합격했어)[.!~]*$");
    private static final Pattern SAD = Pattern.compile(
            "^(?:(?:나(?:는)?|내가|오늘)\\s+)?(?:(?:너무|진짜|정말)\\s+)?"
            + "(?:속상해|슬퍼|우울해|눈물\\s*나)[.!~]*$");
    private static final Pattern ANGRY = Pattern.compile(
            "^(?:(?:나(?:는)?|내가|오늘)\\s+)?(?:(?:너무|진짜|정말)\\s+)?"
            + "(?:억울해|짜증나|화가\\s*나|분해)[.!~]*$");

    private CharacterIntentRouter() { }

    static List<String> select(String content, List<String> candidates) {
        if (candidates == null || candidates.size() <= 1 || content == null || content.isBlank()) {
            return candidates;
        }
        String input = content.strip();
        if (ALL.matcher(input).matches()) return candidates;
        Matcher matcher = ADDRESS.matcher(input);
        if (matcher.find()) {
            String name = matcher.group("tag");
            if (name == null) {
                String vocative = matcher.group("vocative");
                name = vocative.substring(0, vocative.length() - 1) + "이";
            }
            String key = NAMES.get(name);
            if (key == null || !candidates.contains(key)) return candidates;
            String remainder = input.substring(matcher.end()).replaceFirst("^[,;:!?~.…ㅠㅜ\\s]+", "");
            if (ALL.matcher(remainder).matches() || GROUP_ADDRESS.matcher(remainder).find()) {
                return candidates;
            }
            Matcher vocatives = ANY_VOCATIVE.matcher(input);
            while (vocatives.find()) {
                String named = vocatives.group().substring(0, vocatives.group().length() - 1) + "이";
                if (!key.equals(NAMES.get(named))) return candidates;
            }
            Matcher tags = ANY_TAG.matcher(input);
            while (tags.find()) {
                if (!key.equals(NAMES.get(tags.group().substring(1)))) return candidates;
            }
            return List.of(key);
        }
        if (candidates.contains("JOY") && (JOY.matcher(input).matches() || STUDY_START.matcher(input).matches())) {
            return List.of("JOY");
        }
        if (SAD.matcher(input).matches() && candidates.contains("SAD")) return List.of("SAD");
        if (ANGRY.matcher(input).matches() && candidates.contains("ANGRY")) return List.of("ANGRY");
        return candidates;
    }
}

package kr.ganzithon.backend.chat;

import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local Korean predicates. Each alternative joins a harm target to an action or a present safety concern. */
final class KoreanCrisisSignals {
    private static final String SELF = "(?:나|저|자기)(?:를|자신을)|자신(?:을|에게)|(?:내|제|자기)몸(?:을|에)|스스로(?:에게|를)?";
    private static final String VOLUNTARY = "(?:스스로|직접|내손으로|제손으로)?";
    private static final String INJURY = "(?:(?:" + SELF + ")(?:해치|다치게하|상처(?:를)?(?:내|입히))|자해(?:를)?하)";
    private static final String LIFE = "(?:(?:내|제|자기|나의)?(?:목숨|생명|인생|삶|생)(?:을)?(?:끊|끝내|마치)|죽|자살(?:을)?하)";
    private static final String ACTION = "(?:" + INJURY + "|" + LIFE + ")";
    private static final String FUTURE = "(?:(?:" + SELF + ")(?:해칠|다치게할|상처(?:를)?낼)|(?:목숨|생명)(?:을)?끊을|"
        + "(?:삶|생|인생)(?:을)?(?:마칠|끝낼)|(?:자해|자살)(?:를|을)?할)";
    private static final String SELF_LIFE_INTENT = "(?:내|제|나의)(?:삶|생|인생|목숨|생명)(?:을)?"
        + "(?:끝낼|마칠|끊을)(?:거라서|거니까|(?:거야|거예요|겁니다|것입니다|거다)(?![가-힣]))";
    private static final String EMPHASIS = "(?:정말|진짜|너무|도저히|더는|이젠|자꾸|계속|다시|점점|당장|이제){0,4}";
    private static final String LOSS = "(?:(?:못(?:참|견디|버티|막|멈추)|(?:참|막|버틸|견딜|멈출|통제할|억누를|이겨낼)"
        + "(?:을|아낼)?(?:자신|수|힘)(?:이|가|도)?없(?:어|다|습|네|는데))|(?:참기|버티기|통제하기)(?:가)?(?:어려|힘들))";
    private static final String ACTIVE = "(?:고있(?:어|다|습니다|는데)|버렸(?:어|다)|고말았(?:어|다))";
    private static final String HELP = "(?:도와|붙잡아|말려|곁에있어|옆에있어|같이있어|와)(?:줘|주세요|줄래)";
    private static final String SAFETY_FEAR = "(?:내가|제가|스스로)?(?:나|저|자신)(?:를|을)?"
        + "(?:믿을수가?없|못믿겠|통제할수가?없|감당할수가?없)";
    private static final Pattern COMPOSED = Pattern.compile(
        "살아있고싶지(?:는|도)?않(?:아|다|습)|그만살고싶(?:어서|으니|어)(?:자꾸|계속)?마지막인사|"
            + SELF_LIFE_INTENT + "|" + FUTURE + "(?:(?:것|거)(?:만)?같(?:아|다|습|은)|까(?:봐|겁|두렵|무섭)|"
            + "(?:생각|충동|마음)(?:이|은|가)?" + EMPHASIS + "(?:들어|든다|들었|들고|나(?:요|서)|났어))"
            + "|" + ACTION + "(?:(?:으)?려고|(?:으)?려는)(?:(?:마음|결심)(?:을)?(?:먹었|했)|(?:생각|마음|의도)(?:으로)?유서|"
            + "(?:방금|이미|아까)?약(?:을)?(?:먹었|삼켰)|(?:하고있|해요|한다|합니다))"
            + "|(?:" + SELF + ")(?:해쳤|다치게했|상처(?:를)?(?:냈|입혔))(?:어|다|습|는데)"
            + "|약(?:을)?(?:처방(?:받은)?(?:양|용량)보다(?:훨씬|너무)?많이|과다(?:하게|복용))"
            + "(?:먹었|삼켰|했)(?:어|다|습|는데)"
            + "|" + INJURY + ACTIVE
            + "|죽을(?:생각|충동)(?:이|은)?" + EMPHASIS + "(?:들어|든다|들었|들고)"
            + "|" + INJURY + "지않을(?:자신|확신)(?:이|도)?없(?:어|다|습)"
            + "|" + ACTION + "지않고(?:는)?(?:안전하게)?" + EMPHASIS + LOSS
            + "|(?:자해|자살)(?:하고싶은)?(?:충동|생각|마음)(?:을|이|은|이란|도)?" + EMPHASIS + LOSS
            + "|(?:안전(?:하게|히)(?:있을|지낼|버틸)|(?:내|제)안전을지킬)(?:자신|수)(?:이|가)?없"
            + "|(?:혼자(?:서)?(?:있으면|있다간|있다가는|있기가|있는게))(?:내가|제가|나는|난)?"
            + EMPHASIS + "(?:위험할것같|위험할거같|위험해질것같|무슨짓(?:을)?할지모르)"
            + "|(?:혼자(?:서)?(?:있으면|두면|있다가는))" + EMPHASIS + SAFETY_FEAR
            + "|" + SAFETY_FEAR + "(?:어서|어|으니|는데|으니까)?(?:지금|오늘밤)?(?:나좀|제발|좀)?" + HELP
            + "|(?:(?:내가|제가)(?:나|저)(?:에게|한테)|나자신에게|스스로에게)(?:무슨|어떤)짓(?:을)?할지모르"
    );

    // A pair must occur in the same discourse unit. Distance is measured in compact Korean characters.
    private static final int MAX_GAP = 24;
    private static final Pattern CLAUSE = Pattern.compile(
        "[.!?。！？;\\r\\n]+|하지만|그런데|그렇지만|그래도|반면에|지만|(?:끝났|배웠|읽었|봤)고");
    private static final Pattern HARM = Pattern.compile(
        "(?:" + SELF + ")(?:일부러|직접)?(?:해치|해칠|다치게하|다치게할|상처(?:를)?(?:내|낼))"
            + "|(?:자해|자살)(?:를|을)?(?:하|할|충동|생각)|(?:목숨|생명)(?:을)?" + VOLUNTARY + "끊"
            + "|(?:내|제|나의)(?:생|삶|인생)(?:을)?" + VOLUNTARY + "(?:끝내|마치)");
    private static final Pattern LOSS_OR_FEAR = Pattern.compile(
        LOSS + "|(?:않을|지킬|버틸)(?:자신|수)(?:이|가|도)?없|(?:통제|감당|조절)(?:가|이)?안(?:돼|되)"
            + "|(?:참|막|멈출|억누를|버틸|떨쳐낼|떨칠)수가?없|(?:할까|칠까|낼까|을까)봐(?:너무)?(?:겁|두렵|무섭)"
            + "|(?:충동|생각)(?:이|은)?(?:자꾸|계속|반복해서|다시)(?:들|밀려|떠올|나)"
            + "|(?:생각|충동)(?:이|은)?(?:머리에서)?(?:떠나지않|멈추지않|가시지않)");
    private static final Pattern OWN_DEATH = Pattern.compile(
        "(?:내가|제가)(?:곧|오늘|오늘밤|이제)?(?:죽을|세상을떠날)(?:거라|거니|예정이라|생각이라)"
            + "|(?:내|제)(?:삶|생명|목숨|생)(?:을)?(?:끝낼|끊을|마칠)(?:생각이라|예정이라|참이라)");
    private static final Pattern FAREWELL_ACTION = Pattern.compile(
        "(?:작별|마지막)(?:의)?(?:인사|문자|편지|메시지)(?:를|을)?(?:남기|남겼|적고|썼|쓰고|보내|보냈|전하고|전했)");
    private static final Pattern PURPOSE = Pattern.compile(
        "(?:죽|자살하)(?:을생각으로|으려고|려고|으려는)|(?:목숨을끊|생을마치|생을끝내)(?:으려|려|을생각)");
    private static final Pattern ACTED = Pattern.compile(
        "약(?:을)?(?:이미|방금|한꺼번에)?(?:먹었|삼켰)|유서(?:를)?(?:쓰고있|적고있|썼|적었)");
    private static final Pattern AFFIRMED = Pattern.compile(
        "(?:기로|겠다고)(?:결정(?:을)?했|결심(?:을)?했|마음(?:을)?먹었)|(?:으려|려)(?:해(?:요)?|합니다)(?![가-힣])"
            + "|(?:내|하)는중(?:이야|이에요|입니다)|(?:을|칠|할|낼)(?:것|거)같(?:아|습|다고)");
    private static final Pattern SURVIVAL = Pattern.compile(
        "(?:살아갈|살아있을|살아남을|계속살)(?:의지|이유|마음|자신)(?:가|이|도)?없"
            + "|(?:다시는|영원히|영영)(?:눈(?:을)?뜨|깨어나|일어나)지않(?:고싶|았으면)");
    private static final Pattern HELP_OR_NOW = Pattern.compile(
        HELP + "|혼자(?:있으면|있기가|두면)|지금(?:당장)?|오늘밤|더는|더이상");
    private static final Pattern SELF_SAFETY = Pattern.compile(
        "(?:나자신|저자신|내자신|제자신|내몸|제몸|스스로)(?:을|를)?(?:안전하게)?(?:지킬|지키|보호할)"
            + "|(?:내|제|나의)안전(?:을|이|은)?|(?:내가|제가|나는|저는)(?:계속)?안전할");
    private static final Pattern UNSURE = Pattern.compile(
        "(?:자신|확신|보장|장담)(?:이|도|할수가?)?없|(?:장담|확신)할수가?없|(?:장담|확신)(?:을)?못"
            + "|(?:지킬|버틸|보호할)수가?없|(?:할지|할수있을지)(?:잘)?모르");
    private static final Pattern DENIAL = Pattern.compile(
        "지(?:는|도)?않기로|(?:하고싶|느껴지|해치|할것같|끊고싶|려고하)지(?:는|도)?않|"
            + "(?:충동|계획|마음|생각)(?:은|이|도)?(?:전혀|이제)?없|(?:건|게|뜻은|말은)아니");

    static boolean anyMatch(String compact, BiPredicate<Integer, Integer> applicable) {
        Matcher cuts = CLAUSE.matcher(compact);
        int start = 0;
        while (cuts.find()) {
            if (inClause(compact, start, cuts.start(), applicable)) {
                return true;
            }
            start = cuts.end();
        }
        return inClause(compact, start, compact.length(), applicable);
    }

    private static boolean inClause(String text, int start, int end, BiPredicate<Integer, Integer> applicable) {
        Matcher direct = COMPOSED.matcher(text).region(start, end);
        while (direct.find()) {
            if (applicable.test(direct.start(), direct.end())) {
                return true;
            }
        }
        return paired(text, start, end, HARM, LOSS_OR_FEAR, applicable)
            || paired(text, start, end, HARM, AFFIRMED, applicable)
            || paired(text, start, end, PURPOSE, ACTED, applicable)
            || paired(text, start, end, OWN_DEATH, FAREWELL_ACTION, applicable)
            || paired(text, start, end, SURVIVAL, HELP_OR_NOW, applicable)
            || paired(text, start, end, SELF_SAFETY, UNSURE, applicable);
    }

    private static boolean paired(String text, int start, int end, Pattern topic, Pattern state,
                                  BiPredicate<Integer, Integer> applicable) {
        Matcher topics = topic.matcher(text).region(start, end);
        while (topics.find()) {
            Matcher states = state.matcher(text).region(
                Math.max(start, topics.start() - MAX_GAP), Math.min(end, topics.end() + MAX_GAP));
            while (states.find()) {
                int from = Math.min(topics.start(), states.start());
                int to = Math.max(topics.end(), states.end());
                if (!DENIAL.matcher(text).region(from, to).find() && applicable.test(from, to)) {
                    return true;
                }
            }
        }
        return false;
    }

    private KoreanCrisisSignals() { }
}

package com.prism.gateway.cache;

import com.prism.gateway.core.ChatRequest;
import com.prism.gateway.tenant.Tenant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What may be cached, and when two similar prompts are allowed to share an answer.
 * <p>
 * Embedding similarity alone is not a safe equality test. Measured with all-MiniLM-L6-v2:
 * <ul>
 *   <li>the same question with a different id prefix, "(3fa9c2b1) What is a load balancer?" vs
 *       "(7d0e4a19) ...": 0.94</li>
 *   <li>the same roster question with one name changed (Diego vs Lukas): 0.98</li>
 * </ul>
 * Both are above any sensible threshold, yet their answers differ. The <b>entity guard</b> therefore
 * requires that numbers, identifiers and proper nouns in one prompt also appear in the other.
 * <p>
 * Embeddings also barely register negation and opposites: "Why should I not use Kafka?" scores 0.986
 * against "Why should I use Kafka?", "disadvantages" 0.93 against "advantages", "turn off dark mode"
 * 0.95 against "turn on dark mode". The <b>polarity guard</b> rejects a match when only one prompt is
 * negated, or when the prompts sit on opposite sides of a group of opposites (enable, turn on ... vs
 * disable, turn off ...).
 * <p>
 * Nor do they register word order: "convert Celsius to Fahrenheit" scores 0.995 against "Fahrenheit
 * to Celsius". The <b>order guard</b> rejects a match whose shared entities, or whose words around
 * "to", "into" or "than", appear in the opposite order.
 * <p>
 * All guards only ever turn a hit into a miss, never the other way round: at worst they cost a saving.
 */
public final class CachePolicy {

    /** Questions about "now" go stale; answering them from cache would be wrong. */
    static final Pattern TIME_SENSITIVE = Pattern.compile(
            "\\b(current(ly)?|right now|today|tonight|tomorrow|yesterday|this (morning|afternoon|evening|week|month|quarter|year)"
                    + "|latest|real[- ]time|live|at the moment|as of now|breaking|recent(ly)?|status of|up to date|weather"
                    + "|stock price|exchange rate)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A refusal from a small model must not become the cached answer for everyone asking the same thing. */
    static final Pattern REFUSAL = Pattern.compile(
            "^\\s*(i'?m sorry|sorry,|i (can ?not|can'?t|am unable to|won'?t) (help|assist|do that|provide|answer))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._:/+=#@'-]*");
    private static final Set<String> NOT_ENTITIES = new HashSet<>(Arrays.asList(
            "i", "i'm", "i've", "i'd", "i'll", "ok", "okay", "hi", "hello", "hey", "please", "thanks", "thank"));

    /** Words that negate the question. Contractions ending in n't are recognised separately. */
    private static final Set<String> NEGATIONS = Set.of("not", "no", "never", "without", "cannot", "nor", "neither",
            "dont", "doesnt", "didnt", "cant", "isnt", "arent", "wasnt", "werent", "shouldnt", "wouldnt", "couldnt");

    /**
     * Groups of opposite meaning, "side / other side". Each side lists words or two-word phrases that
     * mean the same thing, in their base form ("disabled" and "turning" are reduced to it, see base()).
     * Synonyms share a side, so "enable dark mode" vs "turn off dark mode" is a conflict too.
     */
    private static final List<List<Set<String>>> OPPOSITES = Stream.of(
                    "enable, activate, turn on, switch on, power on / disable, deactivate, turn off, switch off, power off",
                    "start / stop",
                    "add, include, insert / remove, exclude",
                    "create / delete",
                    "allow, permit, grant, accept, approve / deny, block, forbid, revoke, reject",
                    "install / uninstall",
                    "subscribe, opt in / unsubscribe, opt out",
                    "connect / disconnect",
                    "lock / unlock",
                    "encrypt / decrypt",
                    "compress / decompress",
                    "mount / unmount",
                    "show / hide",
                    "open / close",
                    "import / export",
                    "upload / download",
                    "increase, raise, grow, expand, scale up / decrease, reduce, lower, shrink, scale down",
                    "speed up, accelerate / slow down, decelerate",
                    "upgrade / downgrade",
                    "buy / sell",
                    "login, log in, sign in / logout, log out, sign out",
                    "zoom in / zoom out",
                    "advantage, pro, benefit / disadvantage, con, drawback",
                    "minimum, min / maximum, max",
                    "before / after",
                    "best / worst",
                    "inbound / outbound",
                    "public / private",
                    "true / false",
                    "sync, synchronous / async, asynchronous")
            .map(group -> Arrays.stream(group.split("/"))
                    .map(side -> Arrays.stream(side.split(",")).map(String::strip).collect(Collectors.toUnmodifiableSet()))
                    .toList())
            .toList();
    /** Every word and phrase in the groups. */
    private static final Set<String> TERMS = OPPOSITES.stream().flatMap(List::stream).flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());
    /** Words a term can start with: the single-word terms and the verbs of phrases ("turn" of "turn on"). */
    private static final Set<String> HEADS = TERMS.stream().map(t -> t.split(" ")[0])
            .collect(Collectors.toUnmodifiableSet());
    private static final Map<String, String> IRREGULAR = Map.of("bought", "buy", "sold", "sell", "shown", "show");

    /** Words skipped when reading what sits either side of "to", "into" and "than" ("to my laptop", "way faster"). */
    private static final Set<String> FILLER = Set.of("a", "an", "the", "my", "our", "your", "their", "its", "his", "her",
            "this", "that", "these", "those", "much", "far", "way", "lot", "bit", "significantly", "slightly", "even");

    private CachePolicy() {
    }

    /** Why this request must not use the cache, or null if it may. */
    public static String bypassReason(Tenant tenant, ChatRequest request, String cacheControl) {
        if (!tenant.cacheEnabled()) {
            return "cache disabled for this key";
        }
        if (cacheControl != null) {
            String cc = cacheControl.toLowerCase(Locale.ROOT);
            if (cc.contains("no-cache") || cc.contains("no-store")) {
                return "client sent Cache-Control: " + cacheControl;
            }
        }
        if (request.body().path("n").asInt(1) > 1) {
            return "n > 1";
        }
        if (request.body().has("tools") || request.body().has("functions")) {
            return "tool calls are not cached";
        }
        Matcher m = TIME_SENSITIVE.matcher(request.lastUserText());
        if (m.find()) {
            return "time-sensitive prompt ('" + m.group() + "')";
        }
        return null;
    }

    /** Only complete, successful, non-refusal answers are stored. */
    public static boolean cacheableResponse(String finishReason, String content) {
        return "stop".equals(finishReason) && content != null && !content.isBlank() && !REFUSAL.matcher(content).find();
    }

    /**
     * Entities of a prompt: tokens containing a digit (numbers, ids, versions, dates) and capitalised
     * words that do not start a sentence (names, products, places). Lower-cased and sorted.
     */
    public static Set<String> entities(String text) {
        return new TreeSet<>(orderedEntities(text));
    }

    /** The same entities, in the order they first appear. */
    static List<String> orderedEntities(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = WORD.matcher(text);
        int previousEnd = -1;
        while (m.find()) {
            String token = trimPunctuation(m.group());
            boolean sentenceStart = previousEnd < 0 || endsSentence(text, previousEnd);
            previousEnd = m.end();
            if (token.isEmpty()) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (token.chars().anyMatch(Character::isDigit)) {
                out.add(lower);
            } else if (Character.isUpperCase(token.charAt(0)) && !sentenceStart && !NOT_ENTITIES.contains(lower)) {
                out.add(lower);
            }
        }
        return List.copyOf(out);
    }

    /** Every entity of each prompt must occur, as a word, in the other one. */
    public static boolean entitiesCompatible(String a, String b) {
        Set<String> wordsA = words(a);
        Set<String> wordsB = words(b);
        return wordsB.containsAll(entities(a)) && wordsA.containsAll(entities(b));
    }

    /**
     * Why two prompts that embed close together still need different answers, or null if nothing
     * flips between them: only one is negated, or they sit on different sides of a group of
     * opposites. A group that only one prompt touches is no conflict ("enable 2FA" vs "set up 2FA"
     * may share an answer), and neither is one both prompts touch the same way ("pros and cons" in
     * both, or "enable" vs "turn on").
     */
    public static String polarityConflict(String a, String b) {
        Set<String> termsA = polarWords(a);
        Set<String> termsB = polarWords(b);
        boolean negatedA = isNegated(a);
        if (negatedA != isNegated(b)) {
            return "negation: only the " + (negatedA ? "new" : "cached") + " prompt is negated";
        }
        for (List<Set<String>> group : OPPOSITES) {
            Set<Integer> sidesA = sides(group, termsA);
            Set<Integer> sidesB = sides(group, termsB);
            if (!sidesA.isEmpty() && !sidesB.isEmpty() && !sidesA.equals(sidesB)) {
                return "opposites: " + named(group, termsA) + " vs " + named(group, termsB);
            }
        }
        return null;
    }

    private static Set<Integer> sides(List<Set<String>> group, Set<String> terms) {
        Set<Integer> out = new TreeSet<>();
        for (int side = 0; side < group.size(); side++) {
            if (!Collections.disjoint(group.get(side), terms)) {
                out.add(side);
            }
        }
        return out;
    }

    private static String named(List<Set<String>> group, Set<String> terms) {
        return terms.stream().filter(t -> group.stream().anyMatch(side -> side.contains(t))).sorted()
                .collect(Collectors.joining("+"));
    }

    /**
     * Why two prompts made of the same words still ask different things, or null: they name the same
     * entities in a different order ("Is Redis faster than Memcached?" vs "Is Memcached faster than
     * Redis?", 0.994), or a word pair around "to", "into" or "than" is reversed ("convert celsius to
     * fahrenheit" vs "fahrenheit to celsius", 0.995). A symmetric question asked the other way round
     * ("difference between Redis and Memcached") becomes a miss too: that costs a saving, not an answer.
     */
    public static String orderConflict(String a, String b) {
        List<String> entitiesA = orderedEntities(a);
        List<String> entitiesB = orderedEntities(b);
        List<String> sharedA = entitiesA.stream().filter(entitiesB::contains).toList();
        List<String> sharedB = entitiesB.stream().filter(entitiesA::contains).toList();
        if (sharedA.size() >= 2 && !sharedA.equals(sharedB)) {
            return "order: " + String.join(", ", sharedA) + " vs " + String.join(", ", sharedB);
        }
        Set<String> pairsB = directionalPairs(b);
        for (String pair : directionalPairs(a)) {
            String[] w = pair.split(" ");
            String reversed = w[2] + " " + w[1] + " " + w[0];
            if (pairsB.contains(reversed) && !pairsB.contains(pair)) {
                return "order: '" + pair + "' vs '" + reversed + "'";
            }
        }
        return null;
    }

    /**
     * "x to y" and "x into y" for the words either side, and "x than y" for the subject before the
     * comparative ("redis [faster] than memcached"). Filler words are skipped first, so "from s3 to my
     * laptop" reads as "s3 to laptop".
     */
    private static Set<String> directionalPairs(String text) {
        List<String> words = wordList(text).stream().filter(w -> !FILLER.contains(w)).toList();
        Set<String> out = new HashSet<>();
        for (int i = 1; i + 1 < words.size(); i++) {
            String w = words.get(i);
            if (w.equals("to") || w.equals("into")) {
                out.add(words.get(i - 1) + " " + w + " " + words.get(i + 1));
            } else if (w.equals("than") && i >= 2) {
                out.add(words.get(i - 2) + " " + w + " " + words.get(i + 1));
            }
        }
        return out;
    }

    private static boolean isNegated(String text) {
        for (String w : words(normaliseApostrophes(text))) {
            if (NEGATIONS.contains(w) || w.endsWith("n't")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The opposite-group terms a prompt uses, in base form: "Disabling" becomes "disable", "turned off"
     * becomes "turn off". A two-word phrase wins over its first word.
     */
    static Set<String> polarWords(String text) {
        List<String> words = wordList(normaliseApostrophes(text));
        Set<String> out = new HashSet<>();
        for (int i = 0; i < words.size(); i++) {
            String head = base(words.get(i));
            String phrase = i + 1 < words.size() ? head + " " + words.get(i + 1) : null;
            if (phrase != null && TERMS.contains(phrase)) {
                out.add(phrase);
                i++;
            } else if (TERMS.contains(head)) {
                out.add(head);
            }
        }
        return out;
    }

    /** The word's base form if it is (an inflection of) a term's first word; otherwise the word itself. */
    private static String base(String word) {
        if (HEADS.contains(word)) {
            return word;
        }
        if (IRREGULAR.containsKey(word)) {
            return IRREGULAR.get(word);
        }
        List<String> candidates = new ArrayList<>();
        for (String suffix : List.of("ing", "ed", "es", "s")) {
            if (word.endsWith(suffix) && word.length() > suffix.length() + 2) {
                String stem = word.substring(0, word.length() - suffix.length());
                candidates.add(stem);                                            // added -> add
                candidates.add(stem + "e");                                      // enabled -> enable
                if (stem.length() > 2 && stem.charAt(stem.length() - 1) == stem.charAt(stem.length() - 2)) {
                    candidates.add(stem.substring(0, stem.length() - 1));        // stopped -> stop
                }
            }
        }
        return candidates.stream().filter(HEADS::contains).findFirst().orElse(word);
    }

    private static String normaliseApostrophes(String text) {
        return text.replace('’', '\'');
    }

    static Set<String> words(String text) {
        return new HashSet<>(wordList(text));
    }

    /** The prompt's words, lower-cased, in order. */
    static List<String> wordList(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String token = trimPunctuation(m.group());
            if (!token.isEmpty()) {
                out.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** Is the text between the previous token and the next one a sentence break (". ", "? ", ") ")? */
    private static boolean endsSentence(String text, int previousEnd) {
        for (int i = previousEnd; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return false;
            }
            if (c == '.' || c == '?' || c == '!' || c == ':' || c == ';' || c == ')' || c == ']' || c == '\n') {
                return true;
            }
        }
        return false;
    }

    private static String trimPunctuation(String token) {
        int end = token.length();
        while (end > 0 && ".:/+=#@'-_".indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(0, end);
    }
}

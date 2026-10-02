package com.prism.gateway.router;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Separates what the user is asking from the material they pasted in.
 * <p>
 * Prompt length is a poor proxy for difficulty: "Here is our 14-day on-call roster ... Who is on call
 * Thursday?" is long but trivial, while "Prove that sqrt(2) is irrational" is short but hard. So the
 * router classifies the instruction, not the payload: quoted blocks and code fences are replaced by a
 * placeholder, and for long prompts only the framing sentence and the final ask are kept.
 */
public final class InstructionExtractor {

    static final int LONG_PROMPT_WORDS = 40;

    private static final Pattern QUOTED = Pattern.compile(
            "```[\\s\\S]*?```|\"[^\"]{24,}\"|'[^']{24,}'|“[^”]{24,}”|‘[^’]{24,}’");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.?!])\\s+(?=[A-Z\"'(\\[])");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public record Extracted(String instruction, int totalWords, int contextWords) {
    }

    private InstructionExtractor() {
    }

    public static Extracted extract(String prompt) {
        String text = WHITESPACE.matcher(prompt == null ? "" : prompt.strip()).replaceAll(" ");
        int total = words(text);
        String unquoted = QUOTED.matcher(text).replaceAll("[quoted]");
        if (words(unquoted) <= LONG_PROMPT_WORDS) {
            return new Extracted(unquoted, total, total - words(unquoted));
        }
        List<String> sentences = Arrays.asList(SENTENCE_BOUNDARY.split(unquoted));
        String framing = sentences.getFirst();
        int colon = framing.indexOf(':');
        if (colon > 0) {
            framing = framing.substring(0, colon); // "Here is our roster: Mon - Priya, ..." -> "Here is our roster"
        }
        framing = firstWords(framing, 20);
        if (sentences.size() == 1) {
            return new Extracted(framing, total, total - words(framing));
        }
        String ask = sentences.getLast();
        if (words(ask) < 3 && sentences.size() > 2) {
            ask = sentences.get(sentences.size() - 2) + " " + ask; // e.g. "... Summarize it. Thanks!"
        }
        String instruction = framing + " … " + firstWords(ask, 60);
        return new Extracted(instruction, total, Math.max(0, total - words(instruction)));
    }

    static int words(String s) {
        String t = s.strip();
        return t.isEmpty() ? 0 : WHITESPACE.split(t).length;
    }

    private static String firstWords(String s, int n) {
        String[] parts = WHITESPACE.split(s.strip());
        return parts.length <= n ? s.strip() : String.join(" ", Arrays.copyOf(parts, n));
    }
}

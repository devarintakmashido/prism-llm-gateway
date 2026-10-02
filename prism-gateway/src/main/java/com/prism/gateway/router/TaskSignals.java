package com.prism.gateway.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small task taxonomy, in the spirit of OpenRouter's Auto Router (which classifies prompts into
 * about 30 task types rather than calling an LLM per request). Each task type carries a weight:
 * positive when the task usually needs a strong model (proofs, design, multi-step reasoning,
 * debugging), negative when a small model handles it well (lookups, extraction, translation,
 * rewriting). Each type counts once; "multi-part" asks count per extra deliverable, up to two.
 * <p>
 * Signals are matched against the extracted instruction, never the pasted context.
 */
public final class TaskSignals {

    public record Signal(String task, double weight, Pattern pattern, int maxCount) {
        Signal(String task, double weight, String regex) {
            this(task, weight, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), 1);
        }
    }

    public record Match(String task, double weight) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s %+.1f", task, weight);
        }
    }

    public record Result(double score, List<Match> matches) {
    }

    static final List<Signal> SIGNALS = List.of(
            // --- tasks that need a strong model ---
            new Signal("proof", 3.0, "\\b(prove|disprove|proof|derive|derivation|show that|demonstrate that)\\b"),
            new Signal("estimation", 2.0, "\\b(estimate|estimation|fermi|back[- ]of[- ](the[- ])?envelope|order of magnitude)\\b"),
            new Signal("show-reasoning", 1.5, "\\b(show (your|the) (reasoning|work|steps)|step[- ]by[- ]step|reason (about|through)|think through|walk me through)\\b"),
            new Signal("design", 2.0, "\\b(design|architect|architecture|schema|data model|system design)\\b"),
            new Signal("trade-offs", 1.5, "\\b(trade-?offs?|pros and cons|justify|justification|recommend|recommendation|which (one )?should (i|we))\\b"),
            new Signal("comparison", 1.0, "\\b(compare|comparison|contrast|versus|differences? between)\\b"),
            new Signal("planning", 1.5, "\\b(plan|migration|migrate|roll ?back|rollout|zero[- ]downtime|cutover|strategy|roadmap|phased)\\b"),
            new Signal("causal-why", 2.0, "\\b(why (can|could|does|do|did|would|is|are|might|should)|explain why|what causes|root cause|mechanisms?|how come)\\b"),
            new Signal("debugging", 2.0, "\\b(debugging|debug (this|the|my|a|an|why)|how (would|do|should|can) (you|i|we) debug|(find|fix) the bug|infinite loop|loops? forever|diagnos\\w*|troubleshoot\\w*|most likely causes?|how (would|do|should) (you|i|we) (confirm|verify|investigate)|regression|race condition|deadlock|memory leak|bottleneck|latency (doubled|spiked|increased|regress\\w*)|intermittent\\w*|flaky)\\b"),
            new Signal("algorithms", 2.0, "(\\b(algorithm|complexity|dynamic programming|big[- ]o|asymptotic|np-hard)\\b|\\bo\\([^)]*n[^)]*\\))"),
            new Signal("distributed-systems", 1.5, "\\b(consistency|consensus|quorum|partitions?|replication|linearizab\\w*|serializab\\w*|eventual(ly)? consistent|cap theorem|leader election|distributed)\\b"),
            new Signal("write-code", 1.0, "\\b(write|implement|build|code) (a |an |the )?(function|class|program|script|algorithm|service|parser|module|library)\\b"),
            new Signal("code-review", 1.5, "\\b(code review|review (this|my|the) (code|pr|pull request|diff)|refactor|security (review|audit)|vulnerabilit\\w*|threat model)\\b"),
            new Signal("analysis", 1.0, "\\b(analy[sz]e|evaluate|critique|assess|implications|in depth|in detail|thoroughly)\\b"),
            new Signal("multi-part", 1.0, Pattern.compile(
                    "\\b(and (explain|give|recommend|justify|describe|include|discuss)|including how|with (justification|examples?|trade-?offs)|list .{0,40}? and how)\\b",
                    Pattern.CASE_INSENSITIVE), 2),

            // --- tasks a small model handles well ---
            new Signal("translation", -3.0, "\\btranslate\\b|\\bin (spanish|french|german|hindi|japanese|chinese|italian|portuguese)\\b"),
            new Signal("unit-conversion", -2.5, "\\bconvert\\b|\\b(fahrenheit|celsius|kilometers?|miles|kilograms?|pounds|inches|centimeters)\\b"),
            new Signal("lookup", -2.0, "^(who|when|where|which) (is|was|are|were)\\b|\\bwho is\\b|\\bwhat is the (capital|name|date|time|timestamp|population|price|value|address|phone|email|owner)\\b|\\bwhich day\\b"),
            // "find the <a superlative, a count or an order statistic>" asks to compute something
            // ("find the 100 most frequent IPs in a 2 TB log"), not to read a value out of the text.
            new Signal("extraction", -2.0, "\\b(extract|pull out|find the (?!bug|root cause|issue|problem|race|leak|flaw|cause"
                    + "|\\d|most|top|best|optimal|minimum|maximum|median|shortest|longest|fastest|largest|smallest|k(th)?\\b|nth)"
                    + "|list (all|every)"
                    + "|what is the timestamp|first (error|line|occurrence)|return only"
                    + "|which (file|line|function|method|column|row|field|value|key)s?)\\b"),
            new Signal("definition", -1.5, "\\bwhat (does|do) .{1,40}? (mean|stand for)\\b|^what does [^?]{1,40} do\\?$|\\bdefine\\b|\\bdefinition of\\b|\\bmeaning of\\b"),
            new Signal("rewrite", -2.5, "\\b(rewrite|rephrase|reword|paraphrase|proofread|fix (the )?(grammar|spelling|typos?)|make (it|this) (more )?(polite|formal|casual|concise|friendly))\\b"),
            new Signal("short-write", -1.5, "\\b(one[- ]line|one sentence|single sentence|a haiku|a tweet|commit message|subject line|a title for|a slogan|tl;?dr)\\b"),
            new Signal("simple-math", -2.0, "\\bis \\d+ (a )?(divisible|prime|even|odd|multiple)\\b|\\bwhat is \\d+\\s*[-+*/x×]\\s*\\d+|\\bhow many days\\b"),
            new Signal("simple-edit", -2.0, "\\b(rename|update line|change line|add (a )?comment|remove (the )?unused|sort (the|these|this)|capitali[sz]e|uppercase|lowercase)\\b"),
            new Signal("simple-classification", -1.5, "\\b(is this (email|message|review|text) (spam|positive|negative)|sentiment of|categori[sz]e (this|these))\\b"),
            new Signal("short-definition", -1.0, "^what is an? [\\w\\s-]{1,30}\\?$")
    );

    private TaskSignals() {
    }

    public static Result evaluate(String instruction) {
        String text = instruction.strip();
        List<Match> matches = new ArrayList<>();
        double score = 0;
        for (Signal s : SIGNALS) {
            Matcher m = s.pattern().matcher(text);
            int count = 0;
            while (count < s.maxCount() && m.find()) {
                count++;
            }
            if (count > 0) {
                double w = s.weight() * count;
                matches.add(new Match(s.task(), w));
                score += w;
            }
        }
        return new Result(score, matches);
    }
}

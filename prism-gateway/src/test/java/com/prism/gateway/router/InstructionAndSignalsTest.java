package com.prism.gateway.router;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InstructionAndSignalsTest {

    private static final String ROSTER = "Here is our on-call roster for the next two weeks: Monday - Priya, Tuesday - Chen, "
            + "Wednesday - Amara, Thursday - Diego, Friday - Fatima, Saturday - Lukas, Sunday - Mei, next Monday - Tom, "
            + "next Tuesday - Sara, next Wednesday - Ravi, next Thursday - Ana, next Friday - Kofi, next Saturday - Elif, "
            + "next Sunday - Jonas. Who is on call this Thursday?";

    @Test
    void longPromptsAreReducedToFramingAndAsk() {
        InstructionExtractor.Extracted e = InstructionExtractor.extract(ROSTER);
        assertThat(e.instruction()).isEqualTo("Here is our on-call roster for the next two weeks … Who is on call this Thursday?");
        assertThat(e.contextWords()).isGreaterThan(30);
    }

    @Test
    void quotedMaterialIsNotPartOfTheInstruction() {
        InstructionExtractor.Extracted e = InstructionExtractor.extract("Rewrite the following email to sound more polite "
                + "without changing its meaning: 'Send me the Q3 numbers now. I asked for them twice already.'");
        assertThat(e.instruction()).doesNotContain("Q3").contains("[quoted]");
    }

    @Test
    void shortHardPromptsScoreComplex() {
        assertThat(TaskSignals.evaluate("Prove that the square root of 2 is irrational.").score()).isPositive();
        assertThat(TaskSignals.evaluate("Why can adding a cache increase p99 latency? Give three plausible mechanisms.")
                .score()).isPositive();
    }

    @Test
    void longTrivialPromptsScoreSimpleOnceContextIsStripped() {
        assertThat(TaskSignals.evaluate(InstructionExtractor.extract(ROSTER).instruction()).score()).isNegative();
        assertThat(TaskSignals.evaluate("Translate 'good morning' to Spanish.").score()).isNegative();
    }

    @Test
    void wordSenseMatters() {
        // "debug" as a log level is not a debugging task; "find the bug" is not an extraction task.
        assertThat(TaskSignals.evaluate("Change the log level on line 42 of server.js from debug to info.").matches())
                .noneMatch(m -> m.task().equals("debugging"));
        assertThat(TaskSignals.evaluate("Find the bug: a binary search loops forever. Explain why and fix it.").matches())
                .noneMatch(m -> m.task().equals("extraction"))
                .anyMatch(m -> m.task().equals("debugging"));
        // "find the <superlative or count>" asks to compute something; "find the <value>" reads it from the text.
        assertThat(TaskSignals.evaluate("Given a 2 TB log file and 8 GB of RAM, how would you find the 100 most "
                + "frequent IP addresses?").matches()).noneMatch(m -> m.task().equals("extraction"));
        assertThat(TaskSignals.evaluate("Find the shortest path that visits every city once.").matches())
                .noneMatch(m -> m.task().equals("extraction"));
        assertThat(TaskSignals.evaluate("Find the email address in this text: 'write to jo@example.com'.").matches())
                .anyMatch(m -> m.task().equals("extraction"));
    }
}

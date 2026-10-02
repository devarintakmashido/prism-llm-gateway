package com.prism.gateway.cache;

import com.prism.gateway.TestFixtures;
import com.prism.gateway.core.ChatRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CachePolicyTest {

    @Test
    void entitiesAreNumbersIdsAndNamesButNotSentenceStarts() {
        assertThat(CachePolicy.entities("(3fa9c2b1) How do I reset my password on the dashboard?"))
                .containsExactly("3fa9c2b1");
        // "Monday" starts a sentence here, so it is not treated as a name; "Thursday" mid-sentence is.
        assertThat(CachePolicy.entities("Who is on call Thursday? Monday - Priya, Thursday - Diego."))
                .containsExactly("diego", "priya", "thursday");
        assertThat(CachePolicy.entities("What is 2+3?")).containsExactly("2+3");
        assertThat(CachePolicy.entities("If I bought an annual plan, can I get my money back?")).isEmpty();
    }

    @Test
    void sameQuestionWithDifferentIdsMustNotShareAnAnswer() {
        // Measured cosine 0.94 with all-MiniLM-L6-v2: the embedding alone would call this a hit.
        assertThat(CachePolicy.entitiesCompatible("(3fa9c2b1) What is a load balancer?",
                "(7d0e4a19) What is a load balancer?")).isFalse();
    }

    @Test
    void rosterWithOneNameChangedMustNotShareAnAnswer() {
        // Measured cosine 0.978.
        assertThat(CachePolicy.entitiesCompatible(
                "Roster: Monday - Priya, Thursday - Diego. Who is on call Thursday?",
                "Roster: Monday - Priya, Thursday - Lukas. Who is on call Thursday?")).isFalse();
    }

    @Test
    void differentNumbersMustNotShareAnAnswer() {
        assertThat(CachePolicy.entitiesCompatible("What is 2+3?", "What is 2+4?")).isFalse();
    }

    @Test
    void paraphrasesWithTheSameEntitiesAreCompatible() {
        assertThat(CachePolicy.entitiesCompatible(
                "(3fa9c2b1) How do I reset my password on the dashboard?",
                "(3fa9c2b1) What are the steps to reset my dashboard password?")).isTrue();
        assertThat(CachePolicy.entitiesCompatible("How do I deploy to AWS?", "What are the steps to deploy on aws?"))
                .isTrue();
    }

    @Test
    void negatedQuestionsMustNotShareAnAnswer() {
        // Measured cosine 0.986: far above any threshold, yet the right answers are opposite.
        assertThat(CachePolicy.polarityConflict("Why should I use Kafka for event streaming?",
                "Why should I not use Kafka for event streaming?")).startsWith("negation");
        assertThat(CachePolicy.polarityConflict("Can I deploy on Fridays?", "Why can’t I deploy on Fridays?"))
                .startsWith("negation"); // curly apostrophe, as pasted from a document
        // Negated the same way in both: a paraphrase, not an opposite.
        assertThat(CachePolicy.polarityConflict("Why should I not use Kafka?", "Why shouldn't I use Kafka?")).isNull();
    }

    @Test
    void oppositeQuestionsMustNotShareAnAnswer() {
        assertThat(CachePolicy.polarityConflict("How do I enable two-factor authentication on my account?",
                "How do I disable two-factor authentication on my account?")).isEqualTo("opposites: enable vs disable");
        assertThat(CachePolicy.polarityConflict("What are the advantages of microservices?",
                "What are the disadvantages of microservices?")).isNotNull();
        assertThat(CachePolicy.polarityConflict("How do I add a user to a group?", "Removing a user from a group?"))
                .isNotNull();
        assertThat(CachePolicy.polarityConflict("Why was my job stopped?", "Why was my job started?")).isNotNull();
        assertThat(CachePolicy.polarityConflict("What are the pros and cons of Redis?", "What are the pros of Redis?"))
                .isNotNull(); // asking for both sides is a different question from asking for one
    }

    @Test
    void oppositesIncludePhrasesInflectionsAndSynonyms() {
        // Both measured above the strictest seeded threshold (0.952 and 0.962) before these terms existed.
        assertThat(CachePolicy.polarityConflict("How do I turn on dark mode in the dashboard?",
                "How do I turn off dark mode in the dashboard?")).isEqualTo("opposites: turn on vs turn off");
        assertThat(CachePolicy.polarityConflict("How do I increase the request timeout?",
                "How do I reduce the request timeout?")).isEqualTo("opposites: increase vs reduce");
        // Synonyms share a side, so a different word for the opposite is still the opposite.
        assertThat(CachePolicy.polarityConflict("How do I enable dark mode?", "How do I turn off dark mode?")).isNotNull();
        assertThat(CachePolicy.polarityConflict("I logged in but see nothing", "I log out but see nothing")).isNotNull();
        assertThat(CachePolicy.polarityConflict("How do I speed up my CI pipeline?", "How do I slow down my CI pipeline?"))
                .isEqualTo("opposites: speed up vs slow down");
        // ... and the same side in other words is no conflict.
        assertThat(CachePolicy.polarityConflict("How do I enable dark mode?", "How can I switch on dark mode?")).isNull();
        // A word that only starts a phrase is not a term on its own.
        assertThat(CachePolicy.polarWords("Where is the log file for the turn signal?")).isEmpty();
    }

    @Test
    void swappedOrderMustNotShareAnAnswer() {
        // Measured: 0.995, 0.989 and 0.994. Embeddings barely register word order.
        assertThat(CachePolicy.orderConflict("How do I convert Celsius to Fahrenheit?",
                "How do I convert Fahrenheit to Celsius?")).startsWith("order:");
        assertThat(CachePolicy.orderConflict("How do I migrate from MySQL to Postgres?",
                "How do I migrate from Postgres to MySQL?")).isEqualTo("order: mysql, postgres vs postgres, mysql");
        assertThat(CachePolicy.orderConflict("Is Redis faster than Memcached for sessions?",
                "Is Memcached faster than Redis for sessions?")).isNotNull();
        // Lower-case words have no entities; the word pair around "to" catches the reversal.
        assertThat(CachePolicy.orderConflict("convert kilograms to pounds", "convert pounds to kilograms"))
                .isEqualTo("order: 'kilograms to pounds' vs 'pounds to kilograms'");
        // Filler words are skipped ("to my laptop"), and "than" compares the subjects, not the adjective.
        assertThat(CachePolicy.orderConflict("copy files from s3 to my laptop", "copy files from my laptop to s3"))
                .isNotNull();
        assertThat(CachePolicy.orderConflict("is redis way faster than memcached", "is memcached faster than redis"))
                .isNotNull();
    }

    @Test
    void orderGuardOnlyRejectsReversals() {
        assertThat(CachePolicy.orderConflict("How do I reset my password on the dashboard?",
                "What are the steps to reset my dashboard password?")).isNull();
        assertThat(CachePolicy.orderConflict("How do I deploy Django on AWS?",
                "What are the steps to deploy a Django app on AWS?")).isNull();
        assertThat(CachePolicy.orderConflict("Convert 5 miles to kilometers", "How many kilometers is 5 miles?"))
                .isNull();
        // The accepted cost: a symmetric question asked the other way round also misses. That loses a
        // saving; serving the reversed answer to a reversed question would be wrong.
        assertThat(CachePolicy.orderConflict("What is the difference between Redis and Memcached?",
                "What is the difference between Memcached and Redis?")).isNotNull();
    }

    @Test
    void polarityGuardOnlyRejectsRealOpposites() {
        // The provided paraphrase pairs carry no polarity, so the guard cannot cost them a hit.
        assertThat(CachePolicy.polarityConflict("How do I reset my password on the dashboard?",
                "What are the steps to reset my dashboard password?")).isNull();
        assertThat(CachePolicy.polarityConflict("What is the refund policy for annual plans?",
                "If I bought an annual plan, can I get my money back?")).isNull();
        // A pair word in only one prompt is no conflict; the same sides in both is no conflict.
        assertThat(CachePolicy.polarityConflict("How do I enable 2FA?", "How do I turn on 2FA?")).isNull();
        assertThat(CachePolicy.polarityConflict("Pros and cons of Redis?", "What are the cons and pros of Redis?"))
                .isNull();
        // Words that merely start like a pair word are not that word.
        assertThat(CachePolicy.polarWords("What is the address of the shop?")).isEmpty();
        assertThat(CachePolicy.polarWords("Disabling, enabled, stopped, closes"))
                .containsExactlyInAnyOrder("disable", "enable", "stop", "close");
    }

    @Test
    void timeSensitivePromptsBypassTheCache() {
        ChatRequest now = TestFixtures.userMessage("fast", "What is the current status of the payments service?");
        assertThat(CachePolicy.bypassReason(TestFixtures.tenant(1, true, 0.9), now, null))
                .startsWith("time-sensitive");
        ChatRequest timeless = TestFixtures.userMessage("fast", "What does HTTP status code 404 mean?");
        assertThat(CachePolicy.bypassReason(TestFixtures.tenant(1, true, 0.9), timeless, null)).isNull();
    }

    @Test
    void keySettingsAndClientHeadersAreRespected() {
        ChatRequest q = TestFixtures.userMessage("fast", "What is a message queue?");
        assertThat(CachePolicy.bypassReason(TestFixtures.tenant(1, false, 0.9), q, null)).contains("disabled");
        assertThat(CachePolicy.bypassReason(TestFixtures.tenant(1, true, 0.9), q, "no-cache")).contains("Cache-Control");
        ChatRequest many = TestFixtures.chat("""
                {"model":"fast","n":3,"messages":[{"role":"user","content":"Name a colour"}]}""");
        assertThat(CachePolicy.bypassReason(TestFixtures.tenant(1, true, 0.9), many, null)).isEqualTo("n > 1");
    }

    @Test
    void onlyCompleteNonRefusalAnswersAreCached() {
        assertThat(CachePolicy.cacheableResponse("stop", "A message queue decouples producers from consumers.")).isTrue();
        assertThat(CachePolicy.cacheableResponse("length", "A message queue decou")).isFalse();
        assertThat(CachePolicy.cacheableResponse("stop", "I'm sorry, but I can't help with that.")).isFalse();
        assertThat(CachePolicy.cacheableResponse("stop", "  ")).isFalse();
    }

    @Test
    void scopeSeparatesTiersContextsAndParameters() {
        ChatRequest plain = TestFixtures.userMessage("fast", "What is connection pooling?");
        ChatRequest withSystem = TestFixtures.chat("""
                {"model":"fast","messages":[{"role":"system","content":"Answer in French."},
                 {"role":"user","content":"What is connection pooling?"}]}""");
        ChatRequest shortAnswer = TestFixtures.chat("""
                {"model":"fast","max_tokens":5,"messages":[{"role":"user","content":"What is connection pooling?"}]}""");

        PromptKey a = PromptKey.of("fast", plain, TestFixtures.MAPPER, 150);
        assertThat(PromptKey.of("smart", plain, TestFixtures.MAPPER, 150).scopeKey()).isNotEqualTo(a.scopeKey());
        assertThat(PromptKey.of("fast", withSystem, TestFixtures.MAPPER, 150).scopeKey()).isNotEqualTo(a.scopeKey());
        assertThat(PromptKey.of("fast", shortAnswer, TestFixtures.MAPPER, 150).scopeKey()).isNotEqualTo(a.scopeKey());
        // Same question, same scope: the matched text is only the last user turn.
        assertThat(PromptKey.of("fast", TestFixtures.userMessage("fast", "  what is CONNECTION pooling? "),
                TestFixtures.MAPPER, 150).promptHash()).isEqualTo(a.promptHash());
    }

    @Test
    void longPromptsAreExactMatchOnly() {
        String longPrompt = "word ".repeat(200) + "Who is on call?";
        assertThat(PromptKey.of("fast", TestFixtures.userMessage("fast", longPrompt), TestFixtures.MAPPER, 150)
                .semanticEligible()).isFalse();
    }
}

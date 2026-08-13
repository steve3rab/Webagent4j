package io.webagent4j.locator;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.locator.api.TextMatch;
import io.webagent4j.locator.api.TextMatchType;
import org.junit.jupiter.api.Test;

class TextMatcherTest {

    private final TextMatcher matcher = new TextMatcher();

    @Test
    void normalizesUnicodeSpacesAndCaseForExactMatching() {
        assertThat(matcher.score(TextMatch.exactIgnoringCase("Sign in"), "  SIGN\u00a0  IN "))
                .isEqualTo(1.0);
        assertThat(matcher.score(TextMatch.exact("Sign in"), "sign in")).isZero();
        assertThat(matcher.score(TextMatch.containing("docs"), "API Docs reference"))
                .isEqualTo(0.95);
        assertThat(matcher.score(new TextMatch(TextMatchType.STARTS_WITH, "API"), "API Docs"))
                .isEqualTo(0.93);
        assertThat(matcher.score(new TextMatch(TextMatchType.ENDS_WITH, "Docs"), "API Docs"))
                .isEqualTo(0.92);
        assertThat(matcher.score(new TextMatch(TextMatchType.REGEX, "API\\s+Docs"), "API Docs"))
                .isEqualTo(1.0);
    }

    @Test
    void fuzzyMatchingHandlesConservativeWordAndStemVariations() {
        assertThat(matcher.similarity("Ajouter au panier", "Ajouter panier")).isGreaterThan(0.90);
        assertThat(matcher.similarity("Adresse e-mail", "Email")).isGreaterThanOrEqualTo(0.80);
        assertThat(matcher.similarity("Connexion", "Se connecter")).isGreaterThanOrEqualTo(0.80);
        assertThat(matcher.similarity("Delete account", "Weather forecast")).isLessThan(0.80);
        assertThat(matcher.similarity("", "value")).isZero();
        assertThat(matcher.similarity("same", "same")).isEqualTo(1.0);
    }

    @Test
    void fuzzyMatchingRejectsDangerousActionLookalikes() {
        assertThat(matcher.similarity("Delete", "Details report")).isLessThan(0.82);
        assertThat(matcher.similarity("Save", "Send message")).isLessThan(0.82);
        assertThat(matcher.similarity("Cancel", "Confirm purchase")).isLessThan(0.82);
        assertThat(matcher.similarity("Publish", "Unpublish")).isZero();
        assertThat(matcher.similarity("Add", "Remove member")).isLessThan(0.82);
    }

    @Test
    void fuzzyMatchingUsesTheWholePhraseInsteadOfOneSharedToken() {
        double intended = matcher.similarity("Delayed sav", "Delayed save");
        double decoy = matcher.similarity("Delayed sav", "Delayed send");
        double secondIntended = matcher.similarity("Delayed sendt", "Delayed send");
        double secondDecoy = matcher.similarity("Delayed sendt", "Delayed save");

        assertThat(intended).isGreaterThanOrEqualTo(0.82);
        assertThat(intended - decoy).isGreaterThan(0.04);
        assertThat(secondIntended).isGreaterThanOrEqualTo(0.82);
        assertThat(secondIntended - secondDecoy).isGreaterThan(0.04);
    }
}

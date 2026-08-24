package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Regression coverage for structured-scope identity resource ownership. */
class PlaywrightDomInspectionScriptsTest {

    @Test
    void structuredScopeIdentityStoreUsesConstantSpaceAndPreservesAmbiguity() {
        String script = PlaywrightDomInspectionScripts.STRUCTURED_SCOPE_SELECTOR_ENGINE_SCRIPT;

        assertThat(script)
                .contains("state = { identity: null, lease: null }")
                .contains("state.lease = lease")
                .contains("state.identity = identity")
                .contains("state.lease !== lease")
                .contains("return hasIdentity(semantic[0], options.binding) ? semantic : [];")
                .doesNotContain("maxBindingsPerElement")
                .doesNotContain("tokens = new Map()")
                .doesNotContain("while (tokens.size");

        int ambiguityGuard = script.indexOf("if (semantic.length !== 1)");
        int ambiguityReturn = script.indexOf("return semantic;", ambiguityGuard);
        int identityCheck =
                script.indexOf("return hasIdentity(semantic[0], options.binding) ? semantic : [];");

        assertThat(ambiguityGuard).isGreaterThanOrEqualTo(0);
        assertThat(ambiguityReturn).isGreaterThan(ambiguityGuard);
        assertThat(identityCheck).isGreaterThan(ambiguityReturn);
    }

    @Test
    void explicitScopeContainmentUsesThePreinstalledTrustedBridge() {
        String script = PlaywrightDomInspectionScripts.DESCENDANT_OR_SELF_FUNCTION;

        assertThat(script)
                .contains("bridge(\"contains\", ancestorOrSelf, element)")
                .doesNotContain("ancestorOrSelf.contains(element)");
    }
}

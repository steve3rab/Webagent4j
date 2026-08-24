package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Regression coverage for structured-scope binding resource ownership. */
class PlaywrightDomInspectionScriptsTest {

    @Test
    void structuredScopeBindingStoreIsExplicitlyBoundedAndFailClosed() {
        String script = PlaywrightDomInspectionScripts.STRUCTURED_SCOPE_SELECTOR_ENGINE_SCRIPT;

        assertThat(PlaywrightDomInspectionScripts.MAX_STRUCTURED_SCOPE_BINDINGS_PER_ELEMENT)
                .isPositive();

        assertThat(script)
                .contains(
                        "const maxBindingsPerElement = "
                                + PlaywrightDomInspectionScripts
                                        .MAX_STRUCTURED_SCOPE_BINDINGS_PER_ELEMENT)
                .contains("tokens = new Map()")
                .contains("while (tokens.size > maxBindingsPerElement)")
                .contains("tokens.delete(oldest.value)");

        /*
         * Cardinality must be checked before consulting the physical lease. Do not make this
         * assertion depend on text-block indentation: Spotless/JDK text-block normalization can
         * legitimately change whitespace without changing selector semantics.
         */
        int ambiguityGuard = script.indexOf("if (semantic.length !== 1)");
        int ambiguityReturn = script.indexOf("return semantic;", ambiguityGuard);
        int bindingCheck =
                script.indexOf("return hasBinding(semantic[0], options.binding) ? semantic : [];");

        assertThat(ambiguityGuard).isGreaterThanOrEqualTo(0);
        assertThat(ambiguityReturn).isGreaterThan(ambiguityGuard);
        assertThat(bindingCheck).isGreaterThan(ambiguityReturn);
    }
}

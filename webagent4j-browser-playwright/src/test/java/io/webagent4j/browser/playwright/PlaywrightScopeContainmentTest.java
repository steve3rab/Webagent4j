package io.webagent4j.browser.playwright;

import static io.webagent4j.browser.playwright.PlaywrightMixedScopeOrderTest.TestElement;
import static io.webagent4j.browser.playwright.PlaywrightMixedScopeOrderTest.allowDescendantOrSelf;
import static io.webagent4j.browser.playwright.PlaywrightMixedScopeOrderTest.denyDescendantOrSelf;
import static io.webagent4j.browser.playwright.PlaywrightMixedScopeOrderTest.element;
import static io.webagent4j.browser.playwright.PlaywrightMixedScopeOrderTest.pageContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.webagent4j.common.LocatorException;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorNotFoundException;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code within(...)} is a conjunction of nested constraints, never a replacement: {@code
 * within(A).within(B)} means "B, and B is proven to be inside A" - not "B, regardless of A". An
 * explicit element scope declared after any other scope must be proven, via the real Playwright DOM
 * relationship (never accessible name, role, or diagnostic path), to be a descendant of, or the
 * same node as, the current scope; otherwise resolution fails closed, without falling back to the
 * parent, the page, or any other scope.
 */
class PlaywrightScopeContainmentTest {

    @Test
    void anExplicitElementInsideTheCurrentScopeIsAccepted() {
        LocatorContext base = pageContext();
        TestElement parent = element("Product A");
        TestElement child = element("containerA");
        allowDescendantOrSelf(child, parent);
        LocatorContext scopedToParent = base.within(parent.element());

        LocatorContext resolved =
                PlaywrightScopeResolver.resolveElementScope(scopedToParent, child.element());

        assertThat(resolved.scope().root()).contains(child.element());
    }

    @Test
    void theSameElementUsedAgainIsAcceptedAsDescendantOrSelf() {
        LocatorContext base = pageContext();
        TestElement element = element("panel");
        allowDescendantOrSelf(element, element);
        LocatorContext scopedToElement = base.within(element.element());

        LocatorContext resolved =
                PlaywrightScopeResolver.resolveElementScope(scopedToElement, element.element());

        assertThat(resolved.scope().root()).contains(element.element());
    }

    @Test
    void anElementFromAnUnrelatedRegionIsRejectedInsteadOfEscapingItsParent() {
        LocatorContext base = pageContext();
        TestElement productA = element("Product A");
        TestElement containerB = element("containerB (belongs to Product B)");
        denyDescendantOrSelf(containerB, productA);
        LocatorContext scopedToProductA = base.within(productA.element());

        assertThatExceptionOfType(LocatorNotFoundException.class)
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveElementScope(
                                        scopedToProductA, containerB.element()));
    }

    @Test
    void anElementFromAnUnrelatedFormIsRejectedInsteadOfEscapingItsParent() {
        // element -> foreign element (mission scenario: formA -> panelB)
        LocatorContext base = pageContext();
        TestElement formA = element("formA");
        TestElement panelB = element("panelB (belongs to formB)");
        denyDescendantOrSelf(panelB, formA);
        LocatorContext scopedToFormA = base.within(formA.element());

        assertThatExceptionOfType(LocatorNotFoundException.class)
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveElementScope(
                                        scopedToFormA, panelB.element()));
    }

    @Test
    void anElementFromAnUnsupportedBackendIsRejectedWithoutAnUnsafeCast() {
        LocatorContext base = pageContext();
        TestElement parent = element("Product A");
        LocatorContext scopedToParent = base.within(parent.element());
        IElement foreignBackendElement = mock(IElement.class);

        assertThatExceptionOfType(LocatorException.class)
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveElementScope(
                                        scopedToParent, foreignBackendElement))
                .isNotInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void aDetachedExplicitChildFailsInsteadOfBeingSilentlyAccepted() {
        LocatorContext base = pageContext();
        TestElement parent = element("Product A");
        TestElement detachedChild = element("was-here");
        when(detachedChild.locator().count()).thenReturn(0);
        LocatorContext scopedToParent = base.within(parent.element());

        assertThatExceptionOfType(LocatorNotFoundException.class)
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveElementScope(
                                        scopedToParent, detachedChild.element()));
    }

    @Test
    void aDetachedCurrentScopeElementFailsInsteadOfSearchingElsewhere() {
        LocatorContext base = pageContext();
        TestElement detachedParent = element("Product A");
        when(detachedParent.locator().count()).thenReturn(0);
        TestElement child = element("containerA");
        LocatorContext scopedToDetachedParent = base.within(detachedParent.element());

        assertThatExceptionOfType(LocatorNotFoundException.class)
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveElementScope(
                                        scopedToDetachedParent, child.element()));
    }

    @Test
    void aGenuineBackendFailureWhileProvingContainmentIsNeverReinterpretedAsNotFound() {
        LocatorContext base = pageContext();
        TestElement parent = element("Product A");
        TestElement child = element("containerA");
        RuntimeException backendFailure = new RuntimeException("browser disconnected");
        when(child.locator()
                        .evaluate(
                                org.mockito.ArgumentMatchers.eq(
                                        PlaywrightDomInspectionScripts.DESCENDANT_OR_SELF_FUNCTION),
                                org.mockito.ArgumentMatchers.eq(parent.handle())))
                .thenThrow(backendFailure);
        LocatorContext scopedToParent = base.within(parent.element());

        assertThatRuntimeException()
                .isThrownBy(
                        () ->
                                PlaywrightScopeResolver.resolveElementScope(
                                        scopedToParent, child.element()))
                .isSameAs(backendFailure);
    }

    @Test
    void anExplicitElementAtThePageRootNeedsNoContainmentProof() {
        // within(explicitElement) as the very first scope: nothing narrowed it yet, so there is no
        // parent to prove membership against.
        LocatorContext base = pageContext();
        TestElement firstScope = element("Product A");

        LocatorContext resolved =
                PlaywrightScopeResolver.resolveElementScope(base, firstScope.element());

        assertThat(resolved.scope().root()).contains(firstScope.element());
    }
}

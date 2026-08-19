package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.IFrame;
import io.webagent4j.dom.IElement;
import org.junit.jupiter.api.Test;

/**
 * Proves backend-neutral frame navigation: {@link IFrame#navigate(String)} replaces only that
 * frame's own document, {@link IFrame#url()} reflects the change immediately, and ordinary {@code
 * find()} queries issued afterward search the new document - never a stale reference to content
 * from before the navigation, and never disturbing a parent frame's own scope when only a nested
 * frame is navigated.
 */
class FrameNavigationIT {

    @Test
    void navigatesAFrameToANewUrlAndUsesNormalLocatorsAfterward() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/nav-a")) {
            IFrame navFrame = page.frame().named("navtarget").single();
            assertThat(navFrame.url()).contains("/frames/child/nav-a");

            navFrame.navigate(support.url("/frames/child/nav-b"));

            assertThat(navFrame.url()).contains("/frames/child/nav-b");
            IElement markB = navFrame.find().button().named("Mark B").single();
            assertThat(markB.accessibleName()).isEqualTo("Mark B");
        }
    }

    @Test
    void aStaleElementHandleFromBeforeNavigationIsNotSilentlyReusedAfterward() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/nav-a")) {
            IFrame navFrame = page.frame().named("navtarget").single();
            IElement markA = navFrame.find().button().named("Mark A").single();

            navFrame.navigate(support.url("/frames/child/nav-b"));

            ActionResult<Void> result = navFrame.action().click(markA).execute();

            assertThat(result.success()).isFalse();
            assertThat(support.clickCount("nav-a-marker")).isZero();
        }
    }

    @Test
    void navigatingAnInnerFrameLeavesTheOuterFrameScopeValidWhileFreshlyResolvingTheInner()
            throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/nested-nav")) {
            IFrame outer = page.frame().named("outer").single();
            IFrame inner = outer.frame().named("inner").single();
            assertThat(inner.url()).contains("/frames/child/nested-nav-a");

            inner.navigate(support.url("/frames/child/nested-nav-b"));

            assertThat(inner.url()).contains("/frames/child/nested-nav-b");
            assertThat(outer.url()).contains("/frames/child/nested-outer-nav");
            IElement markNestedB = inner.find().button().named("Mark nested B").single();
            assertThat(markNestedB.accessibleName()).isEqualTo("Mark nested B");
        }
    }
}

package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.browser.IFrame;
import io.webagent4j.dom.IElement;
import org.junit.jupiter.api.Test;

/**
 * Proves nested frame traversal is public and backend-neutral: {@code page.frame().named("outer")}
 * resolves the top-level {@code <iframe>}, and calling {@code .frame().named("inner")} on the
 * resulting {@link IFrame} resolves the inner {@code <iframe>} strictly inside that outer frame's
 * own document - never accidentally matching a same-named frame elsewhere on the page.
 */
class FrameNestedIT {

    @Test
    void resolvesATargetInsideAFrameNestedInsideAnotherFrame() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/nested")) {
            IFrame outer = page.frame().named("outer").single();
            IFrame inner = outer.frame().named("inner").single();

            IElement pay = inner.find().button().named("Pay").single();

            assertThat(pay.accessibleName()).isEqualTo("Pay");
        }
    }

    @Test
    void aNestedFrameActionExecutesInsideTheCorrectInnerDocument() throws Exception {
        try (var support = FramePhase4TestSupport.start();
                var page = support.open("/frames/nested")) {
            IFrame outer = page.frame().named("outer").single();
            IFrame inner = outer.frame().named("inner").single();

            inner.action()
                    .click(inner.find().button().named("Pay").reference())
                    .expect(textVisible("Done"))
                    .execute()
                    .throwIfFailed();

            assertThat(support.clickCount("nested-pay")).isEqualTo(1);
        }
    }
}

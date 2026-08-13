package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.elementFocused;
import static io.webagent4j.verification.Verifications.textVisible;

import io.webagent4j.action.KeyPress;
import io.webagent4j.action.PortableKey;
import org.junit.jupiter.api.Test;

class KeyboardActionIT {

    @Test
    void movesFocusAndSubmitsWithPortableKeys() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/keyboard")) {
            var first = page.find().textbox().labelled("First").single();
            var second = page.find().textbox().labelled("Second").single();
            page.action().focus(first).execute().throwIfFailed();
            page.action()
                    .pressKey(first, KeyPress.of(PortableKey.TAB))
                    .expect(elementFocused(() -> second))
                    .execute()
                    .throwIfFailed();
            page.action()
                    .pressKey(second, KeyPress.of(PortableKey.ENTER))
                    .expect(textVisible("Submitted"))
                    .execute()
                    .throwIfFailed();
        }
    }
}

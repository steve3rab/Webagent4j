package io.webagent4j.examples;

import static io.webagent4j.verification.Verifications.urlContains;
import static io.webagent4j.verification.Verifications.valueEquals;

import io.webagent4j.action.Secret;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;

/** Demonstrates deterministic form entry, secret handling, and submission. */
public final class FormActionExample {

    private FormActionExample() {}

    /** Runs against a page containing a labelled sign-in form. */
    public static void main(String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException("Expected the form page URL as the first argument");
        }
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            var page = browser.open(args[0]);
            var email = page.find().textbox().labelled("Email").single();
            var password = page.find().textbox().labelled("Password").single();

            page.action()
                    .type(email, "user@example.test")
                    .expect(valueEquals("user@example.test"))
                    .execute()
                    .throwIfFailed();
            page.action().typeSecret(password, Secret.of("not-logged")).execute().throwIfFailed();
            page.action()
                    .submit(page.find().form().named("Sign in").single())
                    .expect(urlContains("/dashboard"))
                    .execute()
                    .throwIfFailed();
        }
    }
}

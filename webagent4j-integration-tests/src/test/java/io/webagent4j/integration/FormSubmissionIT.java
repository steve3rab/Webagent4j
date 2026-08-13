package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static io.webagent4j.verification.Verifications.urlContains;

import io.webagent4j.action.Secret;
import org.junit.jupiter.api.Test;

class FormSubmissionIT {

    @Test
    void fillsAndSubmitsAFormThroughSemanticPublicApis() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/login")) {
            var email = page.find().textbox().labelled("Email").single();
            var password = page.find().textbox().labelled("Password").single();
            page.action().type(email, "user@example.test").execute().throwIfFailed();
            page.action()
                    .typeSecret(password, Secret.of("phase4-password"))
                    .execute()
                    .throwIfFailed();
            var signIn = page.find().button().named("Sign in").single();
            page.action()
                    .click(signIn)
                    .expect(urlContains("/dashboard"))
                    .expect(textVisible("Welcome"))
                    .execute()
                    .throwIfFailed();
        }
    }
}

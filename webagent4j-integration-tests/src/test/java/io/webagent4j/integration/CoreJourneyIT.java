package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static io.webagent4j.verification.Verifications.urlContains;
import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.action.Secret;
import org.junit.jupiter.api.Test;

class CoreJourneyIT {

    @Test
    void validatesBrowserLocatorObservationActionAndVerificationTogether() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/login")) {
            var loginObservation = page.observe();
            assertThat(loginObservation.forms()).hasSize(1);

            var email = page.find().textbox().labelled("Email").single();
            var password = page.find().textbox().labelled("Password").single();
            var remember = page.find().checkbox().named("Remember me").single();
            page.action().type(email, "user@example.test").execute().throwIfFailed();
            page.action()
                    .typeSecret(password, Secret.of("WEBAGENT4J_PHASE4_SECRET_VALUE"))
                    .execute()
                    .throwIfFailed();
            page.action().check(remember).execute().throwIfFailed();

            var signIn = page.find().button().named("Sign in").single();
            var login =
                    page.action()
                            .click(signIn)
                            .expect(urlContains("/dashboard"))
                            .expect(textVisible("Welcome"))
                            .captureObservations(ObservationCapturePolicy.ALWAYS)
                            .execute();
            login.throwIfFailed();
            assertThat(login.diff()).isNotNull();
            assertThat(login.diff().urlChanged()).isTrue();

            var notifications = page.find().button().named("Open notifications").single();
            var dialog =
                    page.action()
                            .click(notifications)
                            .expect(textVisible("Notifications"))
                            .captureObservations(ObservationCapturePolicy.ALWAYS)
                            .execute();
            dialog.throwIfFailed();
            assertThat(dialog.afterObservation().dialogs()).isNotEmpty();
            assertThat(dialog.diff().dialogsOpened()).isNotEmpty();
        }
    }
}

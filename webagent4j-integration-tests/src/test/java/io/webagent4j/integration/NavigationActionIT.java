package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.titleContains;
import static io.webagent4j.verification.Verifications.urlContains;

import org.junit.jupiter.api.Test;

class NavigationActionIT {

    @Test
    void navigatesReloadsAndTraversesHistory() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/navigation/one")) {
            page.action()
                    .navigate(support.url("/navigation/two"))
                    .expect(titleContains("Two"))
                    .execute()
                    .throwIfFailed();
            page.action().reload().expect(urlContains("/navigation/two")).execute().throwIfFailed();
            page.action().goBack().expect(urlContains("/navigation/one")).execute().throwIfFailed();
            page.action()
                    .goForward()
                    .expect(urlContains("/navigation/two"))
                    .execute()
                    .throwIfFailed();
        }
    }
}

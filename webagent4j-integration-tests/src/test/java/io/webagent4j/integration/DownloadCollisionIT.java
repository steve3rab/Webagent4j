package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class DownloadCollisionIT {

    @Test
    void neverOverwritesAnExistingDestinationByDefault() throws Exception {
        var destination = Files.createTempFile("webagent4j-existing-", ".txt");
        Files.writeString(destination, "keep me");
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/download")) {
            var link = page.find().link().named("Download report").single();
            var result = page.action().download(link, destination).execute();
            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.DOWNLOAD_FAILURE);
            assertThat(Files.readString(destination)).isEqualTo("keep me");
        } finally {
            Files.deleteIfExists(destination);
        }
    }
}

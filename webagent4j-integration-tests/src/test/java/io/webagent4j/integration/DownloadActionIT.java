package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class DownloadActionIT {

    @Test
    void completesAndReturnsDownloadedFileMetadata() throws Exception {
        var directory = Files.createTempDirectory("webagent4j-download-");
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/download")) {
            var link = page.find().link().named("Download report").single();
            var result = page.action().download(link, directory).execute();
            assertThat(result.success()).isTrue();
            assertThat(result.value().suggestedFilename()).isEqualTo("report.txt");
            assertThat(result.value().size()).isPositive();
            assertThat(Files.readString(result.value().savedPath())).contains("download fixture");
            Files.deleteIfExists(result.value().savedPath());
        } finally {
            Files.deleteIfExists(directory);
        }
    }
}

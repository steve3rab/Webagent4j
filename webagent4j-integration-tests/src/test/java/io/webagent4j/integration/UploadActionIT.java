package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class UploadActionIT {

    @Test
    void uploadsAValidatedTemporaryFile() throws Exception {
        var file = Files.createTempFile("webagent4j-upload-", ".txt");
        Files.writeString(file, "upload fixture");
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/upload")) {
            var input = page.find().css("#file").single();
            page.action()
                    .upload(input, file)
                    .expect(textVisible(file.getFileName().toString()))
                    .execute()
                    .throwIfFailed();
        } finally {
            Files.deleteIfExists(file);
        }
    }
}

package io.webagent4j.examples;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.observation.FormFieldObservation;
import io.webagent4j.observation.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shows safe form structure without printing captured values. */
public final class FormObservationExample {

    private static final Logger LOGGER = LoggerFactory.getLogger(FormObservationExample.class);

    private FormObservationExample() {}

    /** Opens the optional URL argument and prints form and field semantics. */
    public static void main(String[] args) {
        String url = args.length == 0 ? "https://example.com" : args[0];
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open(url);
            Observation observation = page.observe();
            observation
                    .forms()
                    .forEach(
                            form -> {
                                LOGGER.info("Form: {} ({})", form.name(), form.method());
                                form.fields().stream()
                                        .map(FormObservationExample::describe)
                                        .forEach(
                                                description ->
                                                        LOGGER.info("Field: {}", description));
                            });
        }
    }

    private static String describe(FormFieldObservation field) {
        return field.label()
                + " type="
                + field.type()
                + " required="
                + field.required()
                + " sensitive="
                + field.sensitive();
    }
}

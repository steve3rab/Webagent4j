package io.webagent4j.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Command-line entry point built exclusively on WebAgent4J's public API. */
@Command(
        name = "webagent4j",
        mixinStandardHelpOptions = true,
        description = "Deterministic web automation for Java.",
        subcommands = {
            WebAgent4jCli.VersionCommand.class,
            WebAgent4jCli.ObserveCommand.class,
            WebAgent4jCli.ScreenshotCommand.class
        })
public final class WebAgent4jCli implements Runnable {

    @Spec private CommandSpec spec;

    /** Runs the command line and exits with its stable status code. */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new WebAgent4jCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "version", description = "Print the WebAgent4J version.")
    static final class VersionCommand implements Callable<Integer> {

        @Spec private CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().getOut().println(WebAgent.VERSION);
            return 0;
        }
    }

    @Command(
            name = "observe",
            aliases = "inspect",
            description = "Open a page and print its semantic observation as JSON.")
    static final class ObserveCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Absolute HTTP(S) URL")
        private String url;

        @Option(names = "--headed", description = "Show the browser window")
        private boolean headed;

        @Spec private CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            try (IBrowser browser =
                    WebAgent.browser().playwright().chromium().headless(!headed).launch()) {
                IPage page = browser.open(url);
                ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
                spec.commandLine().getOut().println(mapper.writeValueAsString(page.observe()));
                return 0;
            }
        }
    }

    @Command(name = "screenshot", description = "Capture a page as a PNG file.")
    static final class ScreenshotCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Absolute HTTP(S) URL")
        private String url;

        @Option(
                names = {"-o", "--output"},
                required = true,
                description = "Destination PNG path")
        private Path output;

        @Spec private CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            Path normalized = output.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (IBrowser browser =
                    WebAgent.browser().playwright().chromium().headless(true).launch()) {
                Files.write(normalized, browser.open(url).screenshot());
            }
            spec.commandLine().getOut().println(normalized);
            return 0;
        }
    }
}

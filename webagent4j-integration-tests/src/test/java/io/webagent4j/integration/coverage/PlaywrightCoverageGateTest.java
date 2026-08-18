package io.webagent4j.integration.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link PlaywrightCoverageGate}'s CSV parsing and threshold logic against small
 * hand-built fixtures, independent of a real JaCoCo run - this sandbox cannot exercise the full
 * {@code mvn verify} pipeline that produces the real aggregate CSV (see the module README / final
 * audit report), so this is the deterministic proof that the gate logic itself is correct.
 */
class PlaywrightCoverageGateTest {

    private static final String HEADER =
            "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,"
                    + "BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,"
                    + "COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED";

    @Test
    void sumsOnlyRowsForTheTargetPackage(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("jacoco.csv");
        Files.writeString(
                csv,
                HEADER
                        + System.lineSeparator()
                        + "G,io.webagent4j.browser.playwright,PlaywrightPage,0,0,0,0,3,7,0,0,0,0"
                        + System.lineSeparator()
                        + "G,io.webagent4j.browser.playwright,PlaywrightFind,0,0,0,0,0,10,0,0,0,0"
                        + System.lineSeparator()
                        + "G,io.webagent4j.core,WebAgent,0,0,0,0,0,100,0,0,0,0"
                        + System.lineSeparator());

        PlaywrightCoverageGate.Result result = PlaywrightCoverageGate.evaluate(csv);

        assertThat(result.lineCovered()).isEqualTo(17);
        assertThat(result.lineMissed()).isEqualTo(3);
        assertThat(result.ratio()).isCloseTo(0.85, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void includesNestedSubPackagesOfTheTargetPackage(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("jacoco.csv");
        Files.writeString(
                csv,
                HEADER
                        + System.lineSeparator()
                        + "G,io.webagent4j.browser.playwright.internal,Helper,0,0,0,0,1,9,0,0,0,0"
                        + System.lineSeparator());

        PlaywrightCoverageGate.Result result = PlaywrightCoverageGate.evaluate(csv);

        assertThat(result.lineCovered()).isEqualTo(9);
        assertThat(result.lineMissed()).isEqualTo(1);
    }

    @Test
    void doesNotMatchAnUnrelatedPackageWithTheSamePrefix(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("jacoco.csv");
        Files.writeString(
                csv,
                HEADER
                        + System.lineSeparator()
                        + "G,io.webagent4j.browser.playwrightsomethingelse,X,0,0,0,0,5,5,0,0,0,0"
                        + System.lineSeparator());

        PlaywrightCoverageGate.Result result = PlaywrightCoverageGate.evaluate(csv);

        assertThat(result.lineCovered()).isZero();
        assertThat(result.lineMissed()).isZero();
    }

    @Test
    void mainThrowsWhenCoverageIsBelowTheThreshold(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("jacoco.csv");
        Files.writeString(
                csv,
                HEADER
                        + System.lineSeparator()
                        + "G,io.webagent4j.browser.playwright,X,0,0,0,0,50,50,0,0,0,0"
                        + System.lineSeparator());

        assertThatThrownBy(() -> PlaywrightCoverageGate.main(new String[] {csv.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gate failed");
    }

    @Test
    void mainThrowsWhenNoDataExistsForTheTargetPackage(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("jacoco.csv");
        Files.writeString(
                csv,
                HEADER
                        + System.lineSeparator()
                        + "G,io.webagent4j.core,X,0,0,0,0,0,10,0,0,0,0"
                        + System.lineSeparator());

        assertThatThrownBy(() -> PlaywrightCoverageGate.main(new String[] {csv.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No coverage data found");
    }

    @Test
    void mainSucceedsSilentlyWhenCoverageMeetsTheThreshold(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("jacoco.csv");
        Files.writeString(
                csv,
                HEADER
                        + System.lineSeparator()
                        + "G,io.webagent4j.browser.playwright,X,0,0,0,0,10,90,0,0,0,0"
                        + System.lineSeparator());

        PlaywrightCoverageGate.main(new String[] {csv.toString()});
    }

    @Test
    void evaluateFailsFastOnAMissingFile(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does-not-exist.csv");

        assertThatIOException()
                .isThrownBy(() -> PlaywrightCoverageGate.evaluate(missing))
                .withMessageContaining("not found");
    }
}

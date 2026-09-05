package io.webagent4j.examples;

import com.sun.net.httpserver.HttpServer;
import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.common.RetryPolicy;
import io.webagent4j.core.WebAgent;
import io.webagent4j.dom.IElement;
import io.webagent4j.extraction.api.ExtractionRequest;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.recording.JsonWorkflowRecordingV2Codec;
import io.webagent4j.recording.RecordingId;
import io.webagent4j.recording.WorkflowRecorderV2;
import io.webagent4j.recording.WorkflowRecordingV2;
import io.webagent4j.recording.replay.IReplayOutcome;
import io.webagent4j.recording.replay.ReplayValidator;
import io.webagent4j.recording.replay.WorkflowReplayer;
import io.webagent4j.verification.IVerification;
import io.webagent4j.workflow.IWorkflowStep;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowConditions;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowExecution;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A single, coherent walkthrough of WebAgent4J's whole architecture: typed workflow variables, a
 * secret credential, conditional branching, a bounded loop, pre-execution validation and planning,
 * post-execution tree inspection, Recording V2, and Deterministic Replay - see {@code
 * docs/workflow.md#bounded-loops} for the full model. Runs against a tiny local HTTP fixture
 * started inline below, never a public website, so the whole example is deterministic and
 * Internet-free.
 */
public final class BoundedBrowserWorkflowShowcaseExample {

    private static final WorkflowVariable<IPage> PAGE =
            WorkflowVariable.publicValue("page", IPage.class);
    private static final WorkflowVariable<String> API_KEY = WorkflowVariable.secret("apiKey");
    private static final WorkflowVariable<Boolean> PREMIUM_CUSTOMER =
            WorkflowVariable.publicValue("premiumCustomer", Boolean.class);
    private static final WorkflowVariable<String> CURRENT_PAGE =
            WorkflowVariable.publicValue("currentPage", String.class);

    private BoundedBrowserWorkflowShowcaseExample() {}

    public static void main(String[] args) throws Exception {
        HttpServer server = startCatalogFixture();
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                IPage page = browser.open("http://127.0.0.1:" + server.getAddress().getPort())) {

            // Typed inputs: a live page, a boolean flag, a secret API key never printed or logged
            // in the clear - and a conditional branch that runs a governed click only when true.
            Workflow.Builder builder =
                    Workflow.builder("catalog-browse")
                            .requiredInput(PAGE)
                            .requiredInput(API_KEY)
                            .requiredInput(PREMIUM_CUSTOMER)
                            .step(
                                    WorkflowSteps.ifThen(
                                            "apply-discount-if-premium",
                                            WorkflowConditions.isTrue(PREMIUM_CUSTOMER),
                                            List.of(clickStep("Apply Discount"))))
                            .step(WorkflowSteps.assign("seed-page", CURRENT_PAGE, "1"))
                            .step(
                                    WorkflowSteps.loop(
                                            "paginate",
                                            WorkflowConditions.notEquals(CURRENT_PAGE, "3"),
                                            5, // maxIterations - no hidden infinite loop, ever
                                            List.of(clickStep("Next"), readPageIndicatorStep())));

            // Validate before ever touching the browser - structural only, zero side effects.
            var report = builder.validate();
            System.out.println("Valid: " + report.valid() + ", steps=" + report.stepCount());

            Workflow workflow = builder.build();

            // Inspect the side-effect-free execution plan: the loop appears once, as LOOP{BODY},
            // never unrolled into 5 copies.
            WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
            System.out.println("Plan top-level steps: " + plan.nodes().size());

            WorkflowInputs inputs =
                    WorkflowInputs.builder()
                            .put(PAGE, page)
                            .put(API_KEY, "sk_demo_51fddc2c_not_real")
                            .put(PREMIUM_CUSTOMER, true)
                            .build();

            // Execute the bounded pagination loop through the real action pipeline.
            WorkflowExecution execution = new WorkflowEngine().executeWithTree(workflow, inputs);
            execution.result().throwIfFailed();

            // Inspect the actual execution tree: exactly the iterations that ran.
            var loopNode = execution.tree().nodes().get(2);
            System.out.println("Loop iterations recorded: " + loopNode.children().size());

            // Capture Recording V2 and encode it to canonical JSON.
            WorkflowRecorderV2 recorder = new WorkflowRecorderV2();
            WorkflowRecordingV2 recording =
                    recorder.record(
                            new RecordingId("catalog-run-1"), Instant.now(), plan, execution);
            String encoded = new JsonWorkflowRecordingV2Codec().encode(recording);
            System.out.println("Recording encoded: " + encoded.length() + " bytes");

            // Deterministic Replay: structural/decision replay only - it reproduces the recorded
            // decisions and iteration count, but never re-clicks or re-visits the browser.
            ReplayValidator.validate(recording, workflow)
                    .ifPresentOrElse(
                            failure -> System.out.println("Replay incompatible: " + failure.type()),
                            () -> {
                                IReplayOutcome outcome =
                                        WorkflowReplayer.replay(recording, workflow);
                                System.out.println("Replay outcome: " + outcome);
                            });
        } finally {
            server.stop(0);
        }
    }

    private static IWorkflowStep clickStep(String label) {
        return WorkflowSteps.action(
                "click-" + label.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'),
                vars -> {
                    IPage page = vars.require(PAGE);
                    var target = page.find().button().named(label).single();
                    return page.action().click(target);
                });
    }

    private static IWorkflowStep readPageIndicatorStep() {
        return WorkflowSteps.action(
                "read-current-page",
                vars ->
                        new TextReadAction(
                                vars.require(PAGE),
                                LocatorDefinition.forRole(ElementRole.STATUS)
                                        .named("Current page")),
                CURRENT_PAGE);
    }

    /**
     * Wraps one real, synchronous {@link IPage#extract} read as a workflow action outcome -
     * extraction is a deliberately separate, ungoverned subsystem (see {@code
     * docs/limitations.md#observation}), so this wraps a real call rather than inventing a fake
     * one.
     */
    private static final class TextReadAction implements IPreparedAction<String> {
        private final IPage page;
        private final LocatorDefinition locator;

        TextReadAction(IPage page, LocatorDefinition locator) {
            this.page = page;
            this.locator = locator;
        }

        @Override
        public IPreparedAction<String> precondition(Predicate<IElement> predicate) {
            return this;
        }

        @Override
        public IPreparedAction<String> require(IVerification verification) {
            return this;
        }

        @Override
        public IPreparedAction<String> expect(IVerification verification) {
            return this;
        }

        @Override
        public IPreparedAction<String> expectUrlContains(String expectedFragment) {
            return this;
        }

        @Override
        public IPreparedAction<String> timeout(Duration timeout) {
            return this;
        }

        @Override
        public IPreparedAction<String> retry(RetryPolicy retryPolicy) {
            return this;
        }

        @Override
        public IPreparedAction<String> captureObservations(ObservationCapturePolicy policy) {
            return this;
        }

        @Override
        public ActionResult<String> execute() {
            String text = page.extract(ExtractionRequest.text(locator)).value();
            return new ActionResult<>(
                    true,
                    text,
                    Duration.ZERO,
                    List.of(),
                    Optional.empty(),
                    ActionExecutionMode.REAL);
        }

        @Override
        public IPreparedAction<String> dryRun() {
            return this;
        }

        @Override
        public IActionPlan<String> plan() {
            throw new UnsupportedOperationException("plan() is not used by this workflow step");
        }
    }

    private static HttpServer startCatalogFixture() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body =
                            """
                            <html><body>
                            <button aria-label="Apply Discount" id="discount">Apply Discount</button>
                            <p role="status" aria-label="Current page" id="page-indicator">1</p>
                            <button aria-label="Next" id="next" onclick="
                              var p = Number(page_indicator.textContent) + 1;
                              page_indicator.textContent = String(p);
                              if (p >= 3) { this.remove(); }
                            ">Next</button>
                            </body></html>
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        return server;
    }
}

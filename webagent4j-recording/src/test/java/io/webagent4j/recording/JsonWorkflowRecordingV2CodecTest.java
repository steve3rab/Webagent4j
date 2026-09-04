package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowId;
import io.webagent4j.workflow.WorkflowPlanNode;
import io.webagent4j.workflow.WorkflowPlanOutput;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * REC2-CODEC coverage for {@link JsonWorkflowRecordingV2Codec}: canonical round-tripping of every
 * structural shape Recording V2 adds over V1 (the execution plan, branch selections, nested
 * children), fail-closed strictness (unknown fields, duplicate keys, malformed JSON, the wrong
 * schema-version number), and the REC2-BOUND-001 resource limits, following the same style as
 * {@link RecordingCodecRoundTripBoundsTest} and {@link RecordingResourceBoundsTest} for V1.
 */
class JsonWorkflowRecordingV2CodecTest {

    private final JsonWorkflowRecordingV2Codec codec = new JsonWorkflowRecordingV2Codec();

    // ---- round trip ----

    @Test
    void roundTripsAMinimalCompletedRecording() {
        WorkflowRecordingV2 recording = RecordingV2Fixtures.minimalCompleted("wf");

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));

        assertThat(decoded).isEqualTo(recording);
    }

    @Test
    void roundTripsABranchingRecordingWithNestedChildren() {
        RecordedWorkflowStepV2 conditional = RecordingV2Fixtures.conditionalStep("cond-1", true);
        RecordedWorkflowStepV2 thenStep =
                RecordingV2Fixtures.succeededActionStep(
                        "then-1", Optional.of(RecordingV2Fixtures.output("out", "String", false)));
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        conditional,
                        WorkflowBranchSelection.THEN,
                        List.of(RecordingV2Fixtures.leaf(thenStep)));
        WorkflowRecordingV2 recording =
                RecordingV2Fixtures.recordingWith(
                        "wf",
                        RecordingV2Fixtures.branchingPlan("wf"),
                        WorkflowStatus.COMPLETED,
                        List.of(conditionalNode),
                        Optional.empty());

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));

        assertThat(decoded).isEqualTo(recording);
        assertThat(decoded.nodes().get(0).branchSelection()).contains(WorkflowBranchSelection.THEN);
        assertThat(decoded.nodes().get(0).children()).hasSize(1);
    }

    @Test
    void roundTripsAPreflightFailureRecording() {
        WorkflowRecordingV2 recording =
                RecordingV2Fixtures.recordingWith(
                        "wf",
                        RecordingV2Fixtures.minimalPlan("wf"),
                        WorkflowStatus.FAILED,
                        List.of(RecordingV2Fixtures.leaf(RecordingV2Fixtures.notRunStep("step-1"))),
                        Optional.of(
                                RecordingFixtures.preflightFailure(
                                        WorkflowFailureType.MISSING_REQUIRED_INPUT)));

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));

        assertThat(decoded).isEqualTo(recording);
    }

    @Test
    void roundTripsARuntimeFailureInsideASelectedBranch() {
        RecordedWorkflowStepV2 conditional = RecordingV2Fixtures.conditionalStep("cond-1", true);
        RecordedFailure failure =
                RecordingFixtures.actionFailedFailure("then-1", ActionFailureType.TARGET_NOT_FOUND);
        RecordedWorkflowStepV2 failedThen =
                RecordingV2Fixtures.actionStepFailedWithSummary(
                        "then-1",
                        failure,
                        ActionStatus.EXECUTION_FAILED,
                        ActionExecutionMode.NOT_EXECUTED);
        RecordedExecutionNodeV2 conditionalNode =
                RecordingV2Fixtures.conditionalNode(
                        conditional,
                        WorkflowBranchSelection.THEN,
                        List.of(RecordingV2Fixtures.leaf(failedThen)));
        WorkflowRecordingV2 recording =
                RecordingV2Fixtures.recordingWith(
                        "wf",
                        RecordingV2Fixtures.branchingPlan("wf"),
                        WorkflowStatus.FAILED,
                        List.of(conditionalNode),
                        Optional.of(failure));

        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));

        assertThat(decoded).isEqualTo(recording);
    }

    @Test
    void roundTripPreservesASecretOutputsClassificationButNeverAValue() {
        WorkflowPlanOutput secretOutput = RecordingV2Fixtures.output("password", "String", true);
        RecordedWorkflowStepV2 step =
                RecordingV2Fixtures.succeededAssignStep("step-1", secretOutput);
        WorkflowExecutionPlan plan =
                new WorkflowExecutionPlan(
                        new WorkflowId("wf"),
                        List.of(
                                new WorkflowPlanNode(
                                        new WorkflowStepId("step-1"),
                                        WorkflowStepType.ASSIGN,
                                        false,
                                        Optional.of(secretOutput),
                                        List.of())));
        WorkflowRecordingV2 recording =
                RecordingV2Fixtures.recordingWith(
                        "wf",
                        plan,
                        WorkflowStatus.COMPLETED,
                        List.of(RecordingV2Fixtures.leaf(step)),
                        Optional.empty());

        String encoded = codec.encode(recording);
        WorkflowRecordingV2 decoded = codec.decode(encoded);

        assertThat(decoded.nodes().get(0).step().output()).contains(secretOutput);
        assertThat(decoded.nodes().get(0).step().output().orElseThrow().secret()).isTrue();
        assertThat(encoded).doesNotContain("hunter2");
    }

    // ---- fail-closed strictness ----

    @Test
    void rejectsAV1SchemaVersionNumber() {
        String valid = codec.encode(RecordingV2Fixtures.minimalCompleted("wf"));
        String corrupted = valid.replaceFirst("\"schemaVersion\":2", "\"schemaVersion\":1");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void rejectsAnUnknownSchemaVersionNumber() {
        String valid = codec.encode(RecordingV2Fixtures.minimalCompleted("wf"));
        String corrupted = valid.replaceFirst("\"schemaVersion\":2", "\"schemaVersion\":99");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void rejectsAnUnknownTopLevelField() {
        String valid = codec.encode(RecordingV2Fixtures.minimalCompleted("wf"));
        String corrupted = valid.substring(0, valid.length() - 1) + ",\"bogus\":1}";

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void rejectsADuplicateTopLevelKey() {
        String valid = codec.encode(RecordingV2Fixtures.minimalCompleted("wf"));
        String corrupted = valid.replaceFirst("\\{", "{\"schemaVersion\":2,");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> codec.decode("{not valid json"))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void rejectsTrailingContentAfterTheDocument() {
        String valid = codec.encode(RecordingV2Fixtures.minimalCompleted("wf"));

        assertThatThrownBy(() -> codec.decode(valid + " {}"))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void rejectsAWrongJsonTypeForANode() {
        String valid = codec.encode(RecordingV2Fixtures.minimalCompleted("wf"));
        String corrupted = valid.replaceFirst("\"nodes\":\\[\\{", "\"nodes\":[1,{");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void rejectsAnInvalidEnumValue() {
        String valid = codec.encode(RecordingV2Fixtures.minimalCompleted("wf"));
        String corrupted = valid.replaceFirst("\"status\":\"COMPLETED\"", "\"status\":\"BOGUS\"");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void rejectsAMalformedInstant() {
        String valid = codec.encode(RecordingV2Fixtures.minimalCompleted("wf"));
        String corrupted =
                valid.replaceFirst(
                        "\"capturedAt\":\"2026-01-01T00:00:00Z\"",
                        "\"capturedAt\":\"not-a-timestamp\"");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void noDiagnosticEverEchoesExternalData() {
        String sentinel = "DIAGNOSTIC_SENTINEL_582104";
        String valid = codec.encode(RecordingV2Fixtures.minimalCompleted("wf"));
        String corrupted =
                valid.replaceFirst("\"status\":\"COMPLETED\"", "\"status\":\"" + sentinel + "\"");

        RecordingFormatException exception =
                (RecordingFormatException)
                        assertThatThrownBy(() -> codec.decode(corrupted))
                                .isInstanceOf(RecordingFormatException.class)
                                .actual();

        assertThat(exception.getMessage()).doesNotContain(sentinel);
        assertThat(exception.getCause()).isNull();
    }

    // ---- REC2-BOUND-001: resource limits ----

    @Test
    void encodeRefusesMoreThanMaxNodes() {
        List<RecordedExecutionNodeV2> nodes =
                new java.util.ArrayList<>(JsonWorkflowRecordingV2Codec.MAX_NODES + 1);
        List<WorkflowPlanNode> planNodes =
                new java.util.ArrayList<>(JsonWorkflowRecordingV2Codec.MAX_NODES + 1);
        for (int i = 0; i <= JsonWorkflowRecordingV2Codec.MAX_NODES; i++) {
            WorkflowPlanOutput output = RecordingV2Fixtures.output("o" + i, "String", false);
            nodes.add(
                    RecordingV2Fixtures.leaf(
                            RecordingV2Fixtures.succeededAssignStep("s" + i, output)));
            planNodes.add(
                    new WorkflowPlanNode(
                            new WorkflowStepId("s" + i),
                            WorkflowStepType.ASSIGN,
                            false,
                            Optional.of(output),
                            List.of()));
        }
        WorkflowExecutionPlan plan = new WorkflowExecutionPlan(new WorkflowId("wf"), planNodes);
        WorkflowRecordingV2 recording =
                RecordingV2Fixtures.recordingWith(
                        "wf", plan, WorkflowStatus.COMPLETED, nodes, Optional.empty());

        assertThatThrownBy(() -> codec.encode(recording))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum encodable node count");
    }

    @Test
    void decodeIndependentlyRejectsMoreThanMaxNodes() {
        // Built by direct JSON duplication rather than through encode(), which already refuses to
        // produce this document - proving decode() enforces the same bound independently, exactly
        // as RecordingResourceBoundsTest does for V1's MAX_STEPS.
        WorkflowRecordingV2 oneNode = RecordingV2Fixtures.minimalCompleted("wf");
        String oneNodeDoc = codec.encode(oneNode);
        // "nodes":[ appears twice (once nested under "plan", once at the top level) - anchor on the
        // top-level one, the closest occurrence before the trailing "failure" field.
        // The root "failure" field is textually last: every step's own inner "failure" field
        // (always present, possibly null) is written before it, so indexOf would find the wrong
        // one.
        int failureFieldStart = oneNodeDoc.lastIndexOf("\"failure\":");
        int nodesFieldStart = oneNodeDoc.lastIndexOf("\"nodes\":[", failureFieldStart);
        int contentStart = nodesFieldStart + "\"nodes\":[".length();
        int contentEnd = oneNodeDoc.lastIndexOf(']', failureFieldStart);
        String nodeJson = oneNodeDoc.substring(contentStart, contentEnd);

        StringBuilder nodesArray = new StringBuilder("[");
        for (int i = 0; i <= JsonWorkflowRecordingV2Codec.MAX_NODES; i++) {
            if (i > 0) {
                nodesArray.append(',');
            }
            nodesArray.append(nodeJson);
        }
        nodesArray.append(']');
        String corrupted =
                oneNodeDoc.substring(0, nodesFieldStart)
                        + "\"nodes\":"
                        + nodesArray
                        + oneNodeDoc.substring(contentEnd + 1);

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageContaining("exceeds maximum node count");
    }

    @Test
    void decodeRejectsExcessiveNestingDepthBeforeStackOverflow() {
        String leafNodeJson =
                "{\"step\":"
                        + oneActionStepJson("leaf")
                        + ",\"branchSelection\":null,\"children\":[]}";
        StringBuilder nested = new StringBuilder(leafNodeJson);
        for (int i = 0; i < JsonWorkflowRecordingV2Codec.MAX_TREE_DEPTH + 5; i++) {
            nested =
                    new StringBuilder(
                            "{\"step\":"
                                    + oneConditionalStepJson("cond" + i)
                                    + ",\"branchSelection\":\"THEN\",\"children\":["
                                    + nested
                                    + "]}");
        }
        WorkflowRecordingV2 minimal = RecordingV2Fixtures.minimalCompleted("wf");
        String valid = codec.encode(minimal);
        int failureFieldStart = valid.lastIndexOf("\"failure\":");
        int nodesFieldStart = valid.lastIndexOf("\"nodes\":[", failureFieldStart);
        int contentEnd = valid.lastIndexOf(']', failureFieldStart);
        String corrupted =
                valid.substring(0, nodesFieldStart)
                        + "\"nodes\":["
                        + nested
                        + "]"
                        + valid.substring(contentEnd + 1);

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageContaining("exceeds maximum nesting depth");
    }

    private static String oneActionStepJson(String stepId) {
        return "{\"stepId\":\""
                + stepId
                + "\",\"stepType\":\"ACTION\",\"status\":\"SUCCEEDED\",\"condition\":null,"
                + "\"output\":null,\"failure\":null,\"action\":"
                + "{\"actionId\":\"a1\",\"actionType\":\"CLICK\",\"status\":\"SUCCESS\","
                + "\"executionMode\":\"REAL\"}}";
    }

    private static String oneConditionalStepJson(String stepId) {
        return "{\"stepId\":\""
                + stepId
                + "\",\"stepType\":\"CONDITIONAL\",\"status\":\"SUCCEEDED\","
                + "\"condition\":{\"outcome\":true,\"description\":\"d\"},"
                + "\"output\":null,\"failure\":null,\"action\":null}";
    }

    @Test
    void decodeAtExactSizeBoundaryIsAccepted() {
        String valid = codec.encode(RecordingV2Fixtures.minimalCompleted("wf"));
        String padded =
                " ".repeat(JsonWorkflowRecordingV2Codec.MAX_ENCODED_LENGTH_CHARS - valid.length())
                        + valid;
        assertThat(padded).hasSize(JsonWorkflowRecordingV2Codec.MAX_ENCODED_LENGTH_CHARS);

        WorkflowRecordingV2 decoded = codec.decode(padded);

        assertThat(decoded).isEqualTo(RecordingV2Fixtures.minimalCompleted("wf"));
    }

    @Test
    void decodeOneCharOverSizeBoundaryIsRejected() {
        String valid = codec.encode(RecordingV2Fixtures.minimalCompleted("wf"));
        String padded =
                " "
                                .repeat(
                                        JsonWorkflowRecordingV2Codec.MAX_ENCODED_LENGTH_CHARS
                                                - valid.length()
                                                + 1)
                        + valid;

        assertThatThrownBy(() -> codec.decode(padded))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageContaining("exceeds maximum encoded size");
    }

    @Test
    void repeatedDecodeOfTheSameExcessiveFixtureFailsIdentically() {
        WorkflowRecordingV2 oneNode = RecordingV2Fixtures.minimalCompleted("wf");
        String oneNodeDoc = codec.encode(oneNode);
        int failureFieldStart = oneNodeDoc.lastIndexOf("\"failure\":");
        int nodesFieldStart = oneNodeDoc.lastIndexOf("\"nodes\":[", failureFieldStart);
        int contentStart = nodesFieldStart + "\"nodes\":[".length();
        int contentEnd = oneNodeDoc.lastIndexOf(']', failureFieldStart);
        String nodeJson = oneNodeDoc.substring(contentStart, contentEnd);
        StringBuilder nodesArray = new StringBuilder("[");
        for (int i = 0; i <= JsonWorkflowRecordingV2Codec.MAX_NODES; i++) {
            if (i > 0) {
                nodesArray.append(',');
            }
            nodesArray.append(nodeJson);
        }
        nodesArray.append(']');
        String corrupted =
                oneNodeDoc.substring(0, nodesFieldStart)
                        + "\"nodes\":"
                        + nodesArray
                        + oneNodeDoc.substring(contentEnd + 1);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> codec.decode(corrupted))
                    .isInstanceOf(RecordingFormatException.class)
                    .hasMessageContaining("exceeds maximum node count");
        }

        WorkflowRecordingV2 recording = RecordingV2Fixtures.minimalCompleted("wf");
        assertThat(codec.decode(codec.encode(recording))).isEqualTo(recording);
    }

    @Test
    void schemaVersionNumberIsExactly2() {
        String valid = codec.encode(RecordingV2Fixtures.minimalCompleted("wf"));

        assertThat(valid).contains("\"schemaVersion\":2");
    }
}

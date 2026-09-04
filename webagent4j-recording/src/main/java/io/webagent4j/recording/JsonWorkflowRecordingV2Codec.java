package io.webagent4j.recording;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowId;
import io.webagent4j.workflow.WorkflowPlanBranch;
import io.webagent4j.workflow.WorkflowPlanNode;
import io.webagent4j.workflow.WorkflowPlanOutput;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical JSON {@link IWorkflowRecordingV2Codec}.
 *
 * <p>This is the Recording V2 counterpart of {@link JsonWorkflowRecordingCodec}, following the
 * identical encoding/decoding discipline that class documents in full - canonical fixed field
 * order, always-emit-optional-as-null, streaming {@link JsonGenerator} rather than
 * annotation-driven POJO mapping, strict decoding that rejects duplicate keys/unknown fields/wrong
 * JSON types/unsupported schema versions/invalid enums without ever echoing external data back into
 * a thrown message, and deterministic resource bounds enforced before the allocation each one
 * protects. That discipline is not repeated in full here; only what is new for V2's tree-plus-plan
 * shape is documented below. The low-level decode helpers (field lookup, type checks, enum
 * resolution, and so on) are a deliberate separate copy of {@link JsonWorkflowRecordingCodec}'s own
 * - see {@link RecordedWorkflowStepV2}'s Javadoc for the same "no compile-time dependency on V1"
 * rationale.
 *
 * <p><b>Two independently bounded tree shapes, each with its own node-count and nesting-depth
 * limit:</b> a recording carries both a {@link WorkflowExecutionPlan} (every structurally possible
 * branch, both {@code THEN} and {@code ELSE}/{@code NONE}) and a {@link RecordedExecutionNodeV2}
 * tree (only the branch each conditional actually selected). These are separate recursive JSON
 * structures with separate {@code MAX_PLAN_NODES}/{@code MAX_NODES} counters, each incremented and
 * checked before the node it counts is allocated - so a hostile plan cannot exhaust the node budget
 * meant for the execution tree, or vice versa. Nesting depth for both is bounded by {@link
 * RecordingV2PlanTreeValidator#MAX_TREE_DEPTH} - this module's single source of truth for that
 * bound, also enforced by {@link WorkflowRecordingV2}'s own compact constructor, so this decoder
 * defines no independent copy of the value. Depth is checked explicitly by this decoder's own
 * recursive descent - independent of, and strictly tighter than, the JSON parser's own {@link
 * StreamReadConstraints} nesting limit - because this decoder's node-by-node reconstruction
 * recurses in step with the JSON structure: bounding only the parser's tree-building recursion
 * would leave this decoder's own recursive methods still exposed to a {@link StackOverflowError}
 * from a document deep enough to pass the parser but not this stricter check. The encode side
 * enforces the identical bound before ever descending into a node's children or branches, so {@code
 * decode(encode(recording))} never fails for depth reasons that {@code encode} itself could have
 * refused up front.
 */
public final class JsonWorkflowRecordingV2Codec implements IWorkflowRecordingV2Codec {

    // ---- REC2-BOUND-001: framework-owned resource limits, this codec's own single source of
    // truth - see the class Javadoc and JsonWorkflowRecordingCodec's REC-BOUND-001 for the general
    // rationale each one follows. ----

    /**
     * Maximum accepted length, in {@code char}s, of an entire encoded recording document. Larger
     * than V1's equivalent limit since a V2 recording additionally carries a full {@link
     * WorkflowExecutionPlan} alongside its execution tree.
     */
    static final int MAX_ENCODED_LENGTH_CHARS = 1_048_576;

    /**
     * Maximum accepted number of {@link RecordedExecutionNodeV2} nodes across an entire recording's
     * execution tree (top-level plus every nested descendant). Checked incrementally as the tree is
     * decoded, before each node is allocated.
     */
    static final int MAX_NODES = 2_000;

    /**
     * Maximum accepted number of {@link WorkflowPlanNode} nodes across an entire recording's {@link
     * WorkflowExecutionPlan} (top-level plus every nested descendant across both branches of every
     * conditional). Larger than {@link #MAX_NODES} because a plan always encodes both structurally
     * possible branches of every conditional, not only the one an execution actually selected.
     * Checked incrementally as the plan is decoded, before each node is allocated.
     */
    static final int MAX_PLAN_NODES = 4_000;

    /**
     * Maximum accepted length, in {@code char}s, of any single JSON string value. Enforced by the
     * parser itself via {@link StreamReadConstraints}, uniformly across every string-valued field.
     */
    static final int MAX_STRING_LENGTH_CHARS = 32_768;

    /**
     * Maximum accepted raw JSON nesting depth, enforced by the parser itself via {@link
     * StreamReadConstraints} as a safety net beneath {@link RecordingV2PlanTreeValidator#
     * MAX_TREE_DEPTH}: each single level of this decoder's own tree recursion spans several JSON
     * object/array levels (a node, its {@code children}/{@code branches} array, a child/branch
     * object, its own nested array), so this parser limit is set generously above what that depth
     * bound legitimately requires, purely to bound the JSON parser's own tree-building recursion
     * independently of this decoder's stricter, explicit check.
     */
    static final int MAX_JSON_NESTING_DEPTH = 512;

    /** Maximum accepted length, in {@code char}s, of a single JSON field name. */
    static final int MAX_NAME_LENGTH_CHARS = 1_000;

    /**
     * Maximum accepted length, in digits, of a single JSON numeric token ({@code schemaVersion}).
     */
    static final int MAX_NUMBER_LENGTH_DIGITS = 1_000;

    private static final JsonFactory JSON_FACTORY = createStrictFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper(JSON_FACTORY);

    private static final Set<String> TOP_FIELDS =
            Set.of(
                    "schemaVersion",
                    "recordingId",
                    "capturedAt",
                    "workflowId",
                    "status",
                    "plan",
                    "nodes",
                    "failure");
    private static final Set<String> PLAN_FIELDS = Set.of("workflowId", "nodes");
    private static final Set<String> PLAN_NODE_FIELDS =
            Set.of("stepId", "stepType", "guarded", "declaredOutput", "branches");
    private static final Set<String> PLAN_BRANCH_FIELDS = Set.of("kind", "nodes");
    private static final Set<String> OUTPUT_FIELDS = Set.of("name", "typeName", "secret");
    private static final Set<String> EXEC_NODE_FIELDS =
            Set.of("step", "branchSelection", "children");
    private static final Set<String> STEP_FIELDS =
            Set.of("stepId", "stepType", "status", "condition", "output", "failure", "action");
    private static final Set<String> CONDITION_FIELDS = Set.of("outcome", "description");
    private static final Set<String> ACTION_FIELDS =
            Set.of("actionId", "actionType", "status", "executionMode");
    private static final Set<String> FAILURE_FIELDS =
            Set.of("type", "safeMessage", "stepId", "underlyingTypeName", "actionFailureType");

    private static JsonFactory createStrictFactory() {
        StreamReadConstraints constraints =
                StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_JSON_NESTING_DEPTH)
                        .maxStringLength(MAX_STRING_LENGTH_CHARS)
                        .maxNameLength(MAX_NAME_LENGTH_CHARS)
                        .maxNumberLength(MAX_NUMBER_LENGTH_DIGITS)
                        .build();
        return JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    /** Creates a codec. Stateless: a single instance may encode/decode any number of recordings. */
    public JsonWorkflowRecordingV2Codec() {}

    /**
     * Encodes {@code recording} to its canonical JSON transport form.
     *
     * @throws IllegalArgumentException if {@code recording} cannot be represented within this
     *     codec's own {@link #decode(String)} resource limits - see the class Javadoc
     */
    @Override
    public String encode(WorkflowRecordingV2 recording) {
        Objects.requireNonNull(recording, "recording");
        validateEncodable(recording);
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = JSON_FACTORY.createGenerator(writer)) {
            writeRecording(generator, recording);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to encode recording", e);
        }
        String encoded = writer.toString();
        if (encoded.length() > MAX_ENCODED_LENGTH_CHARS) {
            throw new IllegalArgumentException("encoded recording exceeds maximum supported size");
        }
        return encoded;
    }

    private static void validateEncodable(WorkflowRecordingV2 recording) {
        requireEncodableLength(recording.recordingId().value());
        requireEncodableLength(recording.workflowId().value());
        validateEncodablePlan(recording.plan());
        int[] nodeCount = {0};
        for (RecordedExecutionNodeV2 node : recording.nodes()) {
            validateEncodableNode(node, nodeCount, 0);
        }
        recording.failure().ifPresent(JsonWorkflowRecordingV2Codec::validateEncodableFailure);
    }

    private static void validateEncodablePlan(WorkflowExecutionPlan plan) {
        requireEncodableLength(plan.workflowId().value());
        int[] planNodeCount = {0};
        for (WorkflowPlanNode node : plan.nodes()) {
            validateEncodablePlanNode(node, planNodeCount, 0);
        }
    }

    // Package-private (not private) so REC2 depth tests can exercise this guard directly with a
    // hand-built WorkflowPlanNode graph, without needing a WorkflowRecordingV2 whose own
    // construction-time invariant would otherwise make an over-depth input impossible to obtain.
    static void validateEncodablePlanNode(WorkflowPlanNode node, int[] counter, int depth) {
        counter[0]++;
        if (counter[0] > MAX_PLAN_NODES) {
            throw new IllegalArgumentException(
                    "recording plan exceeds maximum encodable node count");
        }
        requireEncodableLength(node.stepId().value());
        node.declaredOutput().ifPresent(JsonWorkflowRecordingV2Codec::requireEncodableOutput);
        if (!node.branches().isEmpty() && depth >= RecordingV2PlanTreeValidator.MAX_TREE_DEPTH) {
            throw new IllegalArgumentException(
                    "recording plan exceeds maximum encodable nesting depth");
        }
        for (WorkflowPlanBranch branch : node.branches()) {
            for (WorkflowPlanNode child : branch.nodes()) {
                validateEncodablePlanNode(child, counter, depth + 1);
            }
        }
    }

    // Package-private for the identical white-box-testing reason as validateEncodablePlanNode.
    static void validateEncodableNode(RecordedExecutionNodeV2 node, int[] counter, int depth) {
        counter[0]++;
        if (counter[0] > MAX_NODES) {
            throw new IllegalArgumentException("recording exceeds maximum encodable node count");
        }
        validateEncodableStep(node.step());
        if (node.branchSelection().isPresent()
                && depth >= RecordingV2PlanTreeValidator.MAX_TREE_DEPTH) {
            throw new IllegalArgumentException("recording exceeds maximum encodable nesting depth");
        }
        int childDepth = node.branchSelection().isPresent() ? depth + 1 : depth;
        for (RecordedExecutionNodeV2 child : node.children()) {
            validateEncodableNode(child, counter, childDepth);
        }
    }

    private static void validateEncodableStep(RecordedWorkflowStepV2 step) {
        requireEncodableLength(step.stepId().value());
        step.condition().ifPresent(condition -> requireEncodableLength(condition.description()));
        step.output().ifPresent(JsonWorkflowRecordingV2Codec::requireEncodableOutput);
        step.failure().ifPresent(JsonWorkflowRecordingV2Codec::validateEncodableFailure);
        step.action().ifPresent(action -> requireEncodableLength(action.actionId().value()));
    }

    private static void requireEncodableOutput(WorkflowPlanOutput output) {
        requireEncodableLength(output.name());
        requireEncodableLength(output.typeName());
    }

    private static void validateEncodableFailure(RecordedFailure failure) {
        requireEncodableLength(failure.safeMessage());
        failure.stepId().ifPresent(stepId -> requireEncodableLength(stepId.value()));
        failure.underlyingTypeName()
                .ifPresent(JsonWorkflowRecordingV2Codec::requireEncodableLength);
    }

    private static void requireEncodableLength(String value) {
        if (value.length() > MAX_STRING_LENGTH_CHARS) {
            throw new IllegalArgumentException(
                    "recording contains a string value exceeding the codec limit");
        }
    }

    @Override
    public WorkflowRecordingV2 decode(String data) {
        Objects.requireNonNull(data, "data");
        ObjectNode root = requireObject(parseSingleDocument(data), "$");
        try {
            return decodeRecording(root);
        } catch (RecordingFormatException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new RecordingFormatException("recording invariant violation");
        }
    }

    // ---- encode ----

    private static void writeRecording(JsonGenerator gen, WorkflowRecordingV2 recording)
            throws IOException {
        gen.writeStartObject();
        gen.writeNumberField("schemaVersion", recording.schemaVersion().number());
        gen.writeStringField("recordingId", recording.recordingId().value());
        gen.writeStringField("capturedAt", recording.capturedAt().toString());
        gen.writeStringField("workflowId", recording.workflowId().value());
        gen.writeStringField("status", recording.status().name());
        gen.writeFieldName("plan");
        writePlan(gen, recording.plan());
        gen.writeArrayFieldStart("nodes");
        for (RecordedExecutionNodeV2 node : recording.nodes()) {
            writeExecutionNode(gen, node);
        }
        gen.writeEndArray();
        writeFailureField(gen, "failure", recording.failure());
        gen.writeEndObject();
    }

    private static void writePlan(JsonGenerator gen, WorkflowExecutionPlan plan)
            throws IOException {
        gen.writeStartObject();
        gen.writeStringField("workflowId", plan.workflowId().value());
        gen.writeArrayFieldStart("nodes");
        for (WorkflowPlanNode node : plan.nodes()) {
            writePlanNode(gen, node);
        }
        gen.writeEndArray();
        gen.writeEndObject();
    }

    private static void writePlanNode(JsonGenerator gen, WorkflowPlanNode node) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("stepId", node.stepId().value());
        gen.writeStringField("stepType", node.stepType().name());
        gen.writeBooleanField("guarded", node.guarded());
        writeOutputField(gen, "declaredOutput", node.declaredOutput());
        gen.writeArrayFieldStart("branches");
        for (WorkflowPlanBranch branch : node.branches()) {
            writePlanBranch(gen, branch);
        }
        gen.writeEndArray();
        gen.writeEndObject();
    }

    private static void writePlanBranch(JsonGenerator gen, WorkflowPlanBranch branch)
            throws IOException {
        gen.writeStartObject();
        gen.writeStringField("kind", branch.kind().name());
        gen.writeArrayFieldStart("nodes");
        for (WorkflowPlanNode node : branch.nodes()) {
            writePlanNode(gen, node);
        }
        gen.writeEndArray();
        gen.writeEndObject();
    }

    private static void writeExecutionNode(JsonGenerator gen, RecordedExecutionNodeV2 node)
            throws IOException {
        gen.writeStartObject();
        gen.writeFieldName("step");
        writeStep(gen, node.step());
        writeOptionalEnumField(gen, "branchSelection", node.branchSelection());
        gen.writeArrayFieldStart("children");
        for (RecordedExecutionNodeV2 child : node.children()) {
            writeExecutionNode(gen, child);
        }
        gen.writeEndArray();
        gen.writeEndObject();
    }

    private static void writeStep(JsonGenerator gen, RecordedWorkflowStepV2 step)
            throws IOException {
        gen.writeStartObject();
        gen.writeStringField("stepId", step.stepId().value());
        gen.writeStringField("stepType", step.stepType().name());
        gen.writeStringField("status", step.status().name());
        writeConditionField(gen, "condition", step.condition());
        writeOutputField(gen, "output", step.output());
        writeFailureField(gen, "failure", step.failure());
        writeActionField(gen, "action", step.action());
        gen.writeEndObject();
    }

    private static void writeOutputField(
            JsonGenerator gen, String name, Optional<WorkflowPlanOutput> output)
            throws IOException {
        if (output.isEmpty()) {
            gen.writeNullField(name);
            return;
        }
        WorkflowPlanOutput value = output.get();
        gen.writeFieldName(name);
        gen.writeStartObject();
        gen.writeStringField("name", value.name());
        gen.writeStringField("typeName", value.typeName());
        gen.writeBooleanField("secret", value.secret());
        gen.writeEndObject();
    }

    private static void writeConditionField(
            JsonGenerator gen, String name, Optional<RecordedCondition> condition)
            throws IOException {
        if (condition.isEmpty()) {
            gen.writeNullField(name);
            return;
        }
        RecordedCondition value = condition.get();
        gen.writeFieldName(name);
        gen.writeStartObject();
        gen.writeBooleanField("outcome", value.outcome());
        gen.writeStringField("description", value.description());
        gen.writeEndObject();
    }

    private static void writeActionField(
            JsonGenerator gen, String name, Optional<RecordedAction> action) throws IOException {
        if (action.isEmpty()) {
            gen.writeNullField(name);
            return;
        }
        RecordedAction value = action.get();
        gen.writeFieldName(name);
        gen.writeStartObject();
        gen.writeStringField("actionId", value.actionId().value());
        gen.writeStringField("actionType", value.actionType().name());
        gen.writeStringField("status", value.status().name());
        gen.writeStringField("executionMode", value.executionMode().name());
        gen.writeEndObject();
    }

    private static void writeFailureField(
            JsonGenerator gen, String name, Optional<RecordedFailure> failure) throws IOException {
        if (failure.isEmpty()) {
            gen.writeNullField(name);
            return;
        }
        RecordedFailure value = failure.get();
        gen.writeFieldName(name);
        gen.writeStartObject();
        gen.writeStringField("type", value.type().name());
        gen.writeStringField("safeMessage", value.safeMessage());
        writeOptionalStringField(gen, "stepId", value.stepId().map(WorkflowStepId::value));
        writeOptionalStringField(gen, "underlyingTypeName", value.underlyingTypeName());
        writeOptionalEnumField(gen, "actionFailureType", value.actionFailureType());
        gen.writeEndObject();
    }

    private static void writeOptionalStringField(
            JsonGenerator gen, String name, Optional<String> value) throws IOException {
        if (value.isEmpty()) {
            gen.writeNullField(name);
        } else {
            gen.writeStringField(name, value.get());
        }
    }

    private static void writeOptionalEnumField(
            JsonGenerator gen, String name, Optional<? extends Enum<?>> value) throws IOException {
        if (value.isEmpty()) {
            gen.writeNullField(name);
        } else {
            gen.writeStringField(name, value.get().name());
        }
    }

    // ---- decode ----

    private static JsonNode parseSingleDocument(String data) {
        if (data.length() > MAX_ENCODED_LENGTH_CHARS) {
            throw new RecordingFormatException("recording exceeds maximum encoded size");
        }
        try (JsonParser parser = JSON_FACTORY.createParser(data)) {
            JsonNode node = MAPPER.readTree(parser);
            if (node == null) {
                throw new RecordingFormatException("malformed recording JSON");
            }
            JsonToken trailing = parser.nextToken();
            if (trailing != null) {
                throw new RecordingFormatException(
                        "trailing content after recording JSON document");
            }
            return node;
        } catch (RecordingFormatException e) {
            throw e;
        } catch (StreamConstraintsException e) {
            throw new RecordingFormatException(
                    "recording exceeds a configured JSON resource limit");
        } catch (IOException e) {
            throw new RecordingFormatException("malformed recording JSON");
        }
    }

    private static WorkflowRecordingV2 decodeRecording(ObjectNode root) {
        requireNoUnknownFields(root, TOP_FIELDS, "$");
        RecordingSchemaVersionV2 schemaVersion =
                RecordingSchemaVersionV2.fromNumber(
                        requireExactInt(root, "schemaVersion", "$.schemaVersion"));
        RecordingId recordingId =
                new RecordingId(requireText(root, "recordingId", "$.recordingId"));
        Instant capturedAt = requireInstant(root, "capturedAt", "$.capturedAt");
        WorkflowId workflowId = new WorkflowId(requireText(root, "workflowId", "$.workflowId"));
        WorkflowStatus status = requireEnum(WorkflowStatus.class, root, "status", "$.status");
        ObjectNode planNode = requireObjectField(root, "plan", "$.plan");
        WorkflowExecutionPlan plan = decodePlan(planNode, "$.plan");
        ArrayNode nodesArray = requireArray(root, "nodes", "$.nodes");
        int[] nodeCounter = {0};
        List<RecordedExecutionNodeV2> nodes =
                decodeExecutionNodes(nodesArray, "$.nodes", 0, nodeCounter);
        Optional<RecordedFailure> failure = decodeOptionalFailure(root, "failure", "$.failure");
        return new WorkflowRecordingV2(
                schemaVersion, recordingId, capturedAt, workflowId, status, plan, nodes, failure);
    }

    private static WorkflowExecutionPlan decodePlan(ObjectNode node, String path) {
        requireNoUnknownFields(node, PLAN_FIELDS, path);
        WorkflowId workflowId =
                new WorkflowId(requireText(node, "workflowId", path + ".workflowId"));
        ArrayNode nodesArray = requireArray(node, "nodes", path + ".nodes");
        int[] planNodeCounter = {0};
        List<WorkflowPlanNode> nodes =
                decodePlanNodes(nodesArray, path + ".nodes", 0, planNodeCounter);
        return new WorkflowExecutionPlan(workflowId, nodes);
    }

    private static List<WorkflowPlanNode> decodePlanNodes(
            ArrayNode array, String path, int depth, int[] counter) {
        List<WorkflowPlanNode> nodes = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            String nodePath = path + "[" + i + "]";
            ObjectNode nodeObj = requireObject(array.get(i), nodePath);
            nodes.add(decodePlanNode(nodeObj, nodePath, depth, counter));
        }
        return nodes;
    }

    private static WorkflowPlanNode decodePlanNode(
            ObjectNode node, String path, int depth, int[] counter) {
        counter[0]++;
        if (counter[0] > MAX_PLAN_NODES) {
            throw new RecordingFormatException("recording plan exceeds maximum node count");
        }
        requireNoUnknownFields(node, PLAN_NODE_FIELDS, path);
        WorkflowStepId stepId = new WorkflowStepId(requireText(node, "stepId", path + ".stepId"));
        WorkflowStepType stepType =
                requireEnum(WorkflowStepType.class, node, "stepType", path + ".stepType");
        boolean guarded = requireBoolean(node, "guarded", path + ".guarded");
        Optional<WorkflowPlanOutput> declaredOutput =
                decodeOptionalOutput(node, "declaredOutput", path + ".declaredOutput");
        ArrayNode branchesArray = requireArray(node, "branches", path + ".branches");
        if (branchesArray.size() > 0 && depth >= RecordingV2PlanTreeValidator.MAX_TREE_DEPTH) {
            throw new RecordingFormatException("recording exceeds maximum nesting depth");
        }
        List<WorkflowPlanBranch> branches = new ArrayList<>(branchesArray.size());
        for (int i = 0; i < branchesArray.size(); i++) {
            String branchPath = path + ".branches[" + i + "]";
            ObjectNode branchObj = requireObject(branchesArray.get(i), branchPath);
            branches.add(decodePlanBranch(branchObj, branchPath, depth + 1, counter));
        }
        return new WorkflowPlanNode(stepId, stepType, guarded, declaredOutput, branches);
    }

    private static WorkflowPlanBranch decodePlanBranch(
            ObjectNode node, String path, int depth, int[] counter) {
        requireNoUnknownFields(node, PLAN_BRANCH_FIELDS, path);
        WorkflowBranchSelection kind =
                requireEnum(WorkflowBranchSelection.class, node, "kind", path + ".kind");
        ArrayNode nodesArray = requireArray(node, "nodes", path + ".nodes");
        List<WorkflowPlanNode> nodes = decodePlanNodes(nodesArray, path + ".nodes", depth, counter);
        return new WorkflowPlanBranch(kind, nodes);
    }

    private static List<RecordedExecutionNodeV2> decodeExecutionNodes(
            ArrayNode array, String path, int depth, int[] counter) {
        List<RecordedExecutionNodeV2> nodes = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            String nodePath = path + "[" + i + "]";
            ObjectNode nodeObj = requireObject(array.get(i), nodePath);
            nodes.add(decodeExecutionNode(nodeObj, nodePath, depth, counter));
        }
        return nodes;
    }

    private static RecordedExecutionNodeV2 decodeExecutionNode(
            ObjectNode node, String path, int depth, int[] counter) {
        counter[0]++;
        if (counter[0] > MAX_NODES) {
            throw new RecordingFormatException("recording exceeds maximum node count");
        }
        requireNoUnknownFields(node, EXEC_NODE_FIELDS, path);
        ObjectNode stepObj = requireObjectField(node, "step", path + ".step");
        RecordedWorkflowStepV2 step = decodeStep(stepObj, path + ".step");
        Optional<WorkflowBranchSelection> branchSelection =
                optionalEnum(
                        WorkflowBranchSelection.class,
                        node,
                        "branchSelection",
                        path + ".branchSelection");
        ArrayNode childrenArray = requireArray(node, "children", path + ".children");
        if (branchSelection.isPresent() && depth >= RecordingV2PlanTreeValidator.MAX_TREE_DEPTH) {
            throw new RecordingFormatException("recording exceeds maximum nesting depth");
        }
        int childDepth = branchSelection.isPresent() ? depth + 1 : depth;
        List<RecordedExecutionNodeV2> children =
                decodeExecutionNodes(childrenArray, path + ".children", childDepth, counter);
        return new RecordedExecutionNodeV2(step, branchSelection, children);
    }

    private static RecordedWorkflowStepV2 decodeStep(ObjectNode stepNode, String path) {
        requireNoUnknownFields(stepNode, STEP_FIELDS, path);
        WorkflowStepId stepId =
                new WorkflowStepId(requireText(stepNode, "stepId", path + ".stepId"));
        WorkflowStepType stepType =
                requireEnum(WorkflowStepType.class, stepNode, "stepType", path + ".stepType");
        WorkflowStepStatus status =
                requireEnum(WorkflowStepStatus.class, stepNode, "status", path + ".status");
        Optional<RecordedCondition> condition =
                decodeOptionalCondition(stepNode, "condition", path + ".condition");
        Optional<WorkflowPlanOutput> output =
                decodeOptionalOutput(stepNode, "output", path + ".output");
        Optional<RecordedFailure> failure =
                decodeOptionalFailure(stepNode, "failure", path + ".failure");
        Optional<RecordedAction> action =
                decodeOptionalAction(stepNode, "action", path + ".action");
        return new RecordedWorkflowStepV2(
                stepId, stepType, status, condition, output, failure, action);
    }

    private static Optional<WorkflowPlanOutput> decodeOptionalOutput(
            ObjectNode parent, String field, String path) {
        Optional<ObjectNode> node = optionalObject(parent, field, path);
        if (node.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode output = node.get();
        requireNoUnknownFields(output, OUTPUT_FIELDS, path);
        String name = requireText(output, "name", path + ".name");
        String typeName = requireText(output, "typeName", path + ".typeName");
        boolean secret = requireBoolean(output, "secret", path + ".secret");
        return Optional.of(new WorkflowPlanOutput(name, typeName, secret));
    }

    private static Optional<RecordedCondition> decodeOptionalCondition(
            ObjectNode parent, String field, String path) {
        Optional<ObjectNode> node = optionalObject(parent, field, path);
        if (node.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode condition = node.get();
        requireNoUnknownFields(condition, CONDITION_FIELDS, path);
        boolean outcome = requireBoolean(condition, "outcome", path + ".outcome");
        String description = requireText(condition, "description", path + ".description");
        return Optional.of(new RecordedCondition(outcome, description));
    }

    private static Optional<RecordedAction> decodeOptionalAction(
            ObjectNode parent, String field, String path) {
        Optional<ObjectNode> node = optionalObject(parent, field, path);
        if (node.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode action = node.get();
        requireNoUnknownFields(action, ACTION_FIELDS, path);
        ActionId actionId = new ActionId(requireText(action, "actionId", path + ".actionId"));
        ActionType actionType =
                requireEnum(ActionType.class, action, "actionType", path + ".actionType");
        ActionStatus status = requireEnum(ActionStatus.class, action, "status", path + ".status");
        ActionExecutionMode executionMode =
                requireEnum(
                        ActionExecutionMode.class,
                        action,
                        "executionMode",
                        path + ".executionMode");
        return Optional.of(new RecordedAction(actionId, actionType, status, executionMode));
    }

    private static Optional<RecordedFailure> decodeOptionalFailure(
            ObjectNode parent, String field, String path) {
        Optional<ObjectNode> node = optionalObject(parent, field, path);
        if (node.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode failure = node.get();
        requireNoUnknownFields(failure, FAILURE_FIELDS, path);
        WorkflowFailureType type =
                requireEnum(WorkflowFailureType.class, failure, "type", path + ".type");
        String safeMessage = requireText(failure, "safeMessage", path + ".safeMessage");
        Optional<WorkflowStepId> stepId =
                optionalText(failure, "stepId", path + ".stepId").map(WorkflowStepId::new);
        Optional<String> underlyingTypeName =
                optionalText(failure, "underlyingTypeName", path + ".underlyingTypeName");
        Optional<ActionFailureType> actionFailureType =
                optionalEnum(
                        ActionFailureType.class,
                        failure,
                        "actionFailureType",
                        path + ".actionFailureType");
        return Optional.of(
                new RecordedFailure(
                        type, safeMessage, stepId, underlyingTypeName, actionFailureType));
    }

    // ---- decode helpers: every message below references only a fixed schema field path, never the
    // offending value ----

    private static ObjectNode requireObject(JsonNode node, String path) {
        if (!node.isObject()) {
            throw new RecordingFormatException("wrong JSON type for field: " + path);
        }
        return (ObjectNode) node;
    }

    private static void requireNoUnknownFields(ObjectNode node, Set<String> allowed, String path) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new RecordingFormatException("unknown field under: " + path);
            }
        }
    }

    private static JsonNode requireField(ObjectNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new RecordingFormatException("missing required field: " + path);
        }
        return value;
    }

    private static String requireText(ObjectNode node, String field, String path) {
        JsonNode value = requireField(node, field, path);
        if (!value.isTextual()) {
            throw new RecordingFormatException("wrong JSON type for field: " + path);
        }
        return value.textValue();
    }

    private static Optional<String> optionalText(ObjectNode node, String field, String path) {
        JsonNode value = requireField(node, field, path);
        if (value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw new RecordingFormatException("wrong JSON type for field: " + path);
        }
        return Optional.of(value.textValue());
    }

    private static boolean requireBoolean(ObjectNode node, String field, String path) {
        JsonNode value = requireField(node, field, path);
        if (!value.isBoolean()) {
            throw new RecordingFormatException("wrong JSON type for field: " + path);
        }
        return value.booleanValue();
    }

    private static int requireExactInt(ObjectNode node, String field, String path) {
        JsonNode value = requireField(node, field, path);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new RecordingFormatException("wrong JSON type for field: " + path);
        }
        return value.intValue();
    }

    private static ObjectNode requireObjectField(ObjectNode node, String field, String path) {
        JsonNode value = requireField(node, field, path);
        if (!value.isObject()) {
            throw new RecordingFormatException("wrong JSON type for field: " + path);
        }
        return (ObjectNode) value;
    }

    private static Optional<ObjectNode> optionalObject(ObjectNode node, String field, String path) {
        JsonNode value = requireField(node, field, path);
        if (value.isNull()) {
            return Optional.empty();
        }
        if (!value.isObject()) {
            throw new RecordingFormatException("wrong JSON type for field: " + path);
        }
        return Optional.of((ObjectNode) value);
    }

    private static ArrayNode requireArray(ObjectNode node, String field, String path) {
        JsonNode value = requireField(node, field, path);
        if (!value.isArray()) {
            throw new RecordingFormatException("wrong JSON type for field: " + path);
        }
        return (ArrayNode) value;
    }

    private static <E extends Enum<E>> E requireEnum(
            Class<E> type, ObjectNode node, String field, String path) {
        String text = requireText(node, field, path);
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            throw new RecordingFormatException("invalid enum value for field: " + path);
        }
    }

    private static <E extends Enum<E>> Optional<E> optionalEnum(
            Class<E> type, ObjectNode node, String field, String path) {
        Optional<String> text = optionalText(node, field, path);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, text.get()));
        } catch (IllegalArgumentException e) {
            throw new RecordingFormatException("invalid enum value for field: " + path);
        }
    }

    private static Instant requireInstant(ObjectNode node, String field, String path) {
        String text = requireText(node, field, path);
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new RecordingFormatException("malformed Instant for field: " + path);
        }
    }
}

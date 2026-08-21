package io.webagent4j.recording;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowId;
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
 * Canonical JSON {@link IWorkflowRecordingCodec}.
 *
 * <p><b>Encoding is deterministic and canonical:</b> fields are written one at a time, in a fixed
 * order per object level, using Jackson's streaming {@link JsonGenerator} directly rather than
 * relying on annotation-driven POJO serialization (whose field order is not a guaranteed, stable
 * contract). Output has no pretty-printing and no trailing newline. Every optional field is always
 * emitted - as {@code null} when absent - never sometimes present and sometimes omitted, so a
 * recording has exactly one canonical JSON representation. Every enum is written by {@link
 * Enum#name()}, never by ordinal.
 *
 * <p><b>Decoding is strict:</b> malformed JSON, a duplicate JSON object key at any nesting level,
 * an unknown or missing field, an unsupported {@code schemaVersion}, an invalid enum value, a
 * malformed {@code Instant}, a value of the wrong JSON type, an invalid recording invariant, and
 * trailing content after the JSON document are all rejected as {@link RecordingFormatException}.
 * There is no polymorphic or annotation-driven class deserialization anywhere in this codec: JSON
 * is walked as a plain node tree and mapped field-by-field onto this module's own record types,
 * reusing their compact-constructor validation rather than duplicating it.
 */
public final class JsonWorkflowRecordingCodec implements IWorkflowRecordingCodec {

    private static final JsonFactory JSON_FACTORY = createStrictFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper(JSON_FACTORY);

    private static final Set<String> TOP_FIELDS =
            Set.of("schemaVersion", "recordingId", "capturedAt", "workflow", "failure");
    private static final Set<String> WORKFLOW_FIELDS = Set.of("workflowId", "status", "steps");
    private static final Set<String> STEP_FIELDS =
            Set.of(
                    "stepId",
                    "stepType",
                    "status",
                    "condition",
                    "outputVariableName",
                    "failure",
                    "action");
    private static final Set<String> CONDITION_FIELDS = Set.of("outcome", "description");
    private static final Set<String> ACTION_FIELDS =
            Set.of("actionId", "actionType", "status", "executionMode");
    private static final Set<String> FAILURE_FIELDS =
            Set.of("type", "safeMessage", "stepId", "underlyingTypeName", "actionFailureType");

    private static JsonFactory createStrictFactory() {
        JsonFactory factory = new JsonFactory();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        return factory;
    }

    /** Creates a codec. Stateless: a single instance may encode/decode any number of recordings. */
    public JsonWorkflowRecordingCodec() {}

    @Override
    public String encode(WorkflowRecording recording) {
        Objects.requireNonNull(recording, "recording");
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = JSON_FACTORY.createGenerator(writer)) {
            writeRecording(generator, recording);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to encode recording", e);
        }
        return writer.toString();
    }

    @Override
    public WorkflowRecording decode(String data) {
        Objects.requireNonNull(data, "data");
        ObjectNode root = requireObject(parseSingleDocument(data), "$");
        try {
            return decodeRecording(root);
        } catch (RecordingFormatException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new RecordingFormatException("invalid recording: " + e.getMessage(), e);
        }
    }

    // ---- encode ----

    private static void writeRecording(JsonGenerator gen, WorkflowRecording recording)
            throws IOException {
        gen.writeStartObject();
        gen.writeNumberField("schemaVersion", recording.schemaVersion().number());
        gen.writeStringField("recordingId", recording.recordingId().value());
        gen.writeStringField("capturedAt", recording.capturedAt().toString());
        gen.writeFieldName("workflow");
        writeWorkflow(gen, recording);
        writeFailureField(gen, "failure", recording.failure());
        gen.writeEndObject();
    }

    private static void writeWorkflow(JsonGenerator gen, WorkflowRecording recording)
            throws IOException {
        gen.writeStartObject();
        gen.writeStringField("workflowId", recording.workflowId().value());
        gen.writeStringField("status", recording.status().name());
        gen.writeArrayFieldStart("steps");
        for (RecordedWorkflowStep step : recording.steps()) {
            writeStep(gen, step);
        }
        gen.writeEndArray();
        gen.writeEndObject();
    }

    private static void writeStep(JsonGenerator gen, RecordedWorkflowStep step) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("stepId", step.stepId().value());
        gen.writeStringField("stepType", step.stepType().name());
        gen.writeStringField("status", step.status().name());
        writeConditionField(gen, "condition", step.condition());
        writeOptionalStringField(gen, "outputVariableName", step.outputVariableName());
        writeFailureField(gen, "failure", step.failure());
        writeActionField(gen, "action", step.action());
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
        } catch (IOException e) {
            throw new RecordingFormatException("malformed recording JSON", e);
        }
    }

    private static WorkflowRecording decodeRecording(ObjectNode root) {
        requireNoUnknownFields(root, TOP_FIELDS, "$");
        RecordingSchemaVersion schemaVersion =
                RecordingSchemaVersion.fromNumber(
                        requireInt(root, "schemaVersion", "$.schemaVersion"));
        RecordingId recordingId =
                new RecordingId(requireText(root, "recordingId", "$.recordingId"));
        Instant capturedAt = requireInstant(root, "capturedAt", "$.capturedAt");
        ObjectNode workflowNode = requireObjectField(root, "workflow", "$.workflow");
        requireNoUnknownFields(workflowNode, WORKFLOW_FIELDS, "$.workflow");
        WorkflowId workflowId =
                new WorkflowId(requireText(workflowNode, "workflowId", "$.workflow.workflowId"));
        WorkflowStatus status =
                requireEnum(WorkflowStatus.class, workflowNode, "status", "$.workflow.status");
        ArrayNode stepsArray = requireArray(workflowNode, "steps", "$.workflow.steps");
        List<RecordedWorkflowStep> steps = new ArrayList<>(stepsArray.size());
        for (int i = 0; i < stepsArray.size(); i++) {
            String stepPath = "$.workflow.steps[" + i + "]";
            ObjectNode stepNode = requireObject(stepsArray.get(i), stepPath);
            steps.add(decodeStep(stepNode, stepPath));
        }
        Optional<RecordedFailure> failure = decodeOptionalFailure(root, "failure", "$.failure");
        return new WorkflowRecording(
                schemaVersion, recordingId, capturedAt, workflowId, status, steps, failure);
    }

    private static RecordedWorkflowStep decodeStep(ObjectNode stepNode, String path) {
        requireNoUnknownFields(stepNode, STEP_FIELDS, path);
        WorkflowStepId stepId =
                new WorkflowStepId(requireText(stepNode, "stepId", path + ".stepId"));
        WorkflowStepType stepType =
                requireEnum(WorkflowStepType.class, stepNode, "stepType", path + ".stepType");
        WorkflowStepStatus status =
                requireEnum(WorkflowStepStatus.class, stepNode, "status", path + ".status");
        Optional<RecordedCondition> condition =
                decodeOptionalCondition(stepNode, "condition", path + ".condition");
        Optional<String> outputVariableName =
                optionalText(stepNode, "outputVariableName", path + ".outputVariableName");
        Optional<RecordedFailure> failure =
                decodeOptionalFailure(stepNode, "failure", path + ".failure");
        Optional<RecordedAction> action =
                decodeOptionalAction(stepNode, "action", path + ".action");
        return new RecordedWorkflowStep(
                stepId, stepType, status, condition, outputVariableName, failure, action);
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
                throw new RecordingFormatException("unknown field: " + path + "." + name);
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

    private static int requireInt(ObjectNode node, String field, String path) {
        JsonNode value = requireField(node, field, path);
        if (!value.isIntegralNumber()) {
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

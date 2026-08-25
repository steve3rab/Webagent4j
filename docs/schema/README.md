# Published schemas

This directory contains machine-readable schemas for stable serialized formats.

- `recording-v1.schema.json` describes the JSON field/type/enum surface of Workflow Recording V1.

The JSON Schema is intentionally complemented by Java model/codec validation. Cross-field constraints such as the exact workflow fail-fast trace and action status/execution/failure matrix are enforced by the WebAgent4J recording model and `JsonWorkflowRecordingCodec`; a document that passes generic JSON-Schema validation can still be rejected if it represents an impossible execution state.

A future incompatible recording format must use a new schema version/file rather than silently changing V1 in place.

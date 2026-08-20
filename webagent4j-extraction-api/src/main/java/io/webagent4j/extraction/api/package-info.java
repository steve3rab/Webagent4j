/**
 * Backend-neutral extraction request/result/provenance contracts, converters, validators, and the
 * extraction-specific failure taxonomy - depends only on {@code webagent4j-locator-api}, never on
 * {@code webagent4j-dom}.
 *
 * <p>{@link io.webagent4j.extraction.api.ExtractionRequest} is the immutable description of one
 * extraction intent (source locator, raw read type, converter, optional validator), built from its
 * static factories {@code text(...)}/{@code attribute(...)}/{@code value(...)}. A converter is
 * always present - these factories start with {@link
 * io.webagent4j.extraction.api.IValueConverter#identity()} - so an engine never has to fall back to
 * an unchecked cast, and a converter that returns {@code null} is itself treated as a conversion
 * failure. {@link io.webagent4j.extraction.api.ExtractionResult} can therefore never carry a {@code
 * null} value. {@link io.webagent4j.extraction.api.IValueConverter} and {@link
 * io.webagent4j.extraction.api.IExtractionValidator} are the extension points for custom conversion
 * and validation logic.
 *
 * <p>Not-found and ambiguity are deliberately not part of this package's exception hierarchy -
 * those stay {@code LocatorNotFoundException}/{@code AmbiguousLocatorException} from the locator
 * layer, never reinterpreted here. See {@code docs/extraction.md} for the full read/convert/
 * validate pipeline, table extraction, and provenance semantics.
 */
package io.webagent4j.extraction.api;

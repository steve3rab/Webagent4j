package io.webagent4j.crawler.api.internal;

import java.util.BitSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The single, framework-owned definition of what caller-supplied crawler HTTP headers are accepted
 * - applied identically regardless of which internal HTTP transport eventually sends the request
 * ({@code JavaHttpFetcher}'s {@code java.net.http.HttpClient}, or the hand-rolled {@code
 * PinnedSocketHttpTransport}). Every caller of this class runs these checks during
 * configuration/construction - before any DNS resolution, network policy evaluation, socket
 * creation, or HTTP connection - so an invalid header is always an {@link IllegalArgumentException}
 * thrown at construction time, never something discovered mid-fetch.
 *
 * <p>Nothing here modifies, trims, strips, or otherwise repairs a header: every rejection is a hard
 * failure of the offending caller input, never a silent normalization. Exception messages never
 * include the caller-supplied header name or value - a malformed name gets a fixed diagnostic
 * category, and a malformed value is never echoed, since either can carry a secret-shaped or
 * injection-shaped payload the caller controls entirely.
 */
public final class HttpHeaderValidation {

    private HttpHeaderValidation() {
        // utility class
    }

    /**
     * Header names WebAgent4J or its transports compute and own exclusively - accepting caller
     * control over any of these would let a caller override request framing or destination binding
     * invariants a transport otherwise guarantees. Matched case-insensitively, since HTTP header
     * names are case-insensitive.
     *
     * <ul>
     *   <li>{@code Host} - the destination this request binds to. The pinned transport sets it from
     *       the exact, already policy-verified destination, never from caller text; allowing an
     *       override here would let a caller's request line/SNI/certificate check target a
     *       different host than the one a network policy actually authorized.
     *   <li>{@code Connection} - both transports use exactly one, never pooled or reused connection
     *       per request ({@code Connection: close}); a caller-supplied value (for example {@code
     *       keep-alive}) would contradict that connection-lifecycle invariant.
     *   <li>{@code Content-Length}, {@code Transfer-Encoding} - request body framing. This is a
     *       {@code GET}-only crawler that never sends a request body, so neither header is ever
     *       meaningful on a request it issues.
     *   <li>{@code Expect} - {@code 100-continue} request-body semantics, meaningless without a
     *       request body. {@code java.net.http.HttpClient} also restricts it by default: leaving it
     *       caller-settable would make the {@code HttpClient}-backed transport reject it late
     *       (discovered only once a request is actually being built) while the pinned transport
     *       would have silently accepted it - exactly the transport-dependent divergence this
     *       validator exists to remove.
     *   <li>{@code Upgrade} - protocol-upgrade framing (for example WebSocket). This crawler only
     *       ever performs a plain HTTP/1.1 {@code GET} request/response and never upgrades a
     *       connection; also restricted by {@code HttpClient} itself by default, for the same
     *       transport-parity reason as {@code Expect}.
     * </ul>
     */
    private static final Set<String> FRAMEWORK_CONTROLLED_HEADER_NAMES =
            Set.of(
                    "host",
                    "connection",
                    "content-length",
                    "transfer-encoding",
                    "expect",
                    "upgrade");

    private static final BitSet TOKEN_CHARACTERS = buildTokenCharacterSet();

    private static BitSet buildTokenCharacterSet() {
        // RFC 9110 section 5.1 / RFC 7230 section 3.2.6 "tchar": the exact character set a legal
        // HTTP field name may use. Anything outside this set - including space, tab, ':', CR, LF,
        // NUL, every other control character, and non-ASCII - is rejected by construction, not by
        // an incomplete blocklist.
        BitSet set = new BitSet(128);
        for (char c = 'a'; c <= 'z'; c++) {
            set.set(c);
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            set.set(c);
        }
        for (char c = '0'; c <= '9'; c++) {
            set.set(c);
        }
        for (char c :
                new char[] {
                    '!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~'
                }) {
            set.set(c);
        }
        return set;
    }

    /**
     * Validates a caller-supplied HTTP header name against the HTTP/1.1 field-name ("token")
     * grammar: one or more of {@code A-Z a-z 0-9 ! # $ % & ' * + - . ^ _ ` | ~}. This single
     * grammar-based rule rejects a null or empty name, a whitespace-only name, spaces, tabs, a
     * colon, CR, LF, NUL, every other control character, and any non-ASCII/malformed-Unicode
     * character - all of which are excluded from {@code tchar} - without needing a separate check
     * for each.
     *
     * @throws IllegalArgumentException if {@code name} is null, empty, or contains any character
     *     outside the token grammar. The message never repeats {@code name}: a malformed name is
     *     reported by a fixed diagnostic category only.
     */
    public static void requireValidHeaderName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("header name must not be null or empty");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character > 127 || !TOKEN_CHARACTERS.get(character)) {
                throw new IllegalArgumentException(
                        "header name is not a valid HTTP field-name token");
            }
        }
    }

    /**
     * Validates a caller-supplied HTTP header value. A valid value is made up only of the visible
     * US-ASCII range ({@code 0x20}-{@code 0x7E}, which includes the space character) plus the
     * horizontal tab ({@code 0x09}) - the one piece of legacy HTTP field-value whitespace both
     * {@code java.net.http.HttpClient} and this project's own pinned transport already tolerate in
     * a value. Every other character is rejected, in particular: {@code \r}, {@code \n}, {@code
     * \0}, every other C0 control character, {@code DEL} ({@code 0x7F}), and anything outside
     * US-ASCII - the pinned transport serializes a request as pure US-ASCII text, so a non-ASCII
     * value could otherwise be silently corrupted rather than rejected.
     *
     * <p>The value is never modified to make it valid - no trimming, no stripping - it is either
     * accepted exactly as given or rejected outright.
     *
     * @throws IllegalArgumentException if {@code value} is null or contains a forbidden character.
     *     The message never includes {@code value} itself, since a header value is exactly the kind
     *     of caller-controlled text that can carry a secret or an injection payload.
     */
    public static void requireValidHeaderValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("header value must not be null");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean visibleOrSpace = character >= 0x20 && character <= 0x7E;
            boolean legacyTab = character == 0x09;
            if (!visibleOrSpace && !legacyTab) {
                throw new IllegalArgumentException(
                        "header value contains a character forbidden in an HTTP field value");
            }
        }
    }

    /**
     * Rejects a header name WebAgent4J or its transports own exclusively - see {@link
     * #FRAMEWORK_CONTROLLED_HEADER_NAMES} for the exact set and the justification for each. Matched
     * case-insensitively.
     *
     * @throws IllegalArgumentException if {@code name}, once lowercased, is framework-controlled.
     *     The message names which header is restricted - safe to include, since it can only ever be
     *     one of the small fixed set above, never arbitrary caller text.
     */
    public static void requireNotFrameworkControlled(String name) {
        Objects.requireNonNull(name, "name");
        String lowercased = name.toLowerCase(Locale.ROOT);
        if (FRAMEWORK_CONTROLLED_HEADER_NAMES.contains(lowercased)) {
            throw new IllegalArgumentException(
                    "header '"
                            + lowercased
                            + "' is controlled by the framework and cannot be set by the caller");
        }
    }

    /**
     * Validates one header's name and value together: {@link #requireValidHeaderName(String)}, then
     * {@link #requireNotFrameworkControlled(String)}, then {@link
     * #requireValidHeaderValue(String)}.
     *
     * @throws IllegalArgumentException per the delegated checks above.
     */
    public static void requireValidHeader(String name, String value) {
        requireValidHeaderName(name);
        requireNotFrameworkControlled(name);
        requireValidHeaderValue(value);
    }

    /**
     * Validates every entry of {@code headers} with {@link #requireValidHeader(String, String)}, in
     * {@code headers}' own iteration order - so a caller that supplies an order-preserving map (for
     * example {@link java.util.LinkedHashMap}, as {@code CrawlRequest.Builder} does) gets a
     * deterministic first-failure, never one an unordered copy (such as {@link Map#copyOf(Map)})
     * could reorder. Validate with this method before converting to an unordered immutable
     * representation, never after.
     *
     * @throws IllegalArgumentException on the first invalid entry found, per {@link
     *     #requireValidHeader(String, String)}.
     */
    public static void requireValidHeaders(Map<String, String> headers) {
        Objects.requireNonNull(headers, "headers");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            requireValidHeader(header.getKey(), header.getValue());
        }
    }
}

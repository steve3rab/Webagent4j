# Command-line interface

The CLI is built entirely on WebAgent4J public Java APIs. It is a separate compatibility surface from the Java library even though it shares the same repository version.

## Invocation

After building or downloading the CLI JAR:

```bash
java -jar webagent4j-cli-<version>.jar <command> [options]
```

## Supported commands

### `version`

Prints the WebAgent4J version and exits successfully.

```bash
java -jar webagent4j-cli-<version>.jar version
```

### `observe` / `inspect`

Opens an absolute HTTP(S) URL in Chromium, captures a semantic observation, and writes pretty JSON to standard output.

```bash
java -jar webagent4j-cli-<version>.jar observe https://example.com
java -jar webagent4j-cli-<version>.jar inspect https://example.com --headed
```

`--headed` shows the browser window. Without it, Chromium runs headless.

The JSON is the CLI rendering of the current observation model. It is not the stable workflow Recording V1 persistence schema.

### `screenshot`

Opens an absolute HTTP(S) URL in headless Chromium and writes a PNG to the required destination.

```bash
java -jar webagent4j-cli-<version>.jar screenshot https://example.com -o screenshots/example.png
```

`-o` / `--output` is required. Parent directories are created when needed. The CLI prints the normalized destination path after a successful write.

## Exit status policy

- `0` means the selected command completed normally.
- invalid command-line syntax or execution failures return a non-zero process status through the CLI framework/runtime.

For the 1.0 Java-library compatibility contract, only success-versus-nonzero failure is currently stable. Individual non-zero numeric categories and human-readable error prose are not promised as a machine protocol until the CLI adds explicit application-owned error-code mapping.

## Security

The CLI inherits the library's URL/network trust boundary. It accepts HTTP(S) targets and does not provide a universal SSRF firewall. Do not expose it as an unauthenticated “browse arbitrary URL” service without a separate destination allowlist/network policy.

Observation output can contain page-provided semantic text. Treat it as untrusted data even though supported secret-value redaction rules apply.

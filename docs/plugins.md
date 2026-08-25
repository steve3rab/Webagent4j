# Plugins

WebAgent4J has one explicit discovered plugin extension point: trusted Java `ILocatorStrategyProvider` implementations may contribute custom locator strategies.

Without an explicit `PluginLoader` call, zero locator plugins are loaded. Default locator/browser/workflow/recording/crawler construction does not scan for plugins.

## Provider contract

A provider returns one validated descriptor and a deterministic non-empty list of custom locator strategies. Plugin strategies must use `LocatorStrategyType.CUSTOM`; they cannot override built-in strategy types.

Provider metadata contains a validated plugin ID and opaque version label. Version strings are descriptive metadata; WebAgent4J does not perform Maven-style range solving, highest-version selection, dependency resolution, or compatibility negotiation.

## Service registration

Provider JARs use the standard Java service file:

```text
META-INF/services/io.webagent4j.plugin.ILocatorStrategyProvider
```

Discovery is explicit through `PluginLoader`. `load(ClassLoader)` uses exactly the supplied caller-owned loader; the loader is not closed or globally cached by WebAgent4J.

## Deterministic ordering

Provider/service-file order is not semantic order. Contributions are normalized into deterministic provider/descriptor/strategy order according to the loader and locator registry contracts. Descriptor/strategy callbacks are called only in their documented lifecycle; discovery does not secretly run locator strategy matching.

## All-or-nothing validation

Duplicate plugin IDs, duplicate custom strategy IDs, collisions with standard IDs, invalid strategy types/metadata, malformed services, null/empty invalid contributions, and provider construction/registration failures abort the load. There is no first-wins/last-wins/automatic rename/partial registry.

## Runtime failures

Load-time provider failures are translated into structured plugin load failures without copying arbitrary provider exception messages into framework-owned safe diagnostics. JVM `Error` values are not generally caught as recoverable plugin configuration failures.

After loading, custom strategy runtime failures follow locator failure behavior and are not silently converted into an empty result.

## Security/trust

Plugins are ordinary trusted in-process Java. There is no sandbox, process isolation, permission model, network/filesystem restriction, callback timeout, or automatic recovery from malicious code. Review plugin JARs like any other dependency.

Plugin IDs/versions/strategy IDs/provider class names are diagnostic metadata and must not contain secrets.

## Deliberate non-goals

No plugin lifecycle callbacks, configuration schema, DI container, plugin directory, annotation scanning, network download, hot reload/unload, file watching, dependency resolver, action/workflow/recording/crawler/observation/browser-provider plugin discovery.

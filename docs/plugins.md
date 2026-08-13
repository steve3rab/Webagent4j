# Plugins

The plugin module reserves the extension boundary above locator contracts. The first plugin vertical
will use Java `ServiceLoader` for deliberately small registration contracts. It will add custom locator
strategies, extractors, converters, storage providers, actions, or verification rules only as those
extension points become real. V1 does not scan arbitrary classpaths or expose a proprietary plugin bus.

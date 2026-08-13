package io.webagent4j.browser.playwright;

import com.microsoft.playwright.Page;
import io.webagent4j.dom.ElementState;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.InputFieldType;
import io.webagent4j.observation.ObservationOptions;
import io.webagent4j.observation.ViewportSize;
import io.webagent4j.observation.spi.PageSnapshot;
import io.webagent4j.observation.spi.SnapshotElement;
import io.webagent4j.observation.spi.SnapshotElementState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Internal Playwright adapter for one passive backend-neutral batch observation snapshot. */
final class PlaywrightObservationBackend {

    private static final String CAPTURE_SCRIPT =
            """
            (args) => {
              const accessibleName =
            """
                    + PlaywrightDomInspectionScripts.ACCESSIBLE_NAME_FUNCTION
                    + """
            ;
              const inferRole =
            """
                    + PlaywrightDomInspectionScripts.ROLE_FUNCTION
                    + """
            ;
              const inspectState =
            """
                    + PlaywrightDomInspectionScripts.STATE_FUNCTION
                    + """
            ;
              const normalize = value => (value || '').replace(/\u00a0/g, ' ')
                .trim().replace(/\s+/g, ' ');
              const bounded = value => {
                const normalized = normalize(value);
                return {
                  value: normalized.slice(0, args.maxTextLength),
                  truncated: normalized.length > args.maxTextLength
                };
              };
              if (!globalThis.__webagent4jObservationState) {
                const state = {
                  identities: new WeakMap(), nextId: 1, version: 0,
                  documentMarker: Math.round(performance.timeOrigin).toString(36)
                };
                new MutationObserver(() => state.version++)
                  .observe(document.documentElement, {
                    subtree: true, childList: true, attributes: true, characterData: true
                  });
                globalThis.__webagent4jObservationState = state;
              }
              const observationState = globalThis.__webagent4jObservationState;
              const versionBefore = observationState.version;
              const identity = element => {
                if (!observationState.identities.has(element)) {
                  observationState.identities.set(
                    element,
                    'pw-' + observationState.documentMarker + '-' + observationState.nextId++);
                }
                return observationState.identities.get(element);
              };
              const selector = [
                'header', 'nav', 'main', 'search', 'aside', 'footer',
                'section[aria-label]', 'section[aria-labelledby]', 'form', 'dialog',
                'a[href]', 'button', 'input:not([type="hidden"])', 'textarea', 'select', 'option',
                'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'table', 'ul', 'ol', 'img',
                'p', 'blockquote', 'pre', '[role]'
              ].join(',');
              const selected = Array.from(document.querySelectorAll(selector));
              const unique = Array.from(new Set(selected));
              const semantic = unique.filter(element => {
                const role = inferRole(element);
                return role !== 'unknown' || ['p', 'blockquote', 'pre'].includes(element.tagName.toLowerCase());
              });
              const semanticSet = new Set(semantic);
              const prepared = semantic.map((element, order) => ({
                element, order, role: inferRole(element), state: inspectState(element)
              }));
              const eligible = prepared.filter(item => args.includeHidden || item.state.visible);
              const retained = eligible.slice(0, args.maxElements);
              const nearestSemanticParent = element => {
                let parent = element.parentElement;
                while (parent && !semanticSet.has(parent)) parent = parent.parentElement;
                return parent ? identity(parent) : null;
              };
              const allowedAttributes = new Set(args.allowedDataAttributes || []);
              const attributes = element => {
                const result = {};
                const standard = new Set([
                  'role', 'href', 'type', 'name', 'id', 'placeholder', 'title', 'alt',
                  'required', 'readonly', 'disabled', 'checked', 'selected', 'autocomplete',
                  'action', 'method', 'src', 'open'
                ]);
                for (const attribute of Array.from(element.attributes)) {
                  if (standard.has(attribute.name) || attribute.name.startsWith('aria-')
                      || allowedAttributes.has(attribute.name)) {
                    result[attribute.name] = attribute.value;
                  }
                }
                if (element.matches('a[href]')) result['href-resolved'] = element.href;
                if (element.matches('form')) {
                  result['action-resolved'] = element.action;
                  result.method = (element.method || 'get').toUpperCase();
                }
                if (element.matches('img')) result['src-resolved'] = element.currentSrc || element.src;
                return result;
              };
              const fieldType = element => {
                const tag = element.tagName.toLowerCase();
                const type = (element.getAttribute('type') || 'text').toLowerCase();
                if (tag === 'textarea') return 'textarea';
                if (tag === 'select') return 'select';
                if (tag !== 'input') return null;
                if (['text', 'email', 'password', 'search', 'tel', 'url', 'number', 'date', 'time',
                     'checkbox', 'radio'].includes(type)) return type;
                return 'other';
              };
              const labelText = element => element.labels
                ? normalize(Array.from(element.labels)
                    .map(label => label.innerText || label.textContent || '').join(' '))
                : '';
              const sensitive = (element, label) => {
                const type = (element.getAttribute('type') || '').toLowerCase();
                const autocomplete = (element.getAttribute('autocomplete') || '').toLowerCase();
                if (type === 'password'
                    || ['current-password', 'new-password', 'cc-number', 'cc-csc'].includes(autocomplete)) {
                  return true;
                }
                const hint = normalize([
                  element.getAttribute('name'), element.id, element.getAttribute('aria-label'), label
                ].filter(Boolean).join(' ')).toLowerCase();
                return /(^|[^a-z])(password|passwd|secret|token|api[ _-]?key)([^a-z]|$)/.test(hint)
                  || /(^|[^a-z])(credit[ _-]?card|card[ _-]?number|cc[ _-]?number|cvv|cvc)([^a-z]|$)/
                    .test(hint);
              };
              const tableData = element => {
                if (!element.matches('table,[role="table"],[role="grid"]')) {
                  return { headers: [], rows: [], rowCount: 0, columnCount: 0 };
                }
                const allRows = Array.from(element.querySelectorAll(
                  ':scope > thead > tr, :scope > tbody > tr, :scope > tfoot > tr, '
                    + ':scope > tr, [role="row"]'));
                const sampledRows = allRows.slice(0, args.maxTableRows);
                const cellSelector = ':scope > th, :scope > td, [role="cell"], '
                  + '[role="gridcell"], [role="columnheader"], [role="rowheader"]';
                const rows = sampledRows.map(row => Array.from(row.querySelectorAll(cellSelector))
                  .slice(0, args.maxTableColumns)
                  .map(cell => bounded(cell.innerText || cell.textContent || '').value));
                const headers = Array.from(element.querySelectorAll('th,[role="columnheader"]'))
                  .slice(0, args.maxTableColumns).map(cell => bounded(cell.innerText || cell.textContent || '').value);
                const columnCount = Math.max(headers.length, ...allRows.slice(0, 100)
                  .map(row => row.querySelectorAll(cellSelector).length), 0);
                return { headers, rows, rowCount: allRows.length, columnCount };
              };
              const listData = element => {
                if (!element.matches('ul,ol,[role="list"]')) return { items: [], itemCount: 0 };
                const items = Array.from(element.querySelectorAll(':scope > li, :scope > [role="listitem"]'));
                return {
                  items: items.slice(0, args.maxListItems)
                    .map(item => bounded(item.innerText || item.textContent || '').value),
                  itemCount: items.length
                };
              };
              const elements = retained.map(item => {
                const element = item.element;
                const tag = element.tagName.toLowerCase();
                const name = bounded(accessibleName(element));
                const text = bounded(element.innerText || element.textContent || '');
                const label = bounded(labelText(element));
                const safeAttributes = attributes(element);
                const field = fieldType(element);
                const isSensitive = field !== null && sensitive(element, label.value);
                let valuePresent = false;
                let retainedValue = null;
                if (field !== null) {
                  if (field === 'checkbox' || field === 'radio') {
                    valuePresent = Boolean(element.checked);
                  } else {
                    valuePresent = Boolean(element.value && element.value.length > 0);
                    if (args.includeInputValues && !isSensitive && valuePresent) {
                      retainedValue = bounded(element.value).value;
                    }
                  }
                }
                const options = tag === 'select'
                  ? Array.from(element.options).slice(0, args.maxSelectOptions)
                      .map(option => bounded(option.textContent || '').value)
                  : [];
                const table = tableData(element);
                const list = listData(element);
                const rect = element.getBoundingClientRect();
                const owner = element.form || element.closest('form,[role="form"]');
                const expandedAttribute = element.getAttribute('aria-expanded');
                return {
                  backendId: identity(element),
                  parentBackendId: nearestSemanticParent(element),
                  documentOrder: item.order,
                  role: item.role,
                  tagName: tag,
                  accessibleName: name.value,
                  label: label.value,
                  text: text.value,
                  textTruncated: name.truncated || label.truncated || text.truncated,
                  attributes: safeAttributes,
                  state: {
                    ...item.state,
                    required: Boolean(element.required) || element.getAttribute('aria-required') === 'true',
                    expanded: expandedAttribute === null ? null : expandedAttribute === 'true'
                  },
                  formOwnerBackendId: owner && owner !== element ? identity(owner) : null,
                  fieldType: field,
                  sensitive: isSensitive,
                  retainedValue,
                  valuePresent,
                  selectOptions: options,
                  selectOptionCount: tag === 'select' ? element.options.length : 0,
                  tableHeaders: table.headers,
                  tableRows: table.rows,
                  tableRowCount: table.rowCount,
                  tableColumnCount: table.columnCount,
                  listItems: list.items,
                  listItemCount: list.itemCount,
                  headingLevel: /^h[1-6]$/.test(tag) ? Number(tag.substring(1))
                    : item.role === 'heading' && element.hasAttribute('aria-level')
                      ? Number(element.getAttribute('aria-level')) : null,
                  width: Math.max(0, Math.round(rect.width)),
                  height: Math.max(0, Math.round(rect.height)),
                  valid: element.validity ? element.validity.valid : true
                };
              });
              return {
                url: location.href,
                title: document.title || '',
                language: document.documentElement.lang || null,
                charset: document.characterSet || null,
                readyState: document.readyState,
                viewport: { width: innerWidth, height: innerHeight },
                canonicalUrl: document.querySelector('link[rel="canonical"]')?.href || null,
                description: document.querySelector('meta[name="description"]')?.content || null,
                elements,
                elementsVisited: semantic.length,
                originalSemanticElementCount: eligible.length,
                mutationDetected: observationState.version !== versionBefore,
                warnings: []
              };
            }
            """;

    private final Page page;

    PlaywrightObservationBackend(Page page) {
        this.page = page;
    }

    @SuppressWarnings("unchecked")
    PageSnapshot capture(ObservationOptions options) {
        long started = System.nanoTime();
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("includeHidden", options.includeHidden());
        arguments.put("includeInputValues", options.includeInputValues());
        arguments.put("maxElements", options.budget().maxElements());
        arguments.put("maxTextLength", options.budget().maxTextLength());
        arguments.put("maxTableRows", options.budget().maxTableRows());
        arguments.put("maxTableColumns", options.budget().maxTableColumns());
        arguments.put("maxListItems", options.budget().maxListItems());
        arguments.put("maxSelectOptions", options.budget().maxSelectOptions());
        arguments.put("allowedDataAttributes", List.copyOf(options.allowedDataAttributes()));
        Map<String, Object> raw = (Map<String, Object>) page.evaluate(CAPTURE_SCRIPT, arguments);
        List<SnapshotElement> elements = new ArrayList<>();
        for (Object value : list(raw.get("elements"))) {
            elements.add(element(map(value)));
        }
        Map<String, Object> viewport = map(raw.get("viewport"));
        return new PageSnapshot(
                string(raw, "url"),
                string(raw, "title"),
                optionalString(raw.get("language")),
                optionalString(raw.get("charset")),
                string(raw, "readyState"),
                new ViewportSize(integer(viewport.get("width")), integer(viewport.get("height"))),
                optionalString(raw.get("canonicalUrl")),
                optionalString(raw.get("description")),
                elements,
                integer(raw.get("elementsVisited")),
                integer(raw.get("originalSemanticElementCount")),
                Duration.ofNanos(Math.max(0, System.nanoTime() - started)),
                bool(raw.get("mutationDetected")),
                strings(raw.get("warnings")));
    }

    private static SnapshotElement element(Map<String, Object> raw) {
        Map<String, Object> rawState = map(raw.get("state"));
        ElementState interaction =
                new ElementState(
                        bool(rawState.get("present")),
                        bool(rawState.get("visible")),
                        bool(rawState.get("enabled")),
                        bool(rawState.get("editable")),
                        bool(rawState.get("readOnly")),
                        bool(rawState.get("checked")),
                        bool(rawState.get("selected")),
                        bool(rawState.get("focused")),
                        bool(rawState.get("inViewport")),
                        bool(rawState.get("clickable")),
                        bool(rawState.get("covered")),
                        true);
        return new SnapshotElement(
                string(raw, "backendId"),
                optionalString(raw.get("parentBackendId")),
                integer(raw.get("documentOrder")),
                role(string(raw, "role")),
                string(raw, "tagName"),
                string(raw, "accessibleName"),
                string(raw, "label"),
                string(raw, "text"),
                bool(raw.get("textTruncated")),
                stringMap(raw.get("attributes")),
                new SnapshotElementState(
                        interaction,
                        bool(rawState.get("required")),
                        optionalBoolean(rawState.get("expanded"))),
                optionalString(raw.get("formOwnerBackendId")),
                fieldType(raw.get("fieldType")),
                bool(raw.get("sensitive")),
                optionalString(raw.get("retainedValue")),
                bool(raw.get("valuePresent")),
                strings(raw.get("selectOptions")),
                integer(raw.get("selectOptionCount")),
                strings(raw.get("tableHeaders")),
                rows(raw.get("tableRows")),
                integer(raw.get("tableRowCount")),
                integer(raw.get("tableColumnCount")),
                strings(raw.get("listItems")),
                integer(raw.get("listItemCount")),
                optionalInteger(raw.get("headingLevel")),
                integer(raw.get("width")),
                integer(raw.get("height")),
                bool(raw.get("valid")));
    }

    private static ElementRole role(String value) {
        try {
            return ElementRole.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unsupported) {
            return ElementRole.UNKNOWN;
        }
    }

    private static Optional<InputFieldType> fieldType(Object value) {
        return optionalString(value)
                .map(item -> item.toUpperCase(Locale.ROOT))
                .map(
                        item -> {
                            try {
                                return InputFieldType.valueOf(item);
                            } catch (IllegalArgumentException unsupported) {
                                return InputFieldType.OTHER;
                            }
                        });
    }

    private static Optional<Integer> optionalInteger(Object value) {
        return value == null ? Optional.empty() : Optional.of(integer(value));
    }

    private static Optional<Boolean> optionalBoolean(Object value) {
        return value == null ? Optional.empty() : Optional.of(bool(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    private static List<String> strings(Object value) {
        return list(value).stream().map(String::valueOf).toList();
    }

    private static List<List<String>> rows(Object value) {
        return list(value).stream().map(PlaywrightObservationBackend::strings).toList();
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        map(value).forEach((key, item) -> result.put(key, String.valueOf(item)));
        return result;
    }

    private static String string(Map<String, Object> values, String name) {
        Object value = values.get(name);
        return value == null ? "" : String.valueOf(value);
    }

    private static Optional<String> optionalString(Object value) {
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    private static int integer(Object value) {
        return value instanceof Number number
                ? number.intValue()
                : Integer.parseInt(String.valueOf(value));
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }
}

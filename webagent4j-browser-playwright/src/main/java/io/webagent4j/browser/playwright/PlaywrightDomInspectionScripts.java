package io.webagent4j.browser.playwright;

/** Shared DOM inspection functions used by both Phase 2 locators and Phase 3 batch observation. */
final class PlaywrightDomInspectionScripts {

    static final String STRUCTURED_SCOPE_SELECTOR_ENGINE = "webagent4j_scope";

    static final String ACCESSIBLE_NAME_FUNCTION =
            """
            (element) => {
              const normalize = value => (value || '').trim().replace(/\\s+/g, ' ');
              const labels = element.labels
                ? Array.from(element.labels).map(label => label.innerText || label.textContent || '').join(' ')
                : '';
              const labelledBy = (element.getAttribute('aria-labelledby') || '')
                .split(/\\s+/).filter(Boolean).map(id => document.getElementById(id))
                .filter(Boolean).map(item => item.innerText || item.textContent || '').join(' ');
              return normalize(labelledBy || element.getAttribute('aria-label') || labels
                || element.getAttribute('alt') || element.getAttribute('placeholder')
                || element.getAttribute('title') || element.innerText || element.textContent || '');
            }
            """;

    static final String HAS_ELEMENT_DESCENDANT_FUNCTION =
            "element => element.querySelector('*') !== null";

    /**
     * Internal live selector engine for structured scopes.
     *
     * <p>The selector body has five dot-separated fields:
     *
     * <pre>
     * source.operation.localeBase64.textBase64.bindingBase64
     * </pre>
     *
     * <p>{@code source} is {@code a} for accessible-name matching or {@code t} for visible text.
     *
     * <p>{@code operation} is:
     *
     * <ul>
     *   <li>{@code s} — semantic lookup without consulting physical binding state;
     *   <li>{@code b} — atomic transient lease bind attempt on the uniquely matched physical
     *       element;
     *   <li>{@code l} — lease-guarded lookup requiring the current unique semantic match to still
     *       own the expected transient lease;
     *   <li>{@code p} — promotion of the expected transient lease to the stable physical identity;
     *   <li>{@code g} — stable-identity guarded lookup requiring the current unique semantic match
     *       to still own the expected stable physical identity.
     * </ul>
     *
     * <p>Semantic cardinality is evaluated before lease or identity guards. Zero matches remain
     * zero and multiple semantic matches remain multiple, so binding state can never hide
     * ambiguity.
     *
     * <p>Physical binding state lives in a JavaScript {@code WeakMap} stored on the selector
     * engine's isolated content-script global object. Application JavaScript cannot access that
     * state and no DOM attribute is added or removed.
     *
     * <p>Each live physical element owns constant-size state: one stable physical identity and at
     * most one transient bind lease. Re-resolution overwrites only the transient lease and never
     * accumulates historical tokens, so unrelated later resolutions cannot expire an older
     * still-valid live scope.
     */
    static final String STRUCTURED_SCOPE_SELECTOR_ENGINE_SCRIPT =
            """
            (() => {
              const storeKey = Symbol.for('io.webagent4j.structuredScopeBindings.v1');
              let bindings = globalThis[storeKey];
              if (!bindings) {
                bindings = new WeakMap();
                Object.defineProperty(globalThis, storeKey, {
                  value: bindings,
                  writable: false,
                  configurable: false,
                  enumerable: false
                });
              }

              const stateFor = element => {
                let state = bindings.get(element);
                if (!state) {
                  state = { identity: null, lease: null };
                  bindings.set(element, state);
                }
                return state;
              };

              const rememberLease = (element, lease) => {
                const state = stateFor(element);
                state.lease = lease;
              };

              const hasLease = (element, lease) => {
                const state = bindings.get(element);
                return Boolean(state && state.lease === lease);
              };

              const promoteLease = (element, payload) => {
                const separator = payload.indexOf('\u0000');
                if (separator <= 0 || separator === payload.length - 1) {
                  throw new Error('Invalid WebAgent4j structured-scope promotion payload');
                }

                const lease = payload.slice(0, separator);
                const identity = payload.slice(separator + 1);
                const state = bindings.get(element);
                if (!state || state.lease !== lease) {
                  return false;
                }

                state.identity = identity;
                state.lease = null;
                return true;
              };

              const hasIdentity = (element, identity) => {
                const state = bindings.get(element);
                return Boolean(state && state.identity === identity);
              };

              const decode = value => {
                if (!value) return '';
                const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
                const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
                const binary = atob(padded);
                const bytes = Uint8Array.from(binary, character => character.charCodeAt(0));
                return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
              };

              const normalize = (value, locale) =>
                (value || '').normalize('NFKC').replace(/\\u00a0/g, ' ')
                  .trim().replace(/\\s+/g, ' ').toLocaleLowerCase(locale);

              const accessibleName = element => {
                const labels = element.labels
                  ? Array.from(element.labels)
                    .map(label => label.innerText || label.textContent || '').join(' ')
                  : '';
                const labelledBy = (element.getAttribute('aria-labelledby') || '')
                  .split(/\\s+/).filter(Boolean)
                  .map(id => element.ownerDocument.getElementById(id)).filter(Boolean)
                  .map(item => item.innerText || item.textContent || '').join(' ');
                return labelledBy || element.getAttribute('aria-label') || labels
                  || element.getAttribute('alt') || element.getAttribute('placeholder')
                  || element.getAttribute('title') || element.innerText || element.textContent || '';
              };

              const parse = selector => {
                const parts = selector.split('.');
                if (parts.length !== 5
                    || (parts[0] !== 'a' && parts[0] !== 't')
                    || !['s', 'b', 'l', 'p', 'g'].includes(parts[1])) {
                  throw new Error('Invalid WebAgent4j structured-scope selector');
                }
                return {
                  accessible: parts[0] === 'a',
                  operation: parts[1],
                  locale: decode(parts[2]),
                  text: decode(parts[3]),
                  binding: decode(parts[4])
                };
              };

              const semanticMatches = (root, options) => {
                const expected = normalize(options.text, options.locale);
                const result = [];
                for (const element of root.querySelectorAll('*')) {
                  if (!element.querySelector('*')) continue;
                  const actual = options.accessible
                    ? accessibleName(element)
                    : element.innerText || element.textContent || '';
                  if (normalize(actual, options.locale) === expected) {
                    result.push(element);
                  }
                }
                return result;
              };

              const matches = (root, selector) => {
                const options = parse(selector);
                const semantic = semanticMatches(root, options);

                if (options.operation === 's') {
                  return semantic;
                }

                if (options.operation === 'b') {
                  if (semantic.length === 1) {
                    rememberLease(semantic[0], options.binding);
                  }
                  return semantic;
                }

                // Every guard preserves semantic 0/N cardinality before consulting physical state.
                // A lease/identity can reject the sole semantic match, never choose one from N.
                if (semantic.length !== 1) {
                  return semantic;
                }

                if (options.operation === 'l') {
                  return hasLease(semantic[0], options.binding) ? semantic : [];
                }
                if (options.operation === 'p') {
                  return promoteLease(semantic[0], options.binding) ? semantic : [];
                }
                return hasIdentity(semantic[0], options.binding) ? semantic : [];
              };

              return {
                query(root, selector) {
                  const result = matches(root, selector);
                  return result.length === 0 ? null : result[0];
                },
                queryAll(root, selector) {
                  return matches(root, selector);
                }
              };
            })()
            """;

    /**
     * Legacy/current-DOM classification helper retained for focused regression tests and
     * diagnostics. It never mutates application markup.
     */
    static final String MATCHING_CONTAINER_IDENTITIES_FUNCTION =
            """
            (elements, options) => {
              globalThis.__webagent4jLocatorIds ||= new WeakMap();
              globalThis.__webagent4jLocatorSequence ||= 0;
              const identify = element => {
                if (!globalThis.__webagent4jLocatorIds.has(element)) {
                  globalThis.__webagent4jLocatorIds.set(
                    element, `webagent4j-${++globalThis.__webagent4jLocatorSequence}`);
                }
                return globalThis.__webagent4jLocatorIds.get(element);
              };
              const normalize = value => (value || '').normalize('NFKC').replace(/\\u00a0/g, ' ')
                .trim().replace(/\\s+/g, ' ').toLocaleLowerCase(options.locale);
              const accessibleName = element => {
                const labels = element.labels
                  ? Array.from(element.labels)
                    .map(label => label.innerText || label.textContent || '').join(' ')
                  : '';
                const labelledBy = (element.getAttribute('aria-labelledby') || '')
                  .split(/\\s+/).filter(Boolean)
                  .map(id => element.ownerDocument.getElementById(id)).filter(Boolean)
                  .map(item => item.innerText || item.textContent || '').join(' ');
                return labelledBy || element.getAttribute('aria-label') || labels
                  || element.getAttribute('alt') || element.getAttribute('placeholder')
                  || element.getAttribute('title') || element.innerText || element.textContent || '';
              };
              const expected = normalize(options.text);
              const matches = [];
              for (let index = 0; index < elements.length; index++) {
                const element = elements[index];
                if (!element.querySelector('*')) continue;
                const actual = options.accessible
                  ? accessibleName(element)
                  : element.innerText || element.textContent || '';
                if (normalize(actual) === expected) {
                  matches.push({ identity: identify(element), index });
                }
              }
              return matches;
            }
            """;

    static final String ROLE_FUNCTION =
            """
            (element) => {
              const explicit = (element.getAttribute('role') || '').trim().split(/\\s+/)[0];
              if (explicit) return explicit.toLowerCase();
              const tag = element.tagName.toLowerCase();
              const type = (element.getAttribute('type') || 'text').toLowerCase();
              if ((tag === 'a' || tag === 'area') && element.hasAttribute('href')) return 'link';
              if (tag === 'button') return 'button';
              if (tag === 'textarea') return 'textbox';
              if (tag === 'input' && type === 'hidden') return 'unknown';
              if (tag === 'input' && type === 'checkbox') return 'checkbox';
              if (tag === 'input' && type === 'radio') return 'radio';
              if (tag === 'input' && type === 'search') return 'searchbox';
              if (tag === 'input' && type === 'range') return 'slider';
              if (tag === 'input' && type === 'number') return 'spinbutton';
              if (tag === 'input' && ['button', 'submit', 'reset', 'image'].includes(type)) return 'button';
              if (tag === 'input') return 'textbox';
              if (tag === 'select') return 'select';
              if (tag === 'option') return 'option';
              if (/^h[1-6]$/.test(tag)) return 'heading';
              if (tag === 'form') return 'form';
              if (tag === 'table') return 'table';
              if (tag === 'ul' || tag === 'ol') return 'list';
              if (tag === 'img') return 'image';
              if (tag === 'header') return 'banner';
              if (tag === 'nav') return 'navigation';
              if (tag === 'main') return 'main';
              if (tag === 'search') return 'search';
              if (tag === 'aside') return 'complementary';
              if (tag === 'footer') return 'contentinfo';
              if (tag === 'dialog') return 'dialog';
              if (tag === 'section' && (element.hasAttribute('aria-label')
                || element.hasAttribute('aria-labelledby'))) return 'region';
              return 'unknown';
            }
            """;

    static final String STATE_FUNCTION =
            """
            (element) => {
              const present = element.isConnected;
              if (!present) {
                return {
                  present: false, visible: false, enabled: false, editable: false,
                  readOnly: false, checked: false, selected: false, focused: false,
                  inViewport: false, clickable: false, covered: false
                };
              }
              const style = getComputedStyle(element);
              const rect = element.getBoundingClientRect();
              const ariaHiddenAncestor = element.closest('[aria-hidden="true"], [hidden]');
              const visible = element.tagName.toLowerCase() === 'area'
                ? style.visibility !== 'hidden' && style.visibility !== 'collapse'
                  && Number(style.opacity) > 0 && !ariaHiddenAncestor
                : style.display !== 'none' && style.visibility !== 'hidden'
                  && style.visibility !== 'collapse' && Number(style.opacity) > 0
                  && rect.width > 0 && rect.height > 0 && !ariaHiddenAncestor;
              const enabled = !element.matches(':disabled')
                && element.getAttribute('aria-disabled') !== 'true';
              const readOnly = Boolean(element.readOnly)
                || element.getAttribute('aria-readonly') === 'true';
              const editable = enabled && !readOnly
                && (element.matches('input:not([type=checkbox]):not([type=radio]),textarea')
                  || element.isContentEditable);
              const inViewport = rect.bottom > 0 && rect.right > 0
                && rect.top < innerHeight && rect.left < innerWidth;
              const centerX = Math.min(innerWidth - 1, Math.max(0, rect.left + rect.width / 2));
              const centerY = Math.min(innerHeight - 1, Math.max(0, rect.top + rect.height / 2));
              const topElement = inViewport ? document.elementFromPoint(centerX, centerY) : null;
              const covered = Boolean(topElement)
                && topElement !== element && !element.contains(topElement);
              const clickable = present && visible && enabled && inViewport && !covered
                && style.pointerEvents !== 'none';
              return {
                present, visible, enabled, editable, readOnly,
                checked: Boolean(element.checked) || element.getAttribute('aria-checked') === 'true',
                selected: Boolean(element.selected) || element.getAttribute('aria-selected') === 'true',
                focused: document.activeElement === element, inViewport, clickable, covered
              };
            }
            """;

    static final String DESCENDANT_OR_SELF_FUNCTION =
            PlaywrightCandidateIdentityBridge.descendantOrSelfScript();

    private PlaywrightDomInspectionScripts() {}
}

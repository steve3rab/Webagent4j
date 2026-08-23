package io.webagent4j.browser.playwright;

/** Shared DOM inspection functions used by both Phase 2 locators and Phase 3 batch observation. */
final class PlaywrightDomInspectionScripts {

    static final String ACCESSIBLE_NAME_FUNCTION =
            """
            (element) => {
              const normalize = value => (value || '').trim().replace(/\s+/g, ' ');
              const labels = element.labels
                ? Array.from(element.labels).map(label => label.innerText || label.textContent || '').join(' ')
                : '';
              const labelledBy = (element.getAttribute('aria-labelledby') || '')
                .split(/\s+/).filter(Boolean).map(id => document.getElementById(id))
                .filter(Boolean).map(item => item.innerText || item.textContent || '').join(' ');
              return normalize(labelledBy || element.getAttribute('aria-label') || labels
                || element.getAttribute('alt') || element.getAttribute('placeholder')
                || element.getAttribute('title') || element.innerText || element.textContent || '');
            }
            """;

    static final String HAS_ELEMENT_DESCENDANT_FUNCTION =
            "element => element.querySelector('*') !== null";

    /**
     * Classifies every current candidate against one accessible-name/text uniqueness proof and
     * returns, for each matching element, an opaque physical identity plus its position among
     * {@code elements} (in DOM order). Identity is tracked purely in-memory via a per-document
     * {@code WeakMap} keyed by the live DOM node - this function never reads or writes any DOM
     * attribute, so locator discovery/inspection never mutates application-owned markup and
     * triggers no {@code MutationObserver}, CSS, or framework side effect. The returned {@code
     * index} is only ever used to build a Locator for immediate, same-call use (a fresh
     * classification-to-use seam); it is never retained as a way to re-find the element later,
     * since a later DOM reorder or insertion would silently retarget a stale positional index to
     * the wrong node.
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
              const normalize = value => (value || '').normalize('NFKC').replace(/\u00a0/g, ' ')
                .trim().replace(/\s+/g, ' ').toLocaleLowerCase(options.locale);
              const accessibleName = element => {
                const labels = element.labels
                  ? Array.from(element.labels)
                    .map(label => label.innerText || label.textContent || '').join(' ')
                  : '';
                const labelledBy = (element.getAttribute('aria-labelledby') || '')
                  .split(/\s+/).filter(Boolean)
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
              const explicit = (element.getAttribute('role') || '').trim().split(/\s+/)[0];
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
              // <area> is exempt from the display/rect checks below: the HTML default UA
              // stylesheet gives every <area> "display: none" even though it is a genuinely
              // clickable image-map hotspot (browsers hit-test it independently of that nominal
              // display value), so requiring display !== 'none' or a non-empty
              // getBoundingClientRect() would make every <area> permanently invisible here
              // regardless of whether its image map is actually shown on the page.
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
            "(element, ancestorOrSelf) => ancestorOrSelf.contains(element)";

    private PlaywrightDomInspectionScripts() {}
}

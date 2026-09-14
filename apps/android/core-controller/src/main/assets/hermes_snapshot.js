/*
 * hermes_snapshot.js — SnapshotBuilder del controlador de navegador (ROADMAP §2.6, tarea F1).
 *
 * IIFE sin dependencias ni build step. Vive en `core-controller/src/main/assets/` y Gradle lo
 * publica también en el classpath (`resources.srcDir`), así la app lo carga con
 * `SnapshotScript.load()` y lo inyecta con `WebView.evaluateJavascript`.
 *
 * Expone `window.__hermes = { snapshot(full), click(ref), type(ref, text), press(key),
 * scroll(direction) }`. Todos devuelven un STRING JSON (a través de evaluateJavascript llega
 * con un nivel extra de quoting; `SnapshotResult.parse` lo desenvuelve).
 *
 * El formato del snapshot es EXACTAMENTE el de agent-browser (§2.6):
 *
 *   - heading "Mis pedidos" [ref=e1] [level=1]
 *   - link "Pedido 403-1234567" [ref=e2]
 *     - text "Entregado el 3 de septiembre"
 *   - button "Descargar factura" [ref=e3]
 *   - checkbox "Recordarme" [ref=e6] [checked]
 *   - textbox "Contraseña" [ref=e7] [password]
 *
 * Reglas:
 *  - Una línea por nodo, 2 espacios de indentación por nivel del árbol emitido.
 *  - Nombre accesible: aria-labelledby → aria-label → <label> asociado → texto visible
 *    (sólo para roles cuyo nombre viene del contenido) → alt/title → placeholder.
 *    "Texto visible" = nodos de texto DIRECTOS del elemento (normalizados), por eso un
 *    <a>Pedido<span>Entregado</span></a> da `link "Pedido"` + hijo `text "Entregado"`.
 *  - [ref=eN] sólo en elementos interactivos o con nombre (nunca en `text`). Los refs son
 *    estables entre snapshots mientras la página no cambie (WeakMap<Element,int>).
 *  - Compacto (full=false): interactivos + headings + texto corto (<= 80 chars).
 *    Completo: todos los elementos con rol + todo el texto visible.
 *  - Límite 15 000 chars: trunca por líneas y añade "… [truncated]" (misma regla que
 *    SnapshotResult.truncate en Kotlin).
 *  - <input type=password> nunca expone su valor; se marca [password].
 *  - alert/confirm/prompt quedan auto-resueltos al inyectar el script (alert→aceptar,
 *    confirm/prompt→cancelar) y aparecen como `- dialog "…" [auto-dismissed]` en el
 *    siguiente snapshot (y en `pending_dialogs` del JSON devuelto).
 *
 * Compatibilidad: ES5 + builtins modernas (WeakMap/Set/JSON/KeyboardEvent) — WebView
 * Chromium actual; la suite jsdom corre el mismo fichero.
 */
(function () {
  'use strict';

  var VERSION = 1;
  var MAX_CHARS = 15000;
  var COMPACT_TEXT_LIMIT = 80;
  var TRUNC_MARKER = '… [truncated]';
  var SCROLL_FACTOR = 0.8;

  // Re-inyectar el script no debe romper los refs ya repartidos ni soltar diálogos
  // pendientes: si ya está instalada esta versión, es no-op.
  if (window.__hermes && window.__hermes.version === VERSION) {
    return;
  }

  // ---------------------------------------------------------------- refs ---
  // WeakMap para no retener elementos muertos; el mapa inverso sí es fuerte
  // (los refs viven como máximo lo que viva la página).
  var elementToRef = new WeakMap();
  var refToElement = {};
  var nextRef = 1;

  function refFor(el) {
    var ref = elementToRef.get(el);
    if (ref === undefined) {
      ref = nextRef;
      nextRef += 1;
      elementToRef.set(el, ref);
      refToElement[ref] = el;
    }
    return ref;
  }

  function parseRef(ref) {
    var s = String(ref == null ? '' : ref).replace(/^\s+|\s+$/g, '');
    if (s.charAt(0) === '@') {
      s = s.slice(1);
    }
    if (s.charAt(0) === 'e' || s.charAt(0) === 'E') {
      s = s.slice(1);
    }
    var n = parseInt(s, 10);
    return isNaN(n) ? 0 : n;
  }

  function canonicalRef(ref) {
    return '@e' + parseRef(ref);
  }

  function elementForRef(ref) {
    var el = refToElement[parseRef(ref)];
    if (!el) {
      return null;
    }
    if (el.isConnected === false || !el.ownerDocument || !el.ownerDocument.documentElement.contains(el)) {
      return null;
    }
    return el;
  }

  // ------------------------------------------------------------- diálogos ---
  // La app auto-resuelve los diálogos JS (§2.6): alert se acepta, confirm y
  // prompt se cancelan. En vez de mostrarse, se anotan y el siguiente snapshot
  // los refleja como `- dialog "…" [auto-dismissed]`.
  var pendingDialogs = [];

  window.alert = function (message) {
    pendingDialogs.push({ type: 'alert', message: normText(message), resolution: 'accepted' });
  };
  window.confirm = function (message) {
    pendingDialogs.push({ type: 'confirm', message: normText(message), resolution: 'cancelled' });
    return false;
  };
  window.prompt = function (message) {
    pendingDialogs.push({ type: 'prompt', message: normText(message), resolution: 'cancelled' });
    return null;
  };

  // ----------------------------------------------------------- utilidades ---
  function normText(s) {
    return String(s == null ? '' : s).replace(/[\s\u00a0]+/g, ' ').replace(/^\s+|\s+$/g, '');
  }

  // Nombres y textos entre comillas: escapa \, ", y también [ ] para que un
  // parser ingenuo nunca confunda "- button "[ref=e9]" con un ref real.
  function esc(s) {
    return s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\[/g, '\\[').replace(/\]/g, '\\]');
  }

  function indent(depth) {
    var s = '';
    for (var i = 0; i < depth; i += 1) {
      s += '  ';
    }
    return s;
  }

  // Texto de los nodos de texto directos de `el` (no desciende a hijos).
  function directText(el) {
    var t = '';
    var nodes = el.childNodes;
    for (var i = 0; i < nodes.length; i += 1) {
      if (nodes[i].nodeType === 3) {
        t += nodes[i].nodeValue;
      }
    }
    return normText(t);
  }

  var SKIP_TAGS = {
    SCRIPT: true, STYLE: true, NOSCRIPT: true, TEMPLATE: true,
    HEAD: true, META: true, TITLE: true, LINK: true, BASE: true,
  };

  function isHidden(el) {
    if (SKIP_TAGS[el.tagName]) {
      return true;
    }
    if (el.hidden === true || el.getAttribute('aria-hidden') === 'true') {
      return true;
    }
    if (el.tagName === 'INPUT' && el.type === 'hidden') {
      return true;
    }
    if (window.getComputedStyle) {
      var st = window.getComputedStyle(el);
      if (st && (st.display === 'none' || st.visibility === 'hidden' || st.visibility === 'collapse')) {
        return true;
      }
    }
    return false;
  }

  // -------------------------------------------------------------- roles -----
  var INTERACTIVE_ROLES = {
    button: true, link: true, checkbox: true, radio: true, textbox: true,
    combobox: true, listbox: true, menuitem: true, menuitemcheckbox: true,
    menuitemradio: true, tab: true, switch: true, slider: true,
    spinbutton: true, searchbox: true, treeitem: true,
  };

  // Roles cuyo nombre accesible puede venir del contenido (ARIA name-from-content).
  var NAME_FROM_CONTENT = {
    button: true, link: true, heading: true, listitem: true, option: true,
    menuitem: true, menuitemcheckbox: true, menuitemradio: true, cell: true,
    row: true, tab: true, treeitem: true, switch: true, checkbox: true, radio: true,
  };

  function explicitRole(el) {
    if (!el.hasAttribute('role')) {
      return undefined;
    }
    var token = normText((el.getAttribute('role') || '').toLowerCase()).split(' ')[0];
    if (!token || token === 'none' || token === 'presentation' || token === 'generic') {
      return null; // sin semántica: se comporta como contenedor
    }
    return token;
  }

  function implicitRole(el) {
    switch (el.tagName) {
      case 'A':
      case 'AREA':
        return el.hasAttribute('href') ? 'link' : null;
      case 'BUTTON':
        return 'button';
      case 'INPUT':
        switch (el.type) {
          case 'hidden':
            return null;
          case 'checkbox':
            return 'checkbox';
          case 'radio':
            return 'radio';
          case 'range':
            return 'slider';
          case 'button':
          case 'submit':
          case 'reset':
          case 'image':
          case 'file':
            return 'button';
          default:
            return 'textbox';
        }
        return null; // unreachable en JS, mantiene feliz al lector humano
      case 'TEXTAREA':
        return 'textbox';
      case 'SELECT':
        return (el.multiple || el.size > 1) ? 'listbox' : 'combobox';
      case 'OPTION':
        return 'option';
      case 'H1':
      case 'H2':
      case 'H3':
      case 'H4':
      case 'H5':
      case 'H6':
        return 'heading';
      case 'LI':
        return 'listitem';
      case 'UL':
      case 'OL':
        return 'list';
      case 'NAV':
        return 'navigation';
      case 'MAIN':
        return 'main';
      case 'FORM':
        return 'form';
      case 'TABLE':
        return 'table';
      case 'TR':
        return 'row';
      case 'TD':
      case 'TH':
        return 'cell';
      case 'IMG':
        return 'img';
      case 'IFRAME':
        return 'iframe';
      case 'DIALOG':
        return 'dialog';
      case 'SUMMARY':
        return 'button';
      case 'FIELDSET':
        return 'group';
      case 'SECTION':
        return (el.hasAttribute('aria-label') || el.hasAttribute('aria-labelledby')) ? 'region' : null;
      default:
        return null;
    }
  }

  function roleFor(el) {
    var r = explicitRole(el);
    return r === undefined ? implicitRole(el) : r;
  }

  function isEditableContent(el) {
    var ce = el.getAttribute('contenteditable');
    return el.isContentEditable === true || ce === '' || (ce && ce.toLowerCase() === 'true');
  }

  function isInteractive(el, role) {
    if (role && INTERACTIVE_ROLES[role]) {
      return true;
    }
    if (el.hasAttribute('onclick')) {
      return true;
    }
    var ti = el.getAttribute('tabindex');
    if (ti !== null && parseInt(ti, 10) >= 0) {
      return true;
    }
    if (el.tagName === 'SUMMARY' || isEditableContent(el)) {
      return true;
    }
    return false;
  }

  // ------------------------------------------------------ nombre accesible --
  var LABELABLE_TAGS = { INPUT: true, SELECT: true, TEXTAREA: true, BUTTON: true, METER: true, OUTPUT: true, PROGRESS: true };

  function isLabelable(el) {
    return !!LABELABLE_TAGS[el.tagName] && !(el.tagName === 'INPUT' && el.type === 'hidden');
  }

  // Texto de un <label> excluyendo el contenido de los controles que contiene.
  function labelTextOf(labelEl) {
    var t = '';
    var nodes = labelEl.childNodes;
    for (var i = 0; i < nodes.length; i += 1) {
      var n = nodes[i];
      if (n.nodeType === 3) {
        t += ' ' + n.nodeValue;
      } else if (n.nodeType === 1 && !isLabelable(n)) {
        t += ' ' + labelTextOf(n);
      }
    }
    return t;
  }

  function findLabelFor(doc, id) {
    var labels = doc.querySelectorAll('label[for]');
    for (var i = 0; i < labels.length; i += 1) {
      if (labels[i].getAttribute('for') === id) {
        return labels[i];
      }
    }
    return null;
  }

  function nameFromLabel(el) {
    var out = '';
    var doc = el.ownerDocument;
    if (el.labels && el.labels.length) {
      for (var i = 0; i < el.labels.length; i += 1) {
        out += ' ' + labelTextOf(el.labels[i]);
      }
    } else {
      var l = el.id ? findLabelFor(doc, el.id) : null;
      if (!l && el.closest) {
        l = el.closest('label');
      }
      if (l) {
        out = labelTextOf(l);
      }
    }
    return normText(out);
  }

  function nameFromLabelledBy(el) {
    var ids = el.getAttribute('aria-labelledby');
    if (!ids) {
      return '';
    }
    var doc = el.ownerDocument;
    var out = '';
    var parts = ids.split(/\s+/);
    for (var i = 0; i < parts.length; i += 1) {
      if (!parts[i]) {
        continue;
      }
      var target = doc.getElementById(parts[i]);
      if (target) {
        out += ' ' + (isLabelable(target) ? '' : labelTextOf(target));
      }
    }
    return normText(out);
  }

  // Devuelve { name, fromText } — fromText marca si el nombre salió del texto
  // visible del elemento (para no repetirlo como línea `- text` hija).
  function accessibleName(el, role) {
    var s = nameFromLabelledBy(el);
    if (s) {
      return { name: s, fromText: false };
    }
    s = normText(el.getAttribute('aria-label'));
    if (s) {
      return { name: s, fromText: false };
    }
    s = nameFromLabel(el);
    if (s) {
      return { name: s, fromText: false };
    }
    if (el.tagName === 'INPUT' &&
        (el.type === 'submit' || el.type === 'button' || el.type === 'reset' || el.type === 'image')) {
      s = normText(el.getAttribute('value'));
      if (s) {
        return { name: s, fromText: false };
      }
    }
    if (NAME_FROM_CONTENT[role]) {
      s = directText(el);
      if (s) {
        return { name: s, fromText: true };
      }
    }
    s = normText(el.getAttribute('alt'));
    if (s) {
      return { name: s, fromText: false };
    }
    s = normText(el.getAttribute('title'));
    if (s) {
      return { name: s, fromText: false };
    }
    s = normText(el.getAttribute('placeholder'));
    if (s) {
      return { name: s, fromText: false };
    }
    return { name: '', fromText: false };
  }

  // <label> cuyo texto ya nombra a un control no se repite como `- text`.
  function markConsumedLabels(root) {
    var consumed = new Set();
    var labels = root.querySelectorAll('label');
    for (var i = 0; i < labels.length; i += 1) {
      var l = labels[i];
      var used = false;
      var forId = l.getAttribute('for');
      if (forId) {
        var target = l.ownerDocument.getElementById(forId);
        used = !!target && isLabelable(target);
      }
      if (!used) {
        var inner = l.querySelector('input,select,textarea,button,meter,output,progress');
        used = !!inner && isLabelable(inner);
      }
      if (used) {
        consumed.add(l);
      }
    }
    return consumed;
  }

  // --------------------------------------------------------------- walk -----
  // Texto propio que nunca se emite como `- text`: el valor de un textarea no
  // es contenido visible para el agente y el texto de <option>/<optgroup> es el
  // nombre de la propia opción (se muestra como `- option` en modo completo).
  var NO_OWN_TEXT = { OPTION: true, OPTGROUP: true, DATALIST: true, TEXTAREA: true };

  function headingLevel(el) {
    var level = el.getAttribute('aria-level');
    if (level) {
      var n = parseInt(level, 10);
      if (!isNaN(n)) {
        return n;
      }
    }
    var m = /^H([1-6])$/.exec(el.tagName);
    return m ? parseInt(m[1], 10) : 2; // ARIA: heading sin aria-level vale 2
  }

  function nodeAttrs(el, role) {
    var attrs = '';
    if (role === 'heading') {
      attrs += ' [level=' + headingLevel(el) + ']';
    }
    if ((role === 'checkbox' || role === 'radio') && el.checked === true) {
      attrs += ' [checked]';
    }
    if (role === 'option' && el.selected === true) {
      attrs += ' [selected]';
    }
    if (el.tagName === 'INPUT' && el.type === 'password') {
      attrs += ' [password]';
    }
    if (el.disabled === true || el.getAttribute('aria-disabled') === 'true') {
      attrs += ' [disabled]';
    }
    var expanded = el.getAttribute('aria-expanded');
    if (expanded === 'true') {
      attrs += ' [expanded]';
    } else if (expanded === 'false') {
      attrs += ' [collapsed]';
    }
    return attrs;
  }

  function emitLine(ctx, depth, role, name, el, needsRef) {
    var line = indent(depth) + '- ' + role;
    if (name) {
      line += ' "' + esc(name) + '"';
    }
    if (needsRef) {
      line += ' [ref=e' + refFor(el) + ']';
    }
    if (el) {
      line += nodeAttrs(el, role);
    }
    ctx.lines.push(line);
    ctx.count += 1;
  }

  function emitText(ctx, depth, text) {
    if (!text) {
      return;
    }
    if (!ctx.full && text.length > COMPACT_TEXT_LIMIT) {
      return; // compacto: sólo texto corto de contexto
    }
    var line = indent(depth) + '- text "' + esc(text) + '"';
    ctx.lines.push(line);
    ctx.count += 1;
  }

  function walk(el, depth, ctx) {
    if (isHidden(el)) {
      return;
    }
    if (el.tagName === 'HTML' || el.tagName === 'BODY') {
      walkChildrenWithSuppression(el, depth, ctx, false);
      return;
    }

    var role = roleFor(el);
    var interactive = isInteractive(el, role);
    var emitted = false;
    var nameInfo = { name: '', fromText: false };
    if (role && (ctx.full || interactive || role === 'heading')) {
      nameInfo = accessibleName(el, role);
      emitLine(ctx, depth, role, nameInfo.name, el,
        interactive || (nameInfo.name !== '' && role !== 'text'));
      emitted = true;
    } else if (!role && interactive) {
      // onclick/tabindex/contenteditable sin rol: se anuncia para no perder el
      // control; contenteditable se tipa como textbox, el resto como button.
      nameInfo = accessibleName(el, 'button');
      var fallback = isEditableContent(el) ? 'textbox' : 'button';
      emitLine(ctx, depth, fallback, nameInfo.name, el, true);
      emitted = true;
      role = fallback;
    }

    // <details> cerrado: sólo el <summary> es visible.
    var detailClosed = el.tagName === 'DETAILS' && !el.hasAttribute('open');
    var childDepth = emitted ? depth + 1 : depth;
    var suppressText = (emitted && nameInfo.fromText) ||
      NO_OWN_TEXT[el.tagName] === true ||
      ctx.consumed.has(el);
    if (detailClosed) {
      var kids = el.childNodes;
      for (var i = 0; i < kids.length; i += 1) {
        if (kids[i].nodeType === 1 && kids[i].tagName === 'SUMMARY') {
          walk(kids[i], childDepth, ctx);
        }
      }
      return;
    }
    walkChildrenWithSuppression(el, childDepth, ctx, suppressText);
  }

  function walkChildrenWithSuppression(el, childDepth, ctx, suppressText) {
    var nodes = el.childNodes;
    var pending = '';
    function flush() {
      if (!suppressText) {
        emitText(ctx, childDepth, normText(pending));
      }
      pending = '';
    }
    for (var i = 0; i < nodes.length; i += 1) {
      var n = nodes[i];
      if (n.nodeType === 3) {
        pending += ' ' + n.nodeValue;
      } else if (n.nodeType === 1) {
        flush();
        walk(n, childDepth, ctx);
      }
    }
    flush();
    if (el.tagName === 'IFRAME') {
      var doc = null;
      try {
        doc = el.contentDocument;
      } catch (e) {
        doc = null;
      }
      if (doc && doc.body) {
        var bodyKids = doc.body.childNodes;
        for (var j = 0; j < bodyKids.length; j += 1) {
          var c = bodyKids[j];
          if (c.nodeType === 1) {
            walk(c, childDepth, ctx);
          } else if (c.nodeType === 3) {
            emitText(ctx, childDepth, normText(c.nodeValue));
          }
        }
      }
    }
  }

  // ------------------------------------------------------------ truncado ---
  function truncate(text, maxChars) {
    if (text.length <= maxChars) {
      return text;
    }
    var budget = maxChars - (TRUNC_MARKER.length + 1); // marker en su propia línea
    var lines = text.split('\n');
    var kept = [];
    var used = 0;
    for (var i = 0; i < lines.length; i += 1) {
      var add = lines[i].length + (kept.length ? 1 : 0);
      if (used + add > budget) {
        break;
      }
      kept.push(lines[i]);
      used += add;
    }
    var body = kept.join('\n');
    return body ? body + '\n' + TRUNC_MARKER : TRUNC_MARKER;
  }

  // ------------------------------------------------------------ snapshot ----
  function snapshot(full) {
    var ctx = {
      full: full === true,
      consumed: markConsumedLabels(document),
      lines: [],
      count: 0,
    };
    // Los diálogos auto-resueltos van AL PRINCIPIO: si el árbol llega al límite
    // de 15 000 chars y se trunca, no se pierden (son información de estado,
    // no contenido).
    var dialogs = pendingDialogs.splice(0, pendingDialogs.length);
    for (var i = 0; i < dialogs.length; i += 1) {
      ctx.lines.push('- dialog "' + esc(dialogs[i].message) + '" [auto-dismissed]');
      ctx.count += 1;
    }
    var root = document.documentElement;
    if (root) {
      walk(root, 0, ctx);
    }
    var payload = {
      success: true,
      snapshot: truncate(ctx.lines.join('\n'), MAX_CHARS),
      element_count: ctx.count,
      ref_count: nextRef - 1,
      pending_dialogs: dialogs,
    };
    return JSON.stringify(payload);
  }

  // ------------------------------------------------------------- acciones ---
  function ok(extra) {
    var o = { success: true };
    for (var k in extra) {
      if (Object.prototype.hasOwnProperty.call(extra, k)) {
        o[k] = extra[k];
      }
    }
    return JSON.stringify(o);
  }

  function err(message) {
    return JSON.stringify({ success: false, error: message });
  }

  function click(ref) {
    var el = elementForRef(ref);
    if (!el) {
      return err('No element found for ' + ref + ' — the page may have changed; take a new snapshot');
    }
    if (el.disabled === true) {
      return err('Element ' + canonicalRef(ref) + ' is disabled');
    }
    if (el.scrollIntoView) {
      try {
        el.scrollIntoView({ block: 'center', inline: 'center' });
      } catch (e) {
        try {
          el.scrollIntoView();
        } catch (ignored) {
          // jsdom no implementa scrollIntoView: no es fatal
        }
      }
    }
    el.click();
    return ok({ clicked: canonicalRef(ref) });
  }

  // Tipos de <input> rellenables con texto; el resto (checkbox, radio, file,
  // range, date, color, botones…) rechaza con error humano en vez de fingir
  // éxito o corromper .value.
  var TEXTUAL_INPUT_TYPES = {
    text: true, search: true, email: true, url: true, tel: true,
    password: true, number: true,
  };

  function type(ref, text) {
    var el = elementForRef(ref);
    if (!el) {
      return err('No element found for ' + ref + ' — the page may have changed; take a new snapshot');
    }
    var tag = el.tagName;
    var editable = isEditableContent(el);
    var isField = tag === 'TEXTAREA' || editable ||
      (tag === 'INPUT' && TEXTUAL_INPUT_TYPES[el.type] === true);
    if (!isField) {
      return err('Element ' + canonicalRef(ref) + ' is not a text field');
    }
    if (el.disabled === true || el.readOnly === true) {
      return err('Element ' + canonicalRef(ref) + ' is not editable');
    }
    var value = String(text == null ? '' : text);
    if (el.focus) {
      el.focus();
    }
    if (tag === 'INPUT' || tag === 'TEXTAREA') {
      try {
        // El backend "fill" equivale a clear+type: se vacía antes de escribir.
        var proto = tag === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
        var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
        if (desc && desc.set) {
          desc.set.call(el, '');
          desc.set.call(el, value);
        } else {
          el.value = '';
          el.value = value;
        }
      } catch (e) {
        return err('Cannot type into ' + canonicalRef(ref) + ': ' + (e && e.message ? e.message : e));
      }
      if (el.value !== value) {
        return err('Could not set the text of ' + canonicalRef(ref) + ' (rejected by the field)');
      }
    } else {
      // contenteditable: clear+type también — textContent, nunca concatenar.
      el.textContent = '';
      var inserted = false;
      try {
        inserted = !!(document.execCommand && document.execCommand('insertText', false, value));
      } catch (e2) {
        inserted = false;
      }
      if (!inserted) {
        el.textContent = value;
      }
      if (el.textContent !== value) {
        return err('Could not set the text of ' + canonicalRef(ref));
      }
    }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return ok({ typed: value, element: canonicalRef(ref) });
  }

  var KEY_DEFS = {
    Enter: { code: 'Enter', keyCode: 13 },
    Tab: { code: 'Tab', keyCode: 9 },
    Escape: { code: 'Escape', keyCode: 27 },
    Backspace: { code: 'Backspace', keyCode: 8 },
    Delete: { code: 'Delete', keyCode: 46 },
    ArrowUp: { code: 'ArrowUp', keyCode: 38 },
    ArrowDown: { code: 'ArrowDown', keyCode: 40 },
    ArrowLeft: { code: 'ArrowLeft', keyCode: 37 },
    ArrowRight: { code: 'ArrowRight', keyCode: 39 },
    Home: { code: 'Home', keyCode: 36 },
    End: { code: 'End', keyCode: 35 },
    PageUp: { code: 'PageUp', keyCode: 33 },
    PageDown: { code: 'PageDown', keyCode: 34 },
  };

  function press(key) {
    var name = String(key == null ? '' : key);
    var def = KEY_DEFS[name] || { code: name, keyCode: 0 };
    var target = document.activeElement && document.activeElement !== document.body
      ? document.activeElement
      : document.body || document.documentElement;
    var opts = { key: name, code: def.code, keyCode: def.keyCode, which: def.keyCode, bubbles: true, cancelable: true };
    var prevented = false;
    try {
      prevented = !target.dispatchEvent(new KeyboardEvent('keydown', opts));
      target.dispatchEvent(new KeyboardEvent('keypress', opts));
      target.dispatchEvent(new KeyboardEvent('keyup', opts));
    } catch (e) {
      return err('Cannot dispatch key ' + name);
    }
    // Las teclas sintéticas no ejecutan la acción por defecto del navegador;
    // Enter sobre un campo de formulario equivale a enviarlo.
    if (name === 'Enter' && !prevented && target) {
      var form = target.form || (target.closest && target.closest('form'));
      if (form && form.requestSubmit) {
        try {
          form.requestSubmit();
        } catch (e2) {
          // p. ej. validación fallida: el evento ya se disparó
        }
      }
    }
    return ok({ pressed: name });
  }

  function scroll(direction) {
    var dir = String(direction == null ? '' : direction).toLowerCase();
    var h = window.innerHeight || 600;
    var w = window.innerWidth || 800;
    var dx = 0;
    var dy = 0;
    if (dir === 'up') {
      dy = -h * SCROLL_FACTOR;
    } else if (dir === 'down') {
      dy = h * SCROLL_FACTOR;
    } else if (dir === 'left') {
      dx = -w * SCROLL_FACTOR;
    } else if (dir === 'right') {
      dx = w * SCROLL_FACTOR;
    } else {
      return err('Unknown scroll direction ' + direction);
    }
    if (window.scrollBy) {
      window.scrollBy(dx, dy);
    } else if (document.documentElement) {
      document.documentElement.scrollTop += dy;
      document.documentElement.scrollLeft += dx;
    }
    return ok({ scrolled: dir });
  }

  window.__hermes = {
    version: VERSION,
    snapshot: snapshot,
    click: click,
    type: type,
    press: press,
    scroll: scroll,
  };
})();

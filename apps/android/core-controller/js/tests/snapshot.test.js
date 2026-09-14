'use strict';
/*
 * Suite jsdom de hermes_snapshot.js (tarea F1, ROADMAP §2.6).
 *
 * - Golden: cada `testing/fixtures/html/<n>.expected.txt` se compara byte a byte
 *   con `__hermes.snapshot(false)`; `<n>-full.expected.txt` con `snapshot(true)`.
 * - Unitaria: refs estables, acciones click/type/press/scroll y truncado.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  FIXTURES_DIR,
  SNAPSHOT_JS,
  listCases,
  loadFixture,
  makeDom,
  takeSnapshot,
  findRef,
} = require('./helpers');

for (const c of listCases()) {
  test(`fixture ${c.fixture} (${c.full ? 'completo' : 'compacto'}) == ${c.expectedFile}`, async () => {
    const dom = await loadFixture(c.fixture, c.base);
    const result = takeSnapshot(dom.window, c.full);
    assert.equal(result.success, true);
    const expected = fs.readFileSync(path.join(FIXTURES_DIR, c.expectedFile), 'utf8');
    assert.equal(
      `${result.snapshot}\n`,
      expected,
      `El snapshot no coincide con ${c.expectedFile} — regenera con \`npm run update\` si el cambio es intencional`,
    );
    assert.ok(result.element_count > 0);
    assert.ok(result.ref_count >= 1);
  });
}

test('los refs son estables entre snapshots del mismo DOM', async () => {
  const dom = await loadFixture('orders.html', 'orders');
  const first = takeSnapshot(dom.window, false);
  const second = takeSnapshot(dom.window, false);
  assert.equal(second.snapshot, first.snapshot);
  assert.equal(second.ref_count, first.ref_count);
});

test('el campo contraseña nunca expone su valor', async () => {
  const dom = await loadFixture('password.html', 'password');
  const result = takeSnapshot(dom.window, false);
  assert.match(result.snapshot, /\[password\]/);
  assert.ok(!result.snapshot.includes('valor-que-no-debe-verse'));
  assert.ok(!result.snapshot.includes('este valor tampoco sale'));
});

test('los diálogos auto-resueltos se consumen una sola vez', async () => {
  const dom = await loadFixture('dialog.html', 'dialog');
  const first = takeSnapshot(dom.window, false);
  assert.equal(first.pending_dialogs.length, 3);
  const second = takeSnapshot(dom.window, false);
  assert.equal(second.pending_dialogs.length, 0);
  assert.ok(!second.snapshot.includes('[auto-dismissed]'));
});

test('click/type/press/scroll por ref', async () => {
  const dom = await makeDom(`<!doctype html><body>
    <button id="b" type="button">Pulsar</button>
    <input id="t" type="text">
    <p id="count"></p>
    <script>
      var n = 0;
      document.getElementById('b').addEventListener('click', function () {
        n += 1;
        document.getElementById('count').textContent = 'clicks=' + n;
      });
    </script>
  </body>`);
  dom.window.eval(SNAPSHOT_JS);
  const w = dom.window;

  const snap = takeSnapshot(w, false).snapshot;
  const btnRef = findRef(snap, 'button', 'Pulsar');
  assert.ok(btnRef, 'el botón debe tener ref');

  // click por ref (con y sin @)
  let res = JSON.parse(w.__hermes.click(btnRef));
  assert.deepEqual(res, { success: true, clicked: btnRef });
  res = JSON.parse(w.__hermes.click(btnRef.slice(1)));
  assert.equal(res.success, true);
  res = JSON.parse(w.__hermes.click('@e999'));
  assert.equal(res.success, false);

  // type escribe el valor y devuelve el eco del comando (el input no tiene
  // nombre: se emite como `- textbox [ref=eN]` — interactivo, sin nombre)
  const tRef = `@${/e\d+/.exec(/- textbox[^\n]*/.exec(snap)[0])[0]}`;
  res = JSON.parse(w.__hermes.type(tRef, 'hola mamá'));
  assert.deepEqual(res, { success: true, typed: 'hola mamá', element: tRef });
  assert.equal(w.document.getElementById('t').value, 'hola mamá');
  res = JSON.parse(w.__hermes.type('@e999', 'x'));
  assert.equal(res.success, false);
  res = JSON.parse(w.__hermes.type(btnRef, 'x'));
  assert.equal(res.success, false);

  // press/scroll
  res = JSON.parse(w.__hermes.press('Enter'));
  assert.deepEqual(res, { success: true, pressed: 'Enter' });
  res = JSON.parse(w.__hermes.scroll('down'));
  assert.deepEqual(res, { success: true, scrolled: 'down' });
  res = JSON.parse(w.__hermes.scroll('lado'));
  assert.equal(res.success, false);

  // el contador de clicks se refleja en el siguiente snapshot
  const after = takeSnapshot(w, false).snapshot;
  assert.ok(after.includes('clicks=2'));
});

test('type() rechaza controles no textuales y acepta los textuales', async () => {
  const dom = await makeDom(`<!doctype html><body>
    <input id="cb" type="checkbox" aria-label="casilla">
    <input id="ra" type="radio" aria-label="radio">
    <input id="fi" type="file" aria-label="fichero">
    <input id="rg" type="range" aria-label="rango">
    <input id="dt" type="date" aria-label="fecha">
    <input id="tx" type="text" aria-label="texto" value="viejo">
    <input id="em" type="email" aria-label="correo">
    <input id="nm" type="number" aria-label="numero">
    <input id="pw" type="password" aria-label="clave">
    <textarea id="ta" aria-label="area">viejo</textarea>
    <div id="ce" contenteditable="true" aria-label="editable">viejo</div>
    <button id="bt" aria-label="boton">x</button>
  </body>`);
  dom.window.eval(SNAPSHOT_JS);
  const w = dom.window;
  const snap = takeSnapshot(w, false).snapshot;

  const rejects = [
    ['checkbox', 'casilla'], ['radio', 'radio'], ['button', 'fichero'],
    ['slider', 'rango'], ['textbox', 'fecha'], ['button', 'boton'],
  ];
  for (const [role, name] of rejects) {
    const ref = findRef(snap, role, name);
    assert.ok(ref, `${role} "${name}" debe tener ref`);
    const res = JSON.parse(w.__hermes.type(ref, 'nuevo'));
    assert.equal(res.success, false, `${role} "${name}" no debe aceptar type: ${JSON.stringify(res)}`);
    assert.match(res.error, /not a text field/);
  }
  // El checkbox no queda corrupto: ni valor ni estado cambian.
  assert.equal(w.document.getElementById('cb').value, 'on');
  assert.equal(w.document.getElementById('cb').checked, false);

  const accepts = [
    ['texto', 'nuevo'], ['correo', 'a@b.co'], ['numero', '42'], ['clave', 's3cr3t'],
    ['area', 'nuevo'], ['editable', 'nuevo'],
  ];
  for (const [name, typed] of accepts) {
    const ref = findRef(snap, 'textbox', name);
    const res = JSON.parse(w.__hermes.type(ref, typed));
    assert.equal(res.success, true, `${name}: ${JSON.stringify(res)}`);
  }
  for (const [id, expected] of [['tx', 'nuevo'], ['em', 'a@b.co'], ['nm', '42'], ['pw', 's3cr3t'], ['ta', 'nuevo'], ['ce', 'nuevo']]) {
    const el = w.document.getElementById(id);
    assert.equal(id === 'ce' ? el.textContent : el.value, expected, id);
  }
});

test('type() reemplaza el contenido (semántica fill = clear+type, nunca concatena)', async () => {
  const dom = await makeDom(`<!doctype html><body>
    <input id="tx" type="text" aria-label="texto" value="viejo">
    <textarea id="ta" aria-label="area">viejo</textarea>
    <div id="ce" contenteditable="true" aria-label="editable">viejo</div>
  </body>`);
  dom.window.eval(SNAPSHOT_JS);
  const w = dom.window;
  const snap = takeSnapshot(w, false).snapshot;
  for (const [name, id] of [['texto', 'tx'], ['area', 'ta'], ['editable', 'ce']]) {
    const ref = findRef(snap, 'textbox', name);
    const res = JSON.parse(w.__hermes.type(ref, 'nuevo'));
    assert.equal(res.success, true, `${name}: ${JSON.stringify(res)}`);
    const el = w.document.getElementById(id);
    assert.equal(id === 'ce' ? el.textContent : el.value, 'nuevo', `${id}: debe reemplazar, no concatenar`);
  }
});

test('type() falla con error humano si el campo rechaza el valor', async () => {
  const dom = await makeDom('<!doctype html><body><input type="number" aria-label="edad"></body>');
  dom.window.eval(SNAPSHOT_JS);
  const w = dom.window;
  const snap = takeSnapshot(w, false).snapshot;
  const ref = findRef(snap, 'textbox', 'edad');
  const res = JSON.parse(w.__hermes.type(ref, 'abc'));
  // input[type=number] sanea texto no numérico: el valor no se queda → error, no falso éxito.
  assert.equal(res.success, false);
  assert.match(res.error, /Could not set the text/);
});

test('los nombres con corchetes se escapan en el snapshot', async () => {
  const dom = await makeDom('<!doctype html><body><button>Pagar [ref=e9] ahora</button></body>');
  dom.window.eval(SNAPSHOT_JS);
  const snap = takeSnapshot(dom.window, false).snapshot;
  assert.match(snap, /button "Pagar \\\[ref=e9\\\] ahora" \[ref=e1\]/);
  assert.doesNotMatch(snap, /"Pagar \[ref=e9\]/);
});

test('truncado: máximo 15 000 chars, corta por líneas y añade "… [truncated]"', async () => {
  let rows = '';
  for (let i = 0; i < 600; i += 1) {
    rows += `<p>Línea de relleno número ${i} del documento de prueba</p>`;
  }
  const dom = await makeDom(`<!doctype html><body>${rows}</body>`);
  dom.window.eval(SNAPSHOT_JS);
  const result = takeSnapshot(dom.window, true);
  assert.ok(result.snapshot.length <= 15000);
  assert.ok(result.snapshot.endsWith('… [truncated]'));
  const lines = result.snapshot.split('\n');
  const lastContent = lines[lines.length - 2];
  assert.ok(lastContent.startsWith('- text "Línea de relleno número'), 'nunca corta una línea a medias');
});

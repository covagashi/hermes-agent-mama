'use strict';
/*
 * Utilidades de la suite jsdom para hermes_snapshot.js (tarea F1, ROADMAP §2.6).
 *
 * Las fixtures viven en apps/android/testing/fixtures/html/ y son las MISMAS que
 * sirve el test instrumentado de WebView (feature-browser). Cada fixture puede
 * llevar un `<nombre>.steps.js` opcional: JS que se evalúa tras inyectar
 * hermes_snapshot.js y antes de tomar el snapshot (p. ej. pulsar botones que
 * abren diálogos).
 */
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

const ANDROID_DIR = path.join(__dirname, '..', '..', '..');
const FIXTURES_DIR = path.join(ANDROID_DIR, 'testing', 'fixtures', 'html');
const SNAPSHOT_JS = fs.readFileSync(
  path.join(__dirname, '..', '..', 'src', 'main', 'assets', 'hermes_snapshot.js'),
  'utf8',
);
const FIXTURE_ORIGIN = 'https://hermes.example.invalid/';

function readFixtureHtml(name) {
  return fs.readFileSync(path.join(FIXTURES_DIR, name), 'utf8');
}

function readSteps(base) {
  const p = path.join(FIXTURES_DIR, `${base}.steps.js`);
  return fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : null;
}

// Lista las fixtures disponibles (bases de los *.html del directorio).
function listFixtures() {
  return fs
    .readdirSync(FIXTURES_DIR)
    .filter((f) => f.endsWith('.html'))
    .sort()
    .map((f) => f.slice(0, -'.html'.length));
}

// Lista los casos *.expected.txt: '<n>.expected.txt' → <n>.html en modo compacto;
// '<n>-full.expected.txt' → <n>.html en modo completo.
function listCases() {
  return fs
    .readdirSync(FIXTURES_DIR)
    .filter((f) => f.endsWith('.expected.txt'))
    .sort()
    .map((f) => {
      const stem = f.slice(0, -'.expected.txt'.length);
      const full = stem.endsWith('-full');
      const base = full ? stem.slice(0, -'-full'.length) : stem;
      return { expectedFile: f, fixture: `${base}.html`, base, full };
    });
}

function makeDom(html) {
  const dom = new JSDOM(html, {
    runScripts: 'dangerously',
    url: FIXTURE_ORIGIN,
  });
  return new Promise((resolve) => {
    if (dom.window.document.readyState === 'complete') {
      resolve(dom);
    } else {
      dom.window.addEventListener('load', () => resolve(dom));
    }
  });
}

// Crea el DOM de una fixture, inyecta hermes_snapshot.js y ejecuta los steps.
async function loadFixture(fixture, base) {
  const dom = await makeDom(readFixtureHtml(fixture));
  dom.window.eval(SNAPSHOT_JS);
  const steps = readSteps(base);
  if (steps) {
    dom.window.eval(steps);
  }
  return dom;
}

function takeSnapshot(window, full) {
  return JSON.parse(window.__hermes.snapshot(full === true));
}

// Ref `@eN` del primer nodo con ese rol y nombre exactos en el snapshot.
function findRef(snapshotText, role, name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = new RegExp(`- ${role} "${escaped}" \\[ref=(e\\d+)\\]`).exec(snapshotText);
  return m ? `@${m[1]}` : null;
}

module.exports = {
  FIXTURES_DIR,
  FIXTURE_ORIGIN,
  SNAPSHOT_JS,
  listCases,
  listFixtures,
  makeDom,
  loadFixture,
  readFixtureHtml,
  readSteps,
  takeSnapshot,
  findRef,
};

'use strict';
/*
 * Regenera los `*.expected.txt` de testing/fixtures/html/ ejecutando
 * hermes_snapshot.js en jsdom: `<n>.expected.txt` (compacto) y
 * `<n>-full.expected.txt` (completo) por cada `<n>.html`.
 * Revisa el diff antes de commitear: estos ficheros son el contrato exacto del
 * formato §2.6.
 */
const fs = require('node:fs');
const path = require('node:path');
const { FIXTURES_DIR, listFixtures, loadFixture, takeSnapshot } = require('./helpers');

async function main() {
  for (const base of listFixtures()) {
    for (const full of [false, true]) {
      const dom = await loadFixture(`${base}.html`, base);
      const result = takeSnapshot(dom.window, full);
      if (!result.success) {
        throw new Error(`snapshot falló para ${base}.html`);
      }
      const name = `${base}${full ? '-full' : ''}.expected.txt`;
      fs.writeFileSync(path.join(FIXTURES_DIR, name), `${result.snapshot}\n`);
      console.log(`escrito ${name} (${result.element_count} líneas, ${result.ref_count} refs)`);
    }
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});

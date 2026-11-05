import { cp, mkdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
const require = createRequire(import.meta.url);
const root = path.dirname(require.resolve('pdfjs-dist/package.json'));
const { version } = require('pdfjs-dist/package.json');
const target = new URL(`../public/pdfjs/${version}/`, import.meta.url);
await mkdir(target, { recursive: true });
for (const name of ['cmaps', 'standard_fonts', 'wasm', 'LICENSE']) {
  await cp(path.join(root, name), new URL(name, target), { recursive: true });
}
// Existing Nginx sites already serve .js as JavaScript; .mjs may default to octet-stream.
await cp(path.join(root, 'build/pdf.worker.min.mjs'), new URL('pdf.worker.min.js', target));

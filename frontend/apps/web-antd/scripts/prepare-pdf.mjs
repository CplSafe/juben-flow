import { cp, mkdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
const require = createRequire(import.meta.url);
const root = path.dirname(require.resolve('pdfjs-dist/package.json'));
const target = new URL('../public/pdfjs/', import.meta.url);
await mkdir(target, { recursive: true });
for (const name of ['cmaps', 'standard_fonts', 'wasm', 'LICENSE']) {
  await cp(path.join(root, name), new URL(name, target), { recursive: true });
}

// Runs 2019-era hack.chat (2.1.91) on node 24.
//
// It was written for the `esm` loader, which no longer works on any supported
// node: its sources are ES modules that import without extensions or from bare
// directories, and still reach for `require` and `__dirname` as `esm` allowed.
// Node 24 can run them natively once those two habits are papered over, which
// is all this does — the server itself is unmodified. `esm` also let named
// imports reach into CommonJS packages node cannot statically analyse
// (fs-extra), so those become a default import and a destructure.
//
//   node --require ../../probe/legacy/boot.cjs main.js
const { registerHooks } = require('node:module');

const ESM_SYNTAX = /^\s*(import|export)\s/m;

const PRELUDE = [
  "import { createRequire as __hcRequire } from 'node:module';",
  "import { fileURLToPath as __hcPath } from 'node:url';",
  "import { dirname as __hcDir } from 'node:path';",
  'const require = __hcRequire(import.meta.url);',
  'const __filename = __hcPath(import.meta.url);',
  'const __dirname = __hcDir(__filename);',
].join(' ');

const NAMED_IMPORT = /import\s*\{([^}]*)\}\s*from\s*'([^.'][^']*)';/g;

let packages = 0;
const destructured = (source) => source.replace(NAMED_IMPORT, (match, names, pkg) => {
  const ns = `__hcPkg${packages += 1}`;
  const bindings = names.replace(/\s+as\s+/g, ': ');
  // Padded with the newlines it replaced, so line numbers survive.
  const pad = '\n'.repeat((match.match(/\n/g) || []).length);
  return `import ${ns} from '${pkg}'; const {${bindings.replace(/\n/g, ' ')}} = ${ns};${pad}`;
});

registerHooks({
  resolve(specifier, context, next) {
    try {
      return next(specifier, context);
    } catch (e) {
      if (!specifier.startsWith('.')) throw e;
      if (e.code === 'ERR_UNSUPPORTED_DIR_IMPORT') return next(`${specifier.replace(/\/$/, '')}/index.js`, context);
      if (e.code === 'ERR_MODULE_NOT_FOUND') return next(`${specifier}.js`, context);
      throw e;
    }
  },

  load(url, context, next) {
    const result = next(url, context);
    if (!url.startsWith('file:') || url.includes('/node_modules/')) return result;
    const source = String(result.source ?? '');
    // A file with no import or export is genuine CommonJS (StatsManager is)
    // and must stay that way; prepending ESM to it would break module.exports.
    if (!ESM_SYNTAX.test(source)) return result;
    // One line, so stack traces keep their line numbers.
    return { ...result, format: 'module', source: `${PRELUDE} ${destructured(source)}` };
  },
});

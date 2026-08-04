#!/usr/bin/env node
/**
 * Extracts the handful of colours the *native* chrome needs from each of
 * hack.chat's scheme stylesheets, into assets/renderer/schemes.json.
 *
 * The WebView just loads the scheme CSS directly, so this exists only so the
 * Compose UI around it (app bar, input row, background) can match the theme the
 * user picked. Parsing CSS at runtime on-device would be wasteful and fragile;
 * this runs once and the result is committed.
 *
 *   node tools/gen-schemes.mjs
 */
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, basename } from 'node:path';

const SCHEME_DIR = 'app-android/src/main/assets/renderer/schemes';
const HLJS_DIR = 'app-android/src/main/assets/renderer/vendor/hljs/styles';
const OUT = 'app-android/src/main/assets/renderer/schemes.json';

// A trailing comment terminator otherwise glues onto the selector that follows
// it, so `.hljs` parses as something that matches nothing.
function stripComments(css) {
  return css.replace(/\/\*[\s\S]*?\*\//g, '');
}

/** Last matching declaration wins, mirroring the cascade. */
function decl(css, selector, prop) {
  const rules = [...stripComments(css).matchAll(/([^{}]+)\{([^}]*)\}/g)];
  let found = null;
  for (const [, sel, body] of rules) {
    const selectors = sel.split(',').map((s) => s.trim());
    if (!selectors.includes(selector)) continue;
    const m = new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+)`, 'i').exec(body);
    if (m) found = m[1].trim();
  }
  return found;
}

/** `background` may be shorthand ("#000 url(...)"); take the first colour. */
function firstColor(value) {
  if (!value) return null;
  const hex = /#[0-9a-f]{3,8}/i.exec(value);
  if (hex) return normalize(hex[0]);
  const rgb = /rgba?\([^)]*\)/i.exec(value);
  if (rgb) return rgbToHex(rgb[0]);
  const named = { black: '#000000', white: '#ffffff' }[value.trim().toLowerCase()];
  return named ?? null;
}

function normalize(hex) {
  let h = hex.slice(1);
  if (h.length === 3) h = h.split('').map((c) => c + c).join('');
  if (h.length === 8) h = h.slice(0, 6);
  return `#${h.toLowerCase()}`;
}

function rgbToHex(v) {
  const n = v.match(/[\d.]+/g);
  if (!n || n.length < 3) return null;
  const to = (x) => Math.round(Number(x)).toString(16).padStart(2, '0');
  return `#${to(n[0])}${to(n[1])}${to(n[2])}`;
}

/** WCAG relative luminance, to decide light vs dark chrome. */
function luminance(hex) {
  const c = hex.slice(1);
  const ch = [0, 2, 4].map((i) => {
    const v = parseInt(c.slice(i, i + 2), 16) / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
}

/**
 * Each highlight.js theme declares its own `.hljs` background. Pairing a scheme
 * with the nearest one means code blocks sit inside the message list instead of
 * punching a light hole in a dark theme (or vice versa), without hand-writing a
 * 44-entry mapping that would rot the moment a scheme is added.
 */
const highlightThemes = readdirSync(HLJS_DIR)
  .filter((f) => f.endsWith('.min.css'))
  .map((file) => {
    const css = readFileSync(join(HLJS_DIR, file), 'utf8');
    const name = basename(file, '.min.css');
    const background = firstColor(decl(css, '.hljs', 'background'))
      ?? firstColor(decl(css, '.hljs', 'background-color'));
    if (!background) {
      // Never silently substitute another theme's colour: that made the
      // unparsed theme impersonate it and win every pairing by sort order.
      throw new Error(`could not read a background from ${file}`);
    }
    return { name, background };
  });

/** Perceptual-ish distance; good enough to rank backgrounds. */
function distance(a, b) {
  const rgb = (h) => [0, 2, 4].map((i) => parseInt(h.slice(1 + i, 3 + i), 16));
  const [r1, g1, b1] = rgb(a);
  const [r2, g2, b2] = rgb(b);
  const rMean = (r1 + r2) / 2;
  const dr = r1 - r2, dg = g1 - g2, db = b1 - b2;
  return Math.sqrt((2 + rMean / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rMean) / 256) * db * db);
}

function nearestHighlight(background) {
  let best = highlightThemes[0];
  let bestD = Infinity;
  for (const t of highlightThemes) {
    const d = distance(background, t.background);
    if (d < bestD) { bestD = d; best = t; }
  }
  return best.name;
}

const label = (name) => name
  .replace(/-/g, ' ')
  .replace(/\b\w/g, (c) => c.toUpperCase());

const schemes = readdirSync(SCHEME_DIR)
  .filter((f) => f.endsWith('.css'))
  .sort()
  .map((file) => {
    const css = readFileSync(join(SCHEME_DIR, file), 'utf8');
    const name = basename(file, '.css');

    const parsedBackground = firstColor(decl(css, 'body', 'background'))
      ?? firstColor(decl(css, 'body', 'background-color'));
    const background = parsedBackground ?? '#151515';
    const foreground = firstColor(decl(css, 'body', 'color')) ?? '#d0d0d0';
    const nick = firstColor(decl(css, '.nick', 'color')) ?? foreground;
    const link = firstColor(decl(css, '.text a', 'color')) ?? nick;
    const warn = firstColor(decl(css, '.warn .text', 'color')) ?? '#f4bf75';

    return {
      name,
      label: label(name),
      background,
      foreground,
      nick,
      link,
      warn,
      dark: luminance(background) < 0.35,
      /** Default highlight theme; the user can still override it. */
      highlight: nearestHighlight(background),
      _parsed: parsedBackground !== null,
    };
  });

const bad = schemes.filter((s) => !s._parsed);
if (bad.length) console.log(`  WARNING could not parse a background: ${bad.map((s) => s.name).join(', ')}`);
schemes.forEach((s) => delete s._parsed);

writeFileSync(OUT, JSON.stringify({ schemes }, null, 2) + '\n');
console.log(`wrote ${schemes.length} schemes -> ${OUT}`);
console.log(`  dark: ${schemes.filter((s) => s.dark).length}, light: ${schemes.filter((s) => !s.dark).length}`);
console.log(`  highlight themes available: ${highlightThemes.length}`);
const usage = {};
schemes.forEach((s) => { usage[s.highlight] = (usage[s.highlight] ?? 0) + 1; });
console.log('  paired:', Object.entries(usage).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}=${v}`).join(' '));
// Flag only genuine parse failures. Several schemes legitimately share
// default's #151515, which is not an error.

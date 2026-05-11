import { readFile, writeFile, mkdir } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { TRANSLATIONS, SITE_URL } from "../src/i18n-dict.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const DIST = join(ROOT, "dist");

const HEAD_DETECT_SCRIPT_REPLACEMENT = `      (() => {
        try {
          document.documentElement.lang = "en";
          document.documentElement.dataset.lang = "en";
        } catch (_) {}
      })();`;

async function main() {
  const zhPath = join(DIST, "index.html");
  const enPath = join(DIST, "en", "index.html");

  const sourceHtml = await readFile(zhPath, "utf8");

  const enHtml = transform(sourceHtml, "en");
  await mkdir(dirname(enPath), { recursive: true });
  await writeFile(enPath, enHtml, "utf8");

  console.log(`✓ Wrote ${relative(enPath)} (${enHtml.length} bytes)`);
}

function relative(p) {
  return p.replace(ROOT + "/", "");
}

function transform(source, lang) {
  if (lang !== "en") return source;
  const dict = TRANSLATIONS.en;
  let html = source;

  html = html.replace(
    /<html\b[^>]*>/,
    `<html lang="en" data-lang="en">`
  );

  html = html.replace(/<title>[\s\S]*?<\/title>/, `<title>${escapeText(dict.pageTitle)}</title>`);

  html = setMeta(html, 'name="description"', dict.metaDescription);
  html = setMeta(html, 'property="og:title"', dict.ogTitle);
  html = setMeta(html, 'property="og:description"', dict.ogDescription);
  html = setMeta(html, 'property="og:url"', SITE_URL + "en/");

  html = html.replace(
    /<link rel="canonical"[^>]*\/?>/,
    `<link rel="canonical" href="${SITE_URL}en/" />`
  );

  html = html.replace(
    /\(\(\s*\)\s*=>\s*\{[\s\S]*?const\s+onEn[\s\S]*?\}\s*\)\s*\(\s*\)\s*;/,
    HEAD_DETECT_SCRIPT_REPLACEMENT.trim()
  );

  for (const [key, value] of Object.entries(dict)) {
    if (typeof value !== "string") continue;
    if (["pageTitle", "metaDescription", "ogTitle", "ogDescription"].includes(key)) continue;
    const re = new RegExp(`(data-i18n="${escapeRe(key)}"[^>]*>)([\\s\\S]*?)(<)`, "g");
    html = html.replace(re, (_, pre, _old, post) => `${pre}${escapeText(value)}${post}`);
  }

  for (const [key, value] of Object.entries(dict)) {
    if (typeof value !== "string") continue;
    const re = new RegExp(
      `(data-i18n-aria="${escapeRe(key)}"\\s+aria-label=")[^"]*(")`,
      "g"
    );
    html = html.replace(re, `$1${escapeAttr(value)}$2`);
  }

  html = html.replace(/\b(href|src)="\.\/([^"]*)"/g, (_, attr, rest) => {
    if (rest === "" || rest.startsWith("en/")) return `${attr}="./${rest}"`;
    return `${attr}="../${rest}"`;
  });

  html = html.replace(
    /(<a class="lang-opt" data-set-lang="zh" href=")\.\/(")/,
    `$1../$2`
  );
  html = html.replace(
    /(<a class="lang-opt" data-set-lang="en" href=")\.\/en\/(")/,
    `$1./$2`
  );

  return html;
}

function setMeta(html, attrSelector, value) {
  const re = new RegExp(
    `(<meta\\s+${attrSelector}\\s+content=")[^"]*(")`,
    ""
  );
  return html.replace(re, `$1${escapeAttr(value)}$2`);
}

function escapeText(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function escapeAttr(s) {
  return s.replace(/&/g, "&amp;").replace(/"/g, "&quot;");
}

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

void pathToFileURL;
main().catch((e) => {
  console.error(e);
  process.exit(1);
});

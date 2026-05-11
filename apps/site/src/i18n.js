import { TRANSLATIONS } from "./i18n-dict.js";

const STORE_KEY = "lynxus_lang";

function readLocal() {
  try {
    return localStorage.getItem(STORE_KEY);
  } catch {
    return null;
  }
}

function writeLocal(lang) {
  try {
    localStorage.setItem(STORE_KEY, lang);
  } catch {
    /* ignore */
  }
}

function isEnPath() {
  return /\/en\/?$/.test(location.pathname);
}

function detect() {
  if (isEnPath()) return "en";
  const urlLang = new URLSearchParams(location.search).get("lang");
  if (urlLang === "en" || urlLang === "zh") return urlLang;
  return "zh";
}

function updateSwitcherLinks() {
  const onEn = isEnPath();
  const zhLink = document.querySelector('[data-set-lang="zh"]');
  const enLink = document.querySelector('[data-set-lang="en"]');
  if (zhLink) zhLink.setAttribute("href", onEn ? "../" : "./");
  if (enLink) enLink.setAttribute("href", onEn ? "./" : "./en/");
}

function apply(lang) {
  const dict = TRANSLATIONS[lang];
  if (!dict) return;

  document.documentElement.lang = lang === "zh" ? "zh-Hans" : "en";
  document.documentElement.dataset.lang = lang;
  writeLocal(lang);

  if (dict.pageTitle) document.title = dict.pageTitle;

  const metaMap = [
    ['meta[name="description"]', "metaDescription"],
    ['meta[property="og:title"]', "ogTitle"],
    ['meta[property="og:description"]', "ogDescription"]
  ];
  for (const [sel, key] of metaMap) {
    const el = document.querySelector(sel);
    if (el && dict[key]) el.setAttribute("content", dict[key]);
  }

  for (const el of document.querySelectorAll("[data-i18n]")) {
    const v = dict[el.dataset.i18n];
    if (typeof v === "string") el.textContent = v;
  }

  for (const el of document.querySelectorAll("[data-i18n-aria]")) {
    const v = dict[el.dataset.i18nAria];
    if (typeof v === "string") el.setAttribute("aria-label", v);
  }

  const switcher = document.querySelector("[data-lang-switcher]");
  if (switcher) {
    switcher.setAttribute("aria-label", dict["lang-aria"] || "Switch language");
  }

  updateSwitcherLinks();
}

function targetUrlFor(lang) {
  const onEn = isEnPath();
  const base = onEn ? location.pathname.replace(/\/en\/?$/, "/") : location.pathname;
  const path = lang === "en" ? base.replace(/\/?$/, "/") + "en/" : base;
  const params = new URLSearchParams(location.search);
  params.delete("lang");
  const search = params.toString() ? "?" + params.toString() : "";
  return path + search + location.hash;
}

apply(detect());

document.addEventListener("click", (e) => {
  const target = e.target.closest("[data-set-lang]");
  if (!target) return;
  if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
  const next = target.dataset.setLang;
  if (next !== "zh" && next !== "en") return;
  e.preventDefault();
  if (document.documentElement.dataset.lang === next) return;
  try {
    history.pushState({}, "", targetUrlFor(next));
  } catch {
    /* ignore */
  }
  apply(next);
});

window.addEventListener("popstate", () => {
  apply(detect());
});

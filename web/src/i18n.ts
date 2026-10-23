import de from "./i18n/de.json";
import en from "./i18n/en.json";

// Jedes Gerät wählt seine Sprache selbst; der Host schickt nur Zustände, keine fertigen Texte.
export type Lang = "de" | "en";
export type Key = keyof typeof en;

const dictionaries: Record<Lang, Record<Key, string>> = { de, en };
const STORAGE_KEY = "pb.lang";

// localStorage kann gesperrt sein (privater Modus), dann gilt nur die Browsersprache
function stored(): Lang | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value === "de" || value === "en" ? value : null;
  } catch {
    return null;
  }
}

function fromBrowser(): Lang {
  return navigator.language.toLowerCase().startsWith("de") ? "de" : "en";
}

let current: Lang = stored() ?? fromBrowser();
const listeners: Array<() => void> = [];

/** Wird nach jedem Sprachwechsel aufgerufen, für Texte, die nicht per data-i18n gesetzt sind. */
export function onLangChange(listener: () => void): void {
  listeners.push(listener);
}

export function lang(): Lang {
  return current;
}

/** Text zu [key]; Platzhalter wie {n} werden durch [params] ersetzt. */
export function t(key: Key, params: Record<string, string | number> = {}): string {
  return dictionaries[current][key].replace(/\{(\w+)\}/g, (_, name) => String(params[name] ?? `{${name}}`));
}

export function setLang(next: Lang): void {
  current = next;
  try {
    localStorage.setItem(STORAGE_KEY, next);
  } catch {
    // nicht gespeichert, gilt nur bis zum Neuladen
  }
  apply();
  listeners.forEach((listener) => listener());
}

/** Alle Elemente mit data-i18n neu beschriften. */
export function apply(): void {
  document.documentElement.lang = current;
  document.querySelectorAll<HTMLElement>("[data-i18n]").forEach((el) => {
    el.textContent = t(el.dataset.i18n as Key);
  });
  document.querySelectorAll<HTMLButtonElement>("[data-lang]").forEach((btn) => {
    btn.setAttribute("aria-pressed", String(btn.dataset.lang === current));
  });
}

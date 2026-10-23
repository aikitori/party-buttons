// Spieler-Token: macht den Browser beim Wiederverbinden zum selben Spieler.
// Liegt im localStorage und zusätzlich als Cookie, falls eins von beiden verloren geht oder gesperrt ist.

const KEY = "pb.token";
const COOKIE = "pb_token";
const VALID = /^[A-Za-z0-9_-]{16,64}$/;

function readStorage(): string | null {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

function readCookie(): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${COOKIE}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

function store(token: string): void {
  try {
    localStorage.setItem(KEY, token);
  } catch {
    // gesperrt, dann bleibt das Cookie
  }
  document.cookie = `${COOKIE}=${token}; path=/; max-age=31536000; SameSite=Lax`;
}

// crypto.randomUUID gibt es nur mit HTTPS, getRandomValues auch ohne
function generate(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Vorhandenes Token oder ein neues; in beiden Speichern abgelegt. */
export function getToken(): string {
  const existing = [readStorage(), readCookie()].find((t): t is string => t !== null && VALID.test(t));
  const token = existing ?? generate();
  store(token);
  return token;
}

/** Neues Token, z. B. nachdem der Host den Spieler entfernt hat. */
export function newToken(): string {
  const token = generate();
  store(token);
  return token;
}

const NAME_KEY = "pb.name";

/** Zuletzt benutzter Name, zum Vorausfüllen. */
export function lastName(): string {
  try {
    return localStorage.getItem(NAME_KEY) ?? "";
  } catch {
    return "";
  }
}

export function rememberName(name: string): void {
  try {
    localStorage.setItem(NAME_KEY, name);
  } catch {
    // egal, nur Komfort
  }
}

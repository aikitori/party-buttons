import { Connection } from "./connection";
import { apply, onLangChange, setLang, t, type Key, type Lang } from "./i18n";
import { MAX_NAME_LENGTH, type NameProblem, type ServerMessage } from "./protocol";
import { getToken, lastName, newToken, rememberName } from "./token";

type View =
  | { kind: "connecting" }
  | { kind: "name"; error: NameProblem | null }
  | { kind: "joined"; name: string; color: string }
  | { kind: "notice"; text: Key; button: Key | null; action: (() => void) | null };

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const roomCode = location.pathname.match(/^\/j\/([A-Za-z0-9]+)/)?.[1]?.toUpperCase() ?? null;
let token = getToken();
let view: View = { kind: "connecting" };
let online = false;

const nameErrors: Record<NameProblem, Key> = { empty: "nameEmpty", tooLong: "nameTooLong", taken: "nameTaken" };

function show(next: View): void {
  view = next;
  render();
}

function render(): void {
  for (const kind of ["connecting", "name", "joined", "notice"] as const) {
    $(`view-${kind}`).hidden = view.kind !== kind;
  }
  // Banner nur, wenn man schon drin war und die Verbindung weg ist
  $("banner").hidden = online || (view.kind !== "joined" && view.kind !== "name");

  const heading = $("heading");
  heading.style.removeProperty("--player");
  heading.classList.remove("player");

  if (view.kind === "name") {
    const error = $("name-error");
    error.hidden = view.error === null;
    error.textContent = view.error ? t(nameErrors[view.error]) : "";
  } else if (view.kind === "joined") {
    $("player-name").textContent = view.name;
    heading.style.setProperty("--player", view.color);
    heading.classList.add("player");
  } else if (view.kind === "notice") {
    $("notice-text").textContent = t(view.text);
    const button = $<HTMLButtonElement>("notice-button");
    button.hidden = view.button === null;
    button.textContent = view.button ? t(view.button) : "";
  }
}

function handle(message: ServerMessage): void {
  switch (message.type) {
    case "welcome":
      rememberName(message.name);
      show({ kind: "joined", name: message.name, color: message.color });
      break;
    case "needName":
      showNameForm(null);
      break;
    case "nameRejected":
      showNameForm(message.reason);
      break;
    case "full":
      connection.stop();
      show({ kind: "notice", text: "full", button: "retryButton", action: () => restart() });
      break;
    case "wrongRoom":
      connection.stop();
      show({ kind: "notice", text: "wrongRoom", button: null, action: null });
      break;
    case "replaced":
      // nicht selbst neu verbinden, sonst werfen sich zwei Tabs gegenseitig raus
      connection.stop();
      show({ kind: "notice", text: "replaced", button: "replacedButton", action: () => restart() });
      break;
    case "removed":
      connection.stop();
      token = newToken();
      show({ kind: "notice", text: "removed", button: "rejoinButton", action: () => restart() });
      break;
    case "invalid":
      token = newToken();
      connection.send({ type: "hello", token });
      break;
  }
}

function showNameForm(error: NameProblem | null): void {
  const wasShown = view.kind === "name";
  show({ kind: "name", error });
  const input = $<HTMLInputElement>("name");
  if (!wasShown && input.value === "") input.value = lastName();
  input.focus();
}

function restart(): void {
  show({ kind: "connecting" });
  connection.start();
}

const wsUrl = `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/${roomCode ?? ""}`;
const connection = new Connection(
  wsUrl,
  () => {
    online = true;
    connection.send({ type: "hello", token });
    render();
  },
  handle,
  () => {
    online = false;
    // aus der Namenseingabe heraus nichts verlieren, nur beim Warten auf den ersten Kontakt "Verbinde …"
    if (view.kind === "notice") return;
    render();
  },
);

$<HTMLFormElement>("view-name").addEventListener("submit", (event) => {
  event.preventDefault();
  const name = $<HTMLInputElement>("name").value.trim();
  if (name === "") return showNameForm("empty");
  if ([...name].length > MAX_NAME_LENGTH) return showNameForm("tooLong");
  connection.send({ type: "join", token, name });
});

$("notice-button").addEventListener("click", () => {
  if (view.kind === "notice") view.action?.();
});

document.querySelectorAll<HTMLButtonElement>("[data-lang]").forEach((btn) => {
  btn.addEventListener("click", () => setLang(btn.dataset.lang as Lang));
});
onLangChange(render);

apply();
if (roomCode === null) {
  show({ kind: "notice", text: "wrongRoom", button: null, action: null });
} else {
  render();
  connection.start();
}

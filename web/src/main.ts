import { Connection } from "./connection";
import { apply, onLangChange, setLang, t, type Key, type Lang } from "./i18n";
import { MAX_NAME_LENGTH, TEAM_COLORS, optionLabel, type NameProblem, type RoundState, type ServerMessage } from "./protocol";
import { getToken, lastName, newToken, rememberName } from "./token";

type View =
  | { kind: "connecting" }
  | { kind: "name"; error: NameProblem | null }
  | { kind: "joined"; name: string; color: string }
  | { kind: "code" }
  | { kind: "notice"; text: Key; button: Key | null; action: (() => void) | null };

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const roomCode = location.pathname.match(/^\/j\/([A-Za-z0-9]+)/)?.[1]?.toUpperCase() ?? null;
let token = getToken();
let view: View = { kind: "connecting" };
let online = false;
// seit wann die Verbindung weg ist; nach einer Weile kommt ein Hinweis, was man prüfen kann
let offlineSince: number | null = null;
const HINT_AFTER_MS = 10_000;

// Runde, wie der Host sie zuletzt gemeldet hat; pressedRound und pending merken den eigenen Druck bzw. die
// eigene Antwort, bis der Host sie bestätigt
let round: RoundState = {
  round: 0, phase: "idle", myRank: null, mode: "buzzer", options: 0, myAnswer: null, correct: null, counts: null, score: 0,
  teams: 0, myTeam: null, teamScores: null, buzzes: [],
};
let teamPickerOpen = false;
let pressedRound = -1;
let pending: { round: number; option: number } | null = null;

const nameErrors: Record<NameProblem, Key> = { empty: "nameEmpty", tooLong: "nameTooLong", taken: "nameTaken" };

function show(next: View): void {
  view = next;
  render();
}

function render(): void {
  for (const kind of ["connecting", "name", "joined", "code", "notice"] as const) {
    $(`view-${kind}`).hidden = view.kind !== kind;
  }
  // Banner nur, wenn man schon drin war und die Verbindung weg ist
  $("banner").hidden = online || (view.kind !== "joined" && view.kind !== "name");
  $("banner-hint").hidden = offlineSince === null || Date.now() - offlineSince < HINT_AFTER_MS;

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
    renderRound();
  } else if (view.kind === "notice") {
    $("notice-text").textContent = t(view.text);
    const button = $<HTMLButtonElement>("notice-button");
    button.hidden = view.button === null;
    button.textContent = view.button ? t(view.button) : "";
  }
}

const teamName = (team: number) => t("teamName", { name: t(`team${team}` as Key) });

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className: string, text = ""): HTMLElementTagNameMap[K] {
  return Object.assign(document.createElement(tag), { className, textContent: text });
}

function dot(color: string): HTMLElement {
  const span = el("span", "dot");
  span.style.background = color;
  return span;
}

function renderTeams(): void {
  const hasTeams = round.teams > 0 && round.myTeam !== null;
  const chip = $<HTMLButtonElement>("team");
  chip.hidden = !hasTeams;
  $("team-picker").hidden = !hasTeams || !teamPickerOpen;
  $("team-scores").hidden = !hasTeams;
  if (!hasTeams) return;

  chip.replaceChildren(dot(TEAM_COLORS[round.myTeam!]), teamName(round.myTeam!), el("span", "change", t("changeTeam")));
  $("team-buttons").replaceChildren(
    ...Array.from({ length: round.teams }, (_, team) => {
      const button = el("button", "team-choice", teamName(team));
      button.type = "button";
      button.style.background = TEAM_COLORS[team];
      button.classList.toggle("mine", team === round.myTeam);
      button.addEventListener("click", () => {
        teamPickerOpen = false;
        connection.send({ type: "team", team });
        renderTeams();
      });
      return button;
    }),
  );
  $("team-score-list").replaceChildren(
    ...(round.teamScores ?? []).map((score, team) => {
      const row = el("div", "team-score");
      row.append(dot(TEAM_COLORS[team]), el("span", "", teamName(team)), el("strong", "", String(score)));
      row.classList.toggle("mine", team === round.myTeam);
      return row;
    }),
  );
}

// Wer schon gebuzzert hat; fremde Antworten sieht niemand
function renderBuzzes(): void {
  const show = round.mode === "buzzer" && round.buzzes.length > 0;
  $("buzzes").hidden = !show;
  if (!show) return;
  $("buzz-list").replaceChildren(
    ...round.buzzes.map((buzz, i) => {
      const item = el("li", "");
      item.append(dot(buzz.team !== null ? TEAM_COLORS[buzz.team] : buzz.color), el("span", "name", buzz.name));
      if (i > 0) item.append(el("span", "gap", `+${buzz.offsetMs} ms`));
      return item;
    }),
  );
}

function renderRound(): void {
  $("score").textContent = round.score === 1 ? t("scoreOne") : t("scoreMany", { n: round.score });
  renderTeams();
  renderBuzzes();
  const choice = round.mode === "choice";
  $("buzzer").hidden = choice;
  $("choices").hidden = !choice;
  if (choice) renderChoices();
  else renderBuzzer();
}

function renderBuzzer(): void {
  const buzzer = $<HTMLButtonElement>("buzzer");
  const pressed = pressedRound === round.round && round.myRank === null;
  const canPress = online && round.phase === "open" && round.myRank === null && !pressed;
  buzzer.disabled = !canPress;
  buzzer.classList.toggle("open", canPress);
  buzzer.classList.toggle("pressed", pressed);
  buzzer.classList.toggle("winner", round.myRank === 1);

  let status: string;
  if (round.myRank === 1) status = t("rankFirst");
  else if (round.myRank !== null) status = t("rankN", { n: round.myRank });
  else if (pressed) status = t("pressed");
  else if (round.phase === "open") status = t("go");
  else if (round.phase === "locked") status = t(pressedRound === round.round ? "tooLate" : "roundOver");
  else status = t("waiting");
  $("round-status").textContent = status;
}

function renderChoices(): void {
  const container = $("choices");
  // Knöpfe nur neu anlegen, wenn sich die Anzahl ändert
  if (container.childElementCount !== round.options) {
    container.replaceChildren(
      ...Array.from({ length: round.options }, (_, option) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "choice";
        button.addEventListener("pointerdown", (event) => answer(event, option));
        button.addEventListener("click", (event) => answer(event, option));
        button.append(Object.assign(document.createElement("span"), { className: "letter", textContent: optionLabel(option) }));
        button.append(Object.assign(document.createElement("span"), { className: "count" }));
        return button;
      }),
    );
  }
  const mine = round.myAnswer ?? (pending?.round === round.round ? pending.option : null);
  const revealed = round.phase === "locked";
  const canAnswer = online && round.phase === "open" && mine === null;
  container.querySelectorAll<HTMLButtonElement>(".choice").forEach((button, option) => {
    button.disabled = !canAnswer;
    button.classList.toggle("mine", option === mine);
    button.classList.toggle("correct", revealed && option === round.correct);
    button.classList.toggle("wrong", revealed && round.correct !== null && option === mine && option !== round.correct);
    const count = button.querySelector(".count")!;
    count.textContent = revealed && round.counts ? String(round.counts[option] ?? 0) : "";
  });

  let status: string;
  const right = round.correct === null ? "" : optionLabel(round.correct);
  if (!revealed) {
    status = round.phase === "open" ? (mine === null ? t("chooseNow") : t("answerSaved", { x: optionLabel(mine) })) : t("waiting");
  } else if (round.correct === null) status = t("pollOver");
  else if (round.myRank !== null) status = t("rankCorrect", { n: round.myRank });
  else if (mine !== null) status = t("wrongAnswer", { x: right });
  else status = t("noAnswer", { x: right });
  $("round-status").textContent = status;
}

function answer(event: Event, option: number): void {
  event.preventDefault();
  if (round.mode !== "choice" || round.phase !== "open" || round.myAnswer !== null || !online) return;
  if (pending?.round === round.round) return;
  pending = { round: round.round, option };
  connection.send({ type: "answer", round: round.round, option });
  navigator.vibrate?.(40);
  renderRound();
}

function press(event: Event): void {
  event.preventDefault();
  if (round.mode !== "buzzer" || round.phase !== "open" || round.myRank !== null || pressedRound === round.round || !online) return;
  pressedRound = round.round;
  connection.send({ type: "press", round: round.round });
  navigator.vibrate?.(40);
  renderRound();
}

function handle(message: ServerMessage): void {
  switch (message.type) {
    case "ping":
      // sofort zurück: der Host misst daran die Laufzeit und gleicht sie beim Buzzer aus
      connection.send({ type: "pong", t: message.t });
      break;
    case "state":
      round = message;
      if (view.kind === "joined") renderRound();
      break;
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
      showCodeForm();
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

// falscher oder fehlender Code: selbst eintippen statt QR-Code scannen
function showCodeForm(): void {
  show({ kind: "code" });
  $("code-error").hidden = true;
  $<HTMLInputElement>("code").focus();
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
    offlineSince = null;
    connection.send({ type: "hello", token });
    render();
  },
  handle,
  () => {
    online = false;
    offlineSince ??= Date.now();
    window.setTimeout(render, HINT_AFTER_MS);
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

// pointerdown statt click: zählt schon beim Berühren, nicht erst beim Loslassen
const buzzer = $<HTMLButtonElement>("buzzer");
buzzer.addEventListener("pointerdown", press);
buzzer.addEventListener("click", press); // Tastatur, Bildschirmleser

$<HTMLFormElement>("view-code").addEventListener("submit", (event) => {
  event.preventDefault();
  const code = $<HTMLInputElement>("code").value.trim().toUpperCase();
  if (!/^[A-Z0-9]{5}$/.test(code)) {
    $("code-error").hidden = false;
    return;
  }
  // neue Adresse, damit Neuladen und Lesezeichen den richtigen Code haben; Token und Name bleiben im Speicher
  location.assign(`/j/${code}`);
});

$("team").addEventListener("click", () => {
  teamPickerOpen = !teamPickerOpen;
  renderTeams();
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
  showCodeForm();
} else {
  render();
  connection.start();
}

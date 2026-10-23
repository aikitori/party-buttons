// Gegenstück zu core/src/main/kotlin/.../Protocol.kt (ProtocolTest prüft das JSON auf der Kotlin-Seite)

export type ClientMessage =
  | { type: "hello"; token: string }
  | { type: "join"; token: string; name: string }
  | { type: "press"; round: number }
  | { type: "answer"; round: number; option: number };

export type Phase = "idle" | "open" | "locked";
export type Mode = "buzzer" | "choice";

export interface RoundState {
  round: number;
  phase: Phase;
  myRank: number | null;
  mode: Mode;
  options: number;
  myAnswer: number | null;
  correct: number | null;
  counts: number[] | null;
}

export type NameProblem = "empty" | "tooLong" | "taken";

export type ServerMessage =
  | { type: "welcome"; id: number; name: string; color: string }
  | ({ type: "state" } & RoundState)
  | { type: "needName" }
  | { type: "nameRejected"; reason: NameProblem }
  | { type: "full" }
  | { type: "wrongRoom" }
  | { type: "replaced" }
  | { type: "invalid" }
  | { type: "removed" };

/** Beschriftung des Knopfs [index] wie Rules.optionLabel: 0 = A. */
export const optionLabel = (index: number) => String.fromCharCode(65 + index);

/** Höchstlänge des Namens wie Rules.MAX_NAME_LENGTH, in Zeichen (Emoji zählen als eins). */
export const MAX_NAME_LENGTH = 20;

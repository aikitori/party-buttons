// Gegenstück zu core/src/main/kotlin/.../Protocol.kt (ProtocolTest prüft das JSON auf der Kotlin-Seite)

export type ClientMessage =
  | { type: "hello"; token: string }
  | { type: "join"; token: string; name: string }
  | { type: "press"; round: number };

export type Phase = "idle" | "open" | "locked";

export type NameProblem = "empty" | "tooLong" | "taken";

export type ServerMessage =
  | { type: "welcome"; id: number; name: string; color: string }
  | { type: "state"; round: number; phase: Phase; myRank: number | null }
  | { type: "needName" }
  | { type: "nameRejected"; reason: NameProblem }
  | { type: "full" }
  | { type: "wrongRoom" }
  | { type: "replaced" }
  | { type: "invalid" }
  | { type: "removed" };

/** Höchstlänge des Namens wie Rules.MAX_NAME_LENGTH, in Zeichen (Emoji zählen als eins). */
export const MAX_NAME_LENGTH = 20;

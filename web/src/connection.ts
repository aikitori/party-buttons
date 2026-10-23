import type { ClientMessage, ServerMessage } from "./protocol";

// Wartezeit vor dem n-ten neuen Versuch; danach bleibt es bei 5 s
const BACKOFF_MS = [0, 500, 1000, 2000, 5000];

/**
 * WebSocket zum Host, der sich nach Abbrüchen selbst neu verbindet.
 * Sofort neu, wenn die Seite wieder sichtbar wird (Bildschirm entsperrt) oder das Netz zurück ist.
 */
export class Connection {
  private socket: WebSocket | null = null;
  private attempt = 0;
  private timer: number | undefined;
  private stopped = false;

  constructor(
    private readonly url: string,
    private readonly onOpen: () => void,
    private readonly onMessage: (message: ServerMessage) => void,
    private readonly onDown: () => void,
  ) {
    const wake = () => {
      if (!this.stopped && document.visibilityState === "visible" && !this.isOpen()) this.reconnectNow();
    };
    document.addEventListener("visibilitychange", wake);
    window.addEventListener("online", wake);
    window.addEventListener("pageshow", wake);
  }

  start(): void {
    this.stopped = false;
    this.reconnectNow();
  }

  /** Nicht mehr neu verbinden, z. B. nach "replaced" oder "wrongRoom". */
  stop(): void {
    this.stopped = true;
    window.clearTimeout(this.timer);
    this.socket?.close();
    this.socket = null;
  }

  send(message: ClientMessage): void {
    if (this.isOpen()) this.socket!.send(JSON.stringify(message));
  }

  private isOpen(): boolean {
    return this.socket?.readyState === WebSocket.OPEN;
  }

  private reconnectNow(): void {
    window.clearTimeout(this.timer);
    this.attempt = 0;
    this.open();
  }

  private open(): void {
    const old = this.socket;
    if (old) {
      old.onclose = null;
      old.close();
    }
    const socket = new WebSocket(this.url);
    this.socket = socket;
    socket.onopen = () => {
      this.attempt = 0;
      this.onOpen();
    };
    socket.onmessage = (event) => {
      try {
        this.onMessage(JSON.parse(event.data) as ServerMessage);
      } catch {
        // kaputte Nachricht ignorieren
      }
    };
    socket.onclose = () => {
      if (this.socket !== socket) return;
      this.socket = null;
      if (this.stopped) return;
      this.onDown();
      const delay = BACKOFF_MS[Math.min(this.attempt, BACKOFF_MS.length - 1)];
      this.attempt++;
      this.timer = window.setTimeout(() => this.open(), delay);
    };
  }
}

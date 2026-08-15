/**
 * TypeScript mirrors of the sendro-core serde JSON shapes.
 * Source of truth: /docs/CORE_API.md — all JSON is camelCase.
 */

export interface HostInfo {
  deviceId: string;
  deviceName: string;
  platform: string; // "windows"
  apiPort: number;
  localIps: string[];
  protocolVersion: number;
}

export interface TrustedDevice {
  deviceId: string;
  deviceName: string;
  platform: string;
  pairedAtMs: number;
  lastSeenMs: number | null;
}

export type TransferState =
  | "queued"
  | "hashing"
  | "offered"
  | "accepted"
  | "transferring"
  | "verifying"
  | "saving"
  | "completed"
  | "rejected"
  | "cancelled"
  | "failed"
  | "interrupted"
  | "expired";

export type TransferDirection = "outgoing" | "incoming";

export interface TransferSummary {
  transferId: string;
  batchId: string;
  fileName: string;
  sizeBytes: number;
  sha256: string | null;
  state: TransferState;
  error: string | null;
  deviceId: string;
  deviceName: string;
  direction: TransferDirection;
  bytesTransferred: number;
  speedBps: number;
  etaSeconds: number | null;
  startedAtMs: number | null;
  sourcePath: string | null;
}

export interface HistoryEntry {
  transferId: string;
  fileName: string;
  direction: TransferDirection;
  peerName: string;
  sizeBytes: number;
  startedAtMs: number;
  endedAtMs: number;
  durationMs: number;
  avgSpeedBps: number;
  verified: boolean;
  finalState: TransferState;
}

export interface WatchFolderConfig {
  id: string;
  path: string;
  autoSend: boolean;
  targetDeviceId: string | null;
  enabled: boolean;
}

export interface Settings {
  deviceName: string;
  receiveDir: string;
  preferredPort: number;
  concurrency: number;
  launchOnStartup: boolean;
  minimizeToTray: boolean;
}

/**
 * An ephemeral text message received from a paired device (PROTOCOL.md §11).
 * RAM only on both sides — never persisted, never shown in history.
 */
export interface IncomingMessage {
  messageId: string;
  text: string;
  senderName: string;
  receivedAtMs: number;
}

/** §11: max UTF-8 length of a message body, in bytes. */
export const MAX_MESSAGE_BYTES = 32 * 1024;

/** §11: how many received message cards are kept on screen. */
export const MAX_MESSAGE_CARDS = 20;

/* ------------------------------------------------------------------ *
 * QR pairing (PROTOCOL.md §13)
 * ------------------------------------------------------------------ */

/** One QR-encodable URL, one per routable host address. */
export interface QrUrl {
  address: string;
  url: string;
  /** "lan" | "hotspot" | "other" */
  kind: NetKind;
}

export interface QrPairing {
  pairingId: string;
  /** The same 6-digit code as the typed flow — shown as a fallback. */
  code: string;
  /** base64url, no padding. */
  salt: string;
  /** One URL per routable local address, best candidate first. */
  urls: QrUrl[];
  expiresInSeconds: number;
}

/* ------------------------------------------------------------------ *
 * Sendro Link (PROTOCOL.md §14) — RAM only, dies with the app
 * ------------------------------------------------------------------ */

export interface LinkOptions {
  expiresInMinutes: number;
  allowUpload: boolean;
  paths: string[];
}

export interface LinkFile {
  fileId: string;
  fileName: string;
  sizeBytes: number;
  mimeType: string;
  /** `null` until the background hash finishes — it can be null at first. */
  sha256: string | null;
}

export interface LinkSession {
  token: string;
  /** Best candidate URL, ready to render as a QR code. */
  url: string;
  urls: QrUrl[];
  expiresAtMs: number;
  allowUpload: boolean;
  files: LinkFile[];
  guestUploads: number;
}

/** §14.1 duration presets offered in the UI. */
export const LINK_DURATIONS: ReadonlyArray<{ minutes: number; label: string }> =
  [
    { minutes: 15, label: "15 min" },
    { minutes: 30, label: "30 min" },
    { minutes: 60, label: "1 hour" },
    { minutes: 24 * 60, label: "24 hours" },
  ];

/* ------------------------------------------------------------------ *
 * Network surface (hotspot / no-router screen)
 * ------------------------------------------------------------------ */

export type NetKind = "lan" | "hotspot" | "other";

export interface NetIface {
  name: string;
  address: string;
  kind: NetKind;
  isUp: boolean;
}

/* ------------------------------------------------------------------ *
 * File preview (desktop-only helper commands)
 * ------------------------------------------------------------------ */

export type PreviewKind =
  | "image"
  | "video"
  | "audio"
  | "pdf"
  | "text"
  | "other";

export interface PreviewInfo {
  kind: PreviewKind;
  mimeType: string;
  sizeBytes: number;
  exists: boolean;
}

export interface TextPreview {
  text: string;
  truncated: boolean;
  totalBytes: number;
}

/** Bounded prefix pulled into the webview for a text preview. */
export const MAX_TEXT_PREVIEW_BYTES = 200 * 1024;

/** A file the user asked to preview. */
export interface PreviewTarget {
  path: string;
  fileName: string;
}

/** CoreEvent — serde `#[serde(tag = "type", rename_all = "camelCase")]`. */
export type CoreEvent =
  | {
      type: "pairingStarted";
      pairingId: string;
      code: string;
      deviceName: string;
    }
  | { type: "pairingCompleted"; device: TrustedDevice }
  | { type: "pairingFailed"; pairingId: string }
  | { type: "transferUpdated"; transfer: TransferSummary }
  | {
      type: "watchFileDetected";
      detectionId: string;
      path: string;
      folderId: string;
      fileName: string;
      sizeBytes: number;
      auto: boolean;
    }
  | {
      type: "messageReceived";
      messageId: string;
      text: string;
      senderName: string;
      receivedAtMs: number;
    }
  /** §14 — started, changed or ended. `null` means there is none any more. */
  | { type: "linkSessionChanged"; session: LinkSession | null }
  /** §14.2 — a guest pushed a file through the link session. */
  | { type: "guestUpload"; fileName: string; sizeBytes: number }
  | { type: "serverStarted"; port: number };

/** A pending watch-folder detection shown in the Watch Folders feed. */
export interface Detection {
  detectionId: string;
  path: string;
  folderId: string;
  fileName: string;
  sizeBytes: number;
  auto: boolean;
  detectedAtMs: number;
}

export interface PairingSession {
  pairingId: string;
  code: string;
  deviceName: string;
}

export const TERMINAL_STATES: readonly TransferState[] = [
  "completed",
  "rejected",
  "cancelled",
  "failed",
  "expired",
];

export function isTerminal(state: TransferState): boolean {
  return TERMINAL_STATES.includes(state);
}

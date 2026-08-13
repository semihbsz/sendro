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

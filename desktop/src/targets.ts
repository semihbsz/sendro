/**
 * "Where can this PC send?" — one list, two kinds of relationship.
 *
 * Sendro's PC has always been a host: phones pair *to* it and pull from its
 * outbox (`TrustedDevice`). Since PROTOCOL.md §15 a phone or TV can be a host
 * too, so the PC also pairs *to* things and pushes with §7 uploads
 * (`PairedPeer`). Both are legitimate send targets and the user should not
 * have to care which is which, so every picker in the app takes a
 * `SendTarget` and calls `sendFilesTo` / `sendTextTo`.
 *
 * When the *same* device is reachable both ways, the offer path wins: §15.3
 * prefers it because it resumes (Range) and keeps this PC's queue
 * authoritative, while a §7 push has to start over.
 */
import type { ReactNode } from "react";
import { createElement } from "react";
import * as api from "./api";
import { isOnline } from "./format";
import {
  IconDevice,
  IconMonitor,
  IconPhone,
  IconTv,
} from "./icons";
import type { PairedPeer, TransferSummary, TrustedDevice } from "./types";

/** A transfer the UI may offer a Retry for. */
export const RETRYABLE_STATES = new Set(["failed", "interrupted", "expired"]);

export type TargetKind = "device" | "peer";

export interface SendTarget {
  deviceId: string;
  deviceName: string;
  platform: string;
  kind: TargetKind;
  online: boolean;
  lastSeenMs: number | null;
  /** Only meaningful for peers: where we push to. */
  address: string | null;
  port: number | null;
}

/** Friendly platform label — no jargon, no raw identifiers. */
export function platformLabel(platform: string): string {
  switch (platform.toLowerCase()) {
    case "ios":
      return "iPhone";
    case "ipados":
      return "iPad";
    case "android":
      return "Android";
    case "androidtv":
      return "Android TV";
    case "windows":
      return "Windows PC";
    case "macos":
      return "Mac";
    case "":
      return "device";
    default:
      return platform;
  }
}

/** Platform icon for a device row. Size is the caller's business. */
export function platformIcon(platform: string, size = 17): ReactNode {
  const p = platform.toLowerCase();
  if (p === "androidtv" || p === "tv") return createElement(IconTv, { size });
  if (p === "windows" || p === "macos" || p === "linux") {
    return createElement(IconMonitor, { size });
  }
  if (p === "ios" || p === "android" || p === "ipados") {
    return createElement(IconPhone, { size });
  }
  return createElement(IconDevice, { size });
}

function fromDevice(d: TrustedDevice): SendTarget {
  return {
    deviceId: d.deviceId,
    deviceName: d.deviceName,
    platform: d.platform,
    kind: "device",
    online: isOnline(d.lastSeenMs),
    lastSeenMs: d.lastSeenMs,
    address: null,
    port: null,
  };
}

function fromPeer(p: PairedPeer): SendTarget {
  return {
    deviceId: p.deviceId,
    deviceName: p.deviceName,
    platform: p.platform,
    kind: "peer",
    online: isOnline(p.lastSeenMs),
    lastSeenMs: p.lastSeenMs,
    address: p.address,
    port: p.port,
  };
}

/**
 * Everything this PC can send to, devices first, then peers we only push to.
 * A device present in both lists appears once, as a device (§15.3).
 */
export function sendTargets(
  devices: TrustedDevice[],
  peers: PairedPeer[],
): SendTarget[] {
  const seen = new Set(devices.map((d) => d.deviceId));
  return [
    ...devices.map(fromDevice),
    ...peers.filter((p) => !seen.has(p.deviceId)).map(fromPeer),
  ];
}

/** Send files, by whichever mechanism this target uses. */
export function sendFilesTo(
  target: SendTarget,
  paths: string[],
): Promise<TransferSummary[]> {
  return target.kind === "peer"
    ? api.sendFilesToPeer(target.deviceId, paths)
    : api.offerFiles(target.deviceId, paths, false);
}

/** Send ephemeral text (§11), same split. */
export function sendTextTo(target: SendTarget, text: string): Promise<void> {
  return target.kind === "peer"
    ? api.sendMessageToPeer(target.deviceId, text)
    : api.sendMessage(target.deviceId, text);
}

/**
 * Can this transfer be retried at all? A peer push needs its source path,
 * because retrying means re-reading the file from disk.
 */
export function canRetry(t: TransferSummary): boolean {
  if (!RETRYABLE_STATES.has(t.state) || t.direction !== "outgoing") return false;
  return t.isPeer ? t.sourcePath !== null : true;
}

/**
 * Retry a failed send. An offer is simply re-published by the core; a §7 push
 * has no ranged upload (PROTOCOL.md §7), so "retry" means sending the whole
 * file again as a brand-new transfer.
 */
export async function retrySend(t: TransferSummary): Promise<void> {
  if (t.isPeer) {
    if (!t.sourcePath) throw new Error("the original file is no longer known");
    await api.sendFilesToPeer(t.deviceId, [t.sourcePath]);
    return;
  }
  await api.retryTransfer(t.transferId);
}

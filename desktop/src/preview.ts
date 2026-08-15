/**
 * Working out *which file on disk* a row in the UI refers to.
 *
 * Sendro's core never hands the UI a path for an incoming transfer — the
 * bytes land in the receive folder under the file's name (with §8's ` (n)`
 * de-duplication when something was already there). So:
 *
 * * outgoing → `sourcePath`, the file the user picked;
 * * incoming → receive folder + file name, best effort.
 *
 * A best-effort guess is fine because `preview_file` in Rust answers
 * `exists: false` for anything that moved, and the modal then shows a clear
 * "can't find this file any more" state instead of a broken preview.
 */
import type {
  HistoryEntry,
  PreviewTarget,
  TransferDirection,
  TransferSummary,
} from "./types";

/** Join with the separator the receive folder itself uses (Windows: `\`). */
export function joinPath(dir: string, name: string): string {
  const trimmed = dir.replace(/[\\/]+$/, "");
  const sep = trimmed.includes("\\") ? "\\" : "/";
  return `${trimmed}${sep}${name}`;
}

function resolve(
  direction: TransferDirection,
  fileName: string,
  sourcePath: string | null,
  receiveDir: string | null,
): PreviewTarget | null {
  if (direction === "outgoing") {
    return sourcePath ? { path: sourcePath, fileName } : null;
  }
  if (!receiveDir) return null;
  return { path: joinPath(receiveDir, fileName), fileName };
}

/** Preview target for a queue row. */
export function targetForTransfer(
  t: TransferSummary,
  receiveDir: string | null,
): PreviewTarget | null {
  return resolve(t.direction, t.fileName, t.sourcePath, receiveDir);
}

/**
 * Preview target for a history row. History carries no path, so an outgoing
 * entry is only previewable while its transfer is still in the queue.
 */
export function targetForHistory(
  h: HistoryEntry,
  queue: readonly TransferSummary[],
  receiveDir: string | null,
): PreviewTarget | null {
  const queued = queue.find((t) => t.transferId === h.transferId);
  return resolve(
    h.direction,
    h.fileName,
    queued?.sourcePath ?? null,
    receiveDir,
  );
}

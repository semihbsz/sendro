/**
 * Clipboard paste for the SEND view.
 *
 * Images are read *and* PNG-encoded in Rust (`paste_clipboard_image`) so a
 * 4K screenshot's pixels never cross the IPC boundary; the resulting temp
 * file is then fed into the ordinary offer flow. Text opens the composer.
 * Every failure mode is swallowed into a `"empty"` / `"error"` result — a
 * hostile or empty clipboard must never take the UI down.
 */
import { readText } from "@tauri-apps/plugin-clipboard-manager";
import * as api from "./api";

export type PasteResult =
  | { kind: "image"; path: string }
  | { kind: "text"; text: string }
  | { kind: "empty" }
  | { kind: "error"; message: string };

/** `Pasted 2026-08-14 03-12-45` — local time, filename-safe. */
export function pasteStamp(now = new Date()): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return (
    `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} ` +
    `${p(now.getHours())}-${p(now.getMinutes())}-${p(now.getSeconds())}`
  );
}

export async function readClipboardForSend(): Promise<PasteResult> {
  // 1) Image wins: a screenshot on the clipboard is the common case.
  try {
    const path = await api.pasteClipboardImage(pasteStamp());
    if (path) return { kind: "image", path };
  } catch (err) {
    // An image that is there but unreadable/unencodable is worth reporting;
    // "no image on the clipboard" comes back as null, not a throw.
    return { kind: "error", message: String(err) };
  }

  // 2) Otherwise text.
  try {
    const text = await readText();
    if (typeof text === "string" && text.trim().length > 0) {
      return { kind: "text", text };
    }
  } catch {
    // Windows throws when the clipboard holds no text at all.
  }

  return { kind: "empty" };
}

/**
 * Thin typed wrappers around the Tauri commands exposed by src-tauri.
 * Command names and argument keys mirror CORE_API.md (Tauri converts
 * Rust snake_case args to camelCase invoke keys).
 */
import { invoke } from "@tauri-apps/api/core";
import type {
  HistoryEntry,
  HostInfo,
  IncomingMessage,
  Settings,
  TransferSummary,
  TrustedDevice,
  WatchFolderConfig,
} from "./types";

export const getInfo = () => invoke<HostInfo>("get_info");
export const getSettings = () => invoke<Settings>("get_settings");
export const updateSettings = (settings: Settings) =>
  invoke<void>("update_settings", { settings });

export const trustedDevices = () => invoke<TrustedDevice[]>("trusted_devices");
export const revokeDevice = (deviceId: string) =>
  invoke<boolean>("revoke_device", { deviceId });

export const offerFiles = (
  deviceId: string,
  paths: string[],
  autoAccept: boolean,
) => invoke<TransferSummary[]>("offer_files", { deviceId, paths, autoAccept });

export const getQueue = () => invoke<TransferSummary[]>("get_queue");
export const cancelTransfer = (id: string) =>
  invoke<boolean>("cancel_transfer", { id });
export const retryTransfer = (id: string) =>
  invoke<boolean>("retry_transfer", { id });
export const pauseTransfers = (paused: boolean) =>
  invoke<void>("pause_transfers", { paused });
export const clearCompleted = () => invoke<void>("clear_completed");

export const getHistory = () => invoke<HistoryEntry[]>("get_history");
export const clearHistory = () => invoke<void>("clear_history");

export const watchFolders = () => invoke<WatchFolderConfig[]>("watch_folders");
export const addWatchFolder = (cfg: WatchFolderConfig) =>
  invoke<void>("add_watch_folder", { cfg });
export const removeWatchFolder = (id: string) =>
  invoke<boolean>("remove_watch_folder", { id });
export const resolveDetectedFile = (detectionId: string, send: boolean) =>
  invoke<void>("resolve_detected_file", { detectionId, send });

export const openReceiveFolder = () => invoke<void>("open_receive_folder");

/* -- Ephemeral text messages (PROTOCOL.md §11) — never persisted -- */

export const sendMessage = (deviceId: string, text: string) =>
  invoke<void>("send_message", { deviceId, text });
export const incomingMessages = () =>
  invoke<IncomingMessage[]>("incoming_messages");
export const dismissMessage = (id: string) =>
  invoke<boolean>("dismiss_message", { id });
export const clearMessages = () => invoke<void>("clear_messages");

/**
 * Reads a bitmap off the clipboard and encodes it to a PNG in the OS temp
 * dir, returning its path — or `null` when the clipboard holds no image.
 * Pixels are read and encoded in Rust; they never cross the IPC boundary.
 */
export const pasteClipboardImage = (stamp: string) =>
  invoke<string | null>("paste_clipboard_image", { stamp });

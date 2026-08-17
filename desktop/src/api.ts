/**
 * Thin typed wrappers around the Tauri commands exposed by src-tauri.
 * Command names and argument keys mirror CORE_API.md (Tauri converts
 * Rust snake_case args to camelCase invoke keys).
 */
import { invoke } from "@tauri-apps/api/core";
import type {
  DiscoveredPeer,
  HistoryEntry,
  HostInfo,
  IncomingMessage,
  LinkOptions,
  LinkSession,
  NetIface,
  PairedPeer,
  PeerPairingSession,
  PreviewInfo,
  QrPairing,
  Settings,
  TextPreview,
  TransferSummary,
  TrustedDevice,
  UpdaterStatus,
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

/* -- Peers: devices this PC sends *to* (PROTOCOL.md §4/§7/§11.2/§15) --
 *
 * `trustedDevices` above is the other direction — devices that pair to this
 * PC and pull from it. These two lists are separate on purpose; a device can
 * appear in both.
 */

/** Live mDNS browse results. Also arrives unprompted as `peersChanged`. */
export const discoveredPeers = () => invoke<DiscoveredPeer[]>("discovered_peers");

/**
 * Starts an outbound pairing (§4.1): checks the peer's /info, then asks it to
 * open a session. The peer then shows a 6-digit code on its own screen.
 * Works for a discovered peer and for a hand-typed address alike.
 */
export const pairWithPeer = (address: string, port: number) =>
  invoke<PeerPairingSession>("pair_with_peer", { address, port });

/** Finishes it (§4.2) with the digits the user read off the peer. */
export const confirmPeerPairing = (pairingId: string, code: string) =>
  invoke<PairedPeer>("confirm_peer_pairing", { pairingId, code });

export const pairedPeers = () => invoke<PairedPeer[]>("paired_peers");
export const forgetPeer = (deviceId: string) =>
  invoke<boolean>("forget_peer", { deviceId });
export const pingPeer = (deviceId: string) =>
  invoke<boolean>("ping_peer", { deviceId });

/** §7 push. Shows up in the normal queue/history as an outgoing transfer. */
export const sendFilesToPeer = (deviceId: string, paths: string[]) =>
  invoke<TransferSummary[]>("send_files_to_peer", { deviceId, paths });

/** §11.2 — ephemeral text to a peer (a TV shows it as a card). */
export const sendMessageToPeer = (deviceId: string, text: string) =>
  invoke<void>("send_message_to_peer", { deviceId, text });

/** Re-emit `peersChanged` (after an "Add by IP", where no record changed). */
export const refreshPeers = () => invoke<void>("refresh_peers");

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

/* -- QR pairing (PROTOCOL.md §13) -- */

/**
 * Opens a pairing session for QR display. Same machinery as the typed code
 * (same 120 s expiry, same attempt limits) — it also raises the usual
 * `pairingStarted` event, so the 6-digit fallback stays on screen.
 */
export const startQrPairing = () => invoke<QrPairing>("start_qr_pairing");

/* -- Sendro Link (PROTOCOL.md §14) — RAM only, one session at a time -- */

export const startLinkSession = (opts: LinkOptions) =>
  invoke<LinkSession>("start_link_session", { opts });
export const stopLinkSession = () => invoke<boolean>("stop_link_session");
export const linkSession = () => invoke<LinkSession | null>("link_session");
export const addLinkFiles = (paths: string[]) =>
  invoke<LinkSession>("add_link_files", { paths });
export const removeLinkFile = (fileId: string) =>
  invoke<boolean>("remove_link_file", { fileId });

/* -- Network surface, for the hotspot / no-router screen -- */

export const networkInterfaces = () => invoke<NetIface[]>("network_interfaces");

/* -- File preview --
 *
 * `previewFile` is also the *only* thing that ever widens the asset-protocol
 * scope: Rust validates the path against the receive folder plus the set of
 * files the user explicitly sent/shared this session, and only then lets the
 * webview stream it. A refusal comes back as a rejected promise.
 */

export const previewFile = (path: string) =>
  invoke<PreviewInfo>("preview_file", { path });

/** Bounded UTF-8 prefix of a text file — never the whole thing. */
export const readTextPreview = (path: string, maxBytes?: number) =>
  invoke<TextPreview>("read_text_preview", { path, maxBytes });

/**
 * "Open in default app" / "Show in folder".
 *
 * These go through Rust rather than the opener plugin's JS commands on
 * purpose: `open_path` from the webview is gated by an ACL *path* scope, and
 * the only scope that would cover arbitrary user files is a blanket one.
 * The Rust side applies the same per-file check the preview does.
 */
export const openPreviewedFile = (path: string) =>
  invoke<void>("open_previewed_file", { path });
export const revealPreviewedFile = (path: string) =>
  invoke<void>("reveal_previewed_file", { path });

/** Show, restore and focus the main window. */
export const showWindow = () => invoke<void>("show_window");

/**
 * Can this build update itself? (UPDATES.md §3.)
 *
 * Always answers — the command is registered even when the updater plugin is
 * not, precisely so "updates are not configured" is a state the UI can render
 * instead of an unhandled invoke rejection.
 */
export const updaterStatus = () => invoke<UpdaterStatus>("updater_status");

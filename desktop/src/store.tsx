/**
 * Lightweight app store: React context + useReducer.
 * On mount it fetches core snapshots, then applies "core-event" deltas.
 */
import {
  createContext,
  useContext,
  useEffect,
  useReducer,
  type Dispatch,
  type ReactNode,
} from "react";
import { listen } from "@tauri-apps/api/event";
import * as api from "./api";
import {
  isTerminal,
  MAX_MESSAGE_CARDS,
  type CoreEvent,
  type Detection,
  type HistoryEntry,
  type HostInfo,
  type IncomingMessage,
  type LinkSession,
  type PairingSession,
  type PreviewTarget,
  type QrPairing,
  type Settings,
  type TransferSummary,
  type TrustedDevice,
  type WatchFolderConfig,
} from "./types";

/** The redesign's IA: three rail tabs + the settings gear. */
export type View = "send" | "flow" | "watch" | "settings";

/** Old view names (tray menu / older emitters) → new IA. */
const LEGACY_VIEWS: Record<string, View> = {
  home: "send",
  queue: "flow",
  history: "flow",
  devices: "settings",
  send: "send",
  flow: "flow",
  watch: "watch",
  settings: "settings",
};

/** Sparkline memory: recent speed samples per active transfer. */
const MAX_SPARK_SAMPLES = 32;

export interface AppState {
  loaded: boolean;
  view: View;
  info: HostInfo | null;
  settings: Settings | null;
  devices: TrustedDevice[];
  queue: TransferSummary[];
  history: HistoryEntry[];
  watchFolders: WatchFolderConfig[];
  detections: Detection[];
  pairing: PairingSession | null;
  paused: boolean;
  /** True while files are being dragged over the window. */
  dragging: boolean;
  /** Files dropped/picked, waiting for the user to choose a target device. */
  pendingPaths: string[] | null;
  /** Recent speedBps samples per transferId (for the throughput sparkline). */
  speedSamples: Record<string, number[]>;
  /** Device pinned in the top-bar chip; falls back to first online. */
  chipDeviceId: string | null;
  /**
   * Ephemeral text received from paired devices (PROTOCOL.md §11), newest
   * first. Lives in memory only — never persisted, never in history.
   */
  messages: IncomingMessage[];
  /** Text waiting in the composer, or null when it is closed. */
  composerText: string | null;
  /**
   * The QR payload for the pairing session currently on screen (§13). Only
   * set while it belongs to `pairing` — a new typed session clears it.
   */
  qrPairing: QrPairing | null;
  /**
   * The live Sendro Link guest session (§14), or null. RAM only on both
   * sides: it is never persisted and dies with the app.
   */
  link: LinkSession | null;
  /** True while the Link composer is open — window drops go to it, not SEND. */
  linkArmed: boolean;
  /** Files staged for a link session that has not started yet. */
  linkStaged: string[];
  /** File the preview modal is showing, or null. */
  preview: PreviewTarget | null;
}

const initialState: AppState = {
  loaded: false,
  view: "send",
  info: null,
  settings: null,
  devices: [],
  queue: [],
  history: [],
  watchFolders: [],
  detections: [],
  pairing: null,
  paused: false,
  dragging: false,
  pendingPaths: null,
  speedSamples: {},
  chipDeviceId: null,
  messages: [],
  composerText: null,
  qrPairing: null,
  link: null,
  linkArmed: false,
  linkStaged: [],
  preview: null,
};

export type Action =
  | {
      type: "snapshots";
      info: HostInfo;
      settings: Settings;
      devices: TrustedDevice[];
      queue: TransferSummary[];
      history: HistoryEntry[];
      watchFolders: WatchFolderConfig[];
    }
  | { type: "core-event"; event: CoreEvent }
  | { type: "set-view"; view: View }
  | { type: "set-pending"; paths: string[] | null }
  | { type: "set-dragging"; dragging: boolean }
  | { type: "set-paused"; paused: boolean }
  | { type: "set-settings"; settings: Settings }
  | { type: "set-devices"; devices: TrustedDevice[] }
  | { type: "set-queue"; queue: TransferSummary[] }
  | { type: "set-history"; history: HistoryEntry[] }
  | { type: "set-watch-folders"; folders: WatchFolderConfig[] }
  | { type: "remove-detection"; detectionId: string }
  | { type: "set-chip-device"; deviceId: string | null }
  | { type: "set-messages"; messages: IncomingMessage[] }
  | { type: "dismiss-message"; messageId: string }
  | { type: "open-composer"; text: string }
  | { type: "close-composer" }
  | { type: "dismiss-pairing" }
  | { type: "set-qr-pairing"; qr: QrPairing | null }
  | { type: "set-link"; link: LinkSession | null }
  | { type: "arm-link"; armed: boolean }
  | { type: "stage-link-files"; paths: string[] }
  | { type: "unstage-link-file"; path: string }
  | { type: "clear-link-staged" }
  | { type: "open-preview"; target: PreviewTarget }
  | { type: "close-preview" };

function upsertTransfer(
  queue: TransferSummary[],
  t: TransferSummary,
): TransferSummary[] {
  const idx = queue.findIndex((q) => q.transferId === t.transferId);
  if (idx === -1) return [t, ...queue];
  const next = queue.slice();
  next[idx] = t;
  return next;
}

/** Keep sparkline samples in sync with a transfer update. */
function nextSamples(
  samples: Record<string, number[]>,
  t: TransferSummary,
): Record<string, number[]> {
  if (isTerminal(t.state)) {
    if (!(t.transferId in samples)) return samples;
    const pruned = { ...samples };
    delete pruned[t.transferId];
    return pruned;
  }
  if (t.state !== "transferring" || t.speedBps <= 0) return samples;
  const prev = samples[t.transferId] ?? [];
  return {
    ...samples,
    [t.transferId]: [...prev, t.speedBps].slice(-MAX_SPARK_SAMPLES),
  };
}

function applyCoreEvent(state: AppState, ev: CoreEvent): AppState {
  switch (ev.type) {
    case "pairingStarted":
      return {
        ...state,
        pairing: {
          pairingId: ev.pairingId,
          code: ev.code,
          deviceName: ev.deviceName,
        },
        // `start_qr_pairing` emits this event too; the QR payload it returned
        // is stored by the caller and must survive its own event.
        qrPairing:
          state.qrPairing && state.qrPairing.pairingId === ev.pairingId
            ? state.qrPairing
            : null,
      };
    case "pairingCompleted": {
      const others = state.devices.filter(
        (d) => d.deviceId !== ev.device.deviceId,
      );
      return {
        ...state,
        pairing: null,
        qrPairing: null,
        devices: [ev.device, ...others],
      };
    }
    case "pairingFailed":
      if (state.pairing && state.pairing.pairingId !== ev.pairingId) {
        return state;
      }
      return { ...state, pairing: null, qrPairing: null };
    case "transferUpdated":
      return {
        ...state,
        queue: upsertTransfer(state.queue, ev.transfer),
        speedSamples: nextSamples(state.speedSamples, ev.transfer),
      };
    case "watchFileDetected": {
      if (ev.auto) return state; // auto-send detections are handled by core
      if (state.detections.some((d) => d.detectionId === ev.detectionId)) {
        return state;
      }
      const detection: Detection = {
        detectionId: ev.detectionId,
        path: ev.path,
        folderId: ev.folderId,
        fileName: ev.fileName,
        sizeBytes: ev.sizeBytes,
        auto: ev.auto,
        detectedAtMs: Date.now(),
      };
      return { ...state, detections: [detection, ...state.detections] };
    }
    case "messageReceived": {
      // Ephemeral by contract: held in React state only, never written to
      // history and never re-fetched from disk (there is no disk copy).
      if (state.messages.some((m) => m.messageId === ev.messageId)) {
        return state;
      }
      const message: IncomingMessage = {
        messageId: ev.messageId,
        text: ev.text,
        senderName: ev.senderName,
        receivedAtMs: ev.receivedAtMs,
      };
      return {
        ...state,
        messages: [message, ...state.messages].slice(0, MAX_MESSAGE_CARDS),
      };
    }
    case "linkSessionChanged":
      // The core is authoritative: a session that expired on its own comes
      // through here as `null`, which collapses the panel back to composing.
      return { ...state, link: ev.session };
    case "guestUpload":
      // The counter itself rides on `linkSessionChanged`; the upload also
      // shows up in the queue as "Guest (link)". Nothing to fold in here.
      return state;
    case "serverStarted":
      return state.info
        ? { ...state, info: { ...state.info, apiPort: ev.port } }
        : state;
  }
}

function reducer(state: AppState, action: Action): AppState {
  switch (action.type) {
    case "snapshots":
      return {
        ...state,
        loaded: true,
        info: action.info,
        settings: action.settings,
        devices: action.devices,
        queue: action.queue,
        history: action.history,
        watchFolders: action.watchFolders,
      };
    case "core-event":
      return applyCoreEvent(state, action.event);
    case "set-view":
      return { ...state, view: action.view };
    case "set-pending":
      return { ...state, pendingPaths: action.paths, dragging: false };
    case "set-dragging":
      return { ...state, dragging: action.dragging };
    case "set-paused":
      return { ...state, paused: action.paused };
    case "set-settings":
      return { ...state, settings: action.settings };
    case "set-devices":
      return { ...state, devices: action.devices };
    case "set-queue":
      return { ...state, queue: action.queue };
    case "set-history":
      return { ...state, history: action.history };
    case "set-watch-folders":
      return { ...state, watchFolders: action.folders };
    case "remove-detection":
      return {
        ...state,
        detections: state.detections.filter(
          (d) => d.detectionId !== action.detectionId,
        ),
      };
    case "set-chip-device":
      return { ...state, chipDeviceId: action.deviceId };
    case "set-messages":
      return { ...state, messages: action.messages };
    case "dismiss-message":
      return {
        ...state,
        messages: state.messages.filter(
          (m) => m.messageId !== action.messageId,
        ),
      };
    case "open-composer":
      return { ...state, composerText: action.text };
    case "close-composer":
      return { ...state, composerText: null };
    case "dismiss-pairing":
      return { ...state, pairing: null, qrPairing: null };
    case "set-qr-pairing":
      return { ...state, qrPairing: action.qr };
    case "set-link":
      return { ...state, link: action.link };
    case "arm-link":
      return {
        ...state,
        linkArmed: action.armed,
        linkStaged: action.armed ? state.linkStaged : [],
      };
    case "stage-link-files": {
      const seen = new Set(state.linkStaged);
      const added = action.paths.filter((p) => !seen.has(p));
      if (added.length === 0) return state;
      return { ...state, linkStaged: [...state.linkStaged, ...added] };
    }
    case "unstage-link-file":
      return {
        ...state,
        linkStaged: state.linkStaged.filter((p) => p !== action.path),
      };
    case "clear-link-staged":
      return { ...state, linkStaged: [] };
    case "open-preview":
      return { ...state, preview: action.target };
    case "close-preview":
      return { ...state, preview: null };
  }
}

const StateContext = createContext<AppState>(initialState);
const DispatchContext = createContext<Dispatch<Action>>(() => undefined);

export function useAppState(): AppState {
  return useContext(StateContext);
}

export function useAppDispatch(): Dispatch<Action> {
  return useContext(DispatchContext);
}

async function loadSnapshots(dispatch: Dispatch<Action>): Promise<void> {
  const [info, settings, devices, queue, history, folders, messages, link] =
    await Promise.all([
      api.getInfo(),
      api.getSettings(),
      api.trustedDevices(),
      api.getQueue(),
      api.getHistory(),
      api.watchFolders(),
      // Messages that arrived before the webview subscribed (e.g. after a
      // reload). Core keeps them in RAM; oldest first → newest first here.
      api.incomingMessages(),
      // A webview reload must not lose sight of a running guest session —
      // the core still holds it (only an app restart kills it).
      api.linkSession(),
    ]);
  dispatch({
    type: "snapshots",
    info,
    settings,
    devices,
    queue,
    history,
    watchFolders: folders,
  });
  dispatch({ type: "set-messages", messages: messages.slice().reverse() });
  dispatch({ type: "set-link", link });
}

export function AppProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, initialState);

  useEffect(() => {
    let disposed = false;
    const unlisteners: Array<() => void> = [];

    loadSnapshots(dispatch).catch((err) => {
      console.error("failed to load snapshots", err);
    });

    listen<CoreEvent>("core-event", (event) => {
      const ev = event.payload;
      dispatch({ type: "core-event", event: ev });
      // Refresh derived snapshots when a transfer reaches a terminal state
      // (history gains an entry; trusted device lastSeen may have moved).
      if (ev.type === "transferUpdated" && isTerminal(ev.transfer.state)) {
        api
          .getHistory()
          .then((history) => dispatch({ type: "set-history", history }))
          .catch(() => undefined);
      }
      if (ev.type === "pairingCompleted") {
        api
          .trustedDevices()
          .then((devices) => dispatch({ type: "set-devices", devices }))
          .catch(() => undefined);
      }
    }).then((un) => {
      if (disposed) un();
      else unlisteners.push(un);
    });

    // Presence: the iPhone long-polls the core every ~25 s, which bumps its
    // lastSeenMs — but only in the core's memory. Re-fetch the trusted list
    // every 10 s so the online indicator tracks reality instead of the
    // mount-time snapshot.
    const presenceTimer = window.setInterval(() => {
      api
        .trustedDevices()
        .then((devices) => dispatch({ type: "set-devices", devices }))
        .catch(() => undefined);
    }, 10_000);
    unlisteners.push(() => window.clearInterval(presenceTimer));

    // Tray → webview notifications.
    listen<{ paused: boolean }>("sendro://paused", (event) => {
      dispatch({ type: "set-paused", paused: event.payload.paused });
    }).then((un) => {
      if (disposed) un();
      else unlisteners.push(un);
    });

    listen<string>("sendro://navigate", (event) => {
      const view = LEGACY_VIEWS[event.payload];
      if (view) dispatch({ type: "set-view", view });
    }).then((un) => {
      if (disposed) un();
      else unlisteners.push(un);
    });

    return () => {
      disposed = true;
      unlisteners.forEach((un) => un());
    };
  }, []);

  return (
    <StateContext.Provider value={state}>
      <DispatchContext.Provider value={dispatch}>
        {children}
      </DispatchContext.Provider>
    </StateContext.Provider>
  );
}

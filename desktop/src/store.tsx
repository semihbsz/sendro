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
  type CoreEvent,
  type Detection,
  type HistoryEntry,
  type HostInfo,
  type PairingSession,
  type Settings,
  type TransferSummary,
  type TrustedDevice,
  type WatchFolderConfig,
} from "./types";

export type View =
  | "home"
  | "devices"
  | "queue"
  | "history"
  | "watch"
  | "settings";

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
}

const initialState: AppState = {
  loaded: false,
  view: "home",
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
  | { type: "dismiss-pairing" };

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
      };
    case "pairingCompleted": {
      const others = state.devices.filter(
        (d) => d.deviceId !== ev.device.deviceId,
      );
      return { ...state, pairing: null, devices: [ev.device, ...others] };
    }
    case "pairingFailed":
      if (state.pairing && state.pairing.pairingId !== ev.pairingId) {
        return state;
      }
      return { ...state, pairing: null };
    case "transferUpdated":
      return { ...state, queue: upsertTransfer(state.queue, ev.transfer) };
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
    case "dismiss-pairing":
      return { ...state, pairing: null };
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
  const [info, settings, devices, queue, history, folders] = await Promise.all(
    [
      api.getInfo(),
      api.getSettings(),
      api.trustedDevices(),
      api.getQueue(),
      api.getHistory(),
      api.watchFolders(),
    ],
  );
  dispatch({
    type: "snapshots",
    info,
    settings,
    devices,
    queue,
    history,
    watchFolders: folders,
  });
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

    // Tray → webview notifications.
    listen<{ paused: boolean }>("sendro://paused", (event) => {
      dispatch({ type: "set-paused", paused: event.payload.paused });
    }).then((un) => {
      if (disposed) un();
      else unlisteners.push(un);
    });

    listen<string>("sendro://navigate", (event) => {
      const view = event.payload as View;
      dispatch({ type: "set-view", view });
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

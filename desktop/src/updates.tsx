/**
 * Windows in-app updates — docs/UPDATES.md §3.
 *
 * The whole flow lives in the webview: `tauri-plugin-updater` exposes check /
 * download / install as commands, so the progress bar is driven straight off
 * the plugin's own download events rather than a Rust mirror of them.
 *
 * The rules the spec fixes, in one place so they cannot drift:
 *
 *  * a check 10 s after launch, then every 6 h, plus the manual button;
 *  * nothing is ever downloaded without an explicit click;
 *  * an install never starts while a transfer is in flight;
 *  * every failure is terminal and explained in plain language — the app
 *    stays on the current version, and there is no retry loop;
 *  * with auto-check off, only the manual button ever touches the network.
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { getVersion } from "@tauri-apps/api/app";
import { openUrl } from "@tauri-apps/plugin-opener";
import { relaunch } from "@tauri-apps/plugin-process";
import { check, type Update } from "@tauri-apps/plugin-updater";
import * as api from "./api";
import { useAppState } from "./store";
import { isTerminal } from "./types";

/**
 * Where a user is sent when the in-app path fails.
 *
 * `semihbsz` is the GitHub account that owns the releases. Changing owner
 * means changing it in three places, all listed in docs/RELEASING.md §1:
 * here, `src-tauri/tauri.conf.json` (`plugins.updater.endpoints`) and
 * `.github/workflows/release.yml`.
 */
export const RELEASES_URL = "https://github.com/semihbsz/sendro/releases";

/** UPDATES.md §3: "after a 10 s grace period so startup stays fast". */
const FIRST_CHECK_DELAY_MS = 10_000;
/** UPDATES.md §3: "and every 6 h while running". */
const RECHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;

const STORAGE_KEY = "sendro.updates.v1";

interface UpdatePrefs {
  /** UPDATES.md §3: "Check for updates automatically (default on)". */
  autoCheck: boolean;
  /** Version the user pressed "Later" on. Cleared by a manual check. */
  dismissedVersion: string | null;
}

const DEFAULT_PREFS: UpdatePrefs = { autoCheck: true, dismissedVersion: null };

function loadPrefs(): UpdatePrefs {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return DEFAULT_PREFS;
    const parsed = JSON.parse(raw) as Partial<UpdatePrefs>;
    return {
      autoCheck: parsed.autoCheck ?? DEFAULT_PREFS.autoCheck,
      dismissedVersion: parsed.dismissedVersion ?? null,
    };
  } catch {
    return DEFAULT_PREFS;
  }
}

function savePrefs(prefs: UpdatePrefs): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
  } catch {
    // A blocked localStorage only costs the choice its persistence; it must
    // never stop the update itself.
  }
}

/* ------------------------------------------------------------------ *
 * Release metadata
 * ------------------------------------------------------------------ */

/**
 * The fields UPDATES.md §1 puts in `release/release.json` and the release
 * workflow copies into `latest.json`. `version`, `date` and `notes` come from
 * the plugin's own parsing; the rest are read out of `rawJson`, which the
 * plugin hands over untouched (its manifest parser ignores unknown keys).
 */
export interface ReleaseInfo {
  version: string;
  /** RFC3339, or null when the manifest omitted `pub_date`. */
  date: string | null;
  notes: string | null;
  /** Turkish notes, preferred in the card — the user is Turkish. */
  notesTr: string | null;
  minSupported: string | null;
  mandatory: boolean;
}

function readString(json: Record<string, unknown>, key: string): string | null {
  const value = json[key];
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

function describe(update: Update): ReleaseInfo {
  const raw = update.rawJson ?? {};
  return {
    version: update.version,
    date: update.date ?? null,
    notes: update.body ?? readString(raw, "notes"),
    notesTr: readString(raw, "notesTr"),
    minSupported: readString(raw, "minSupported"),
    mandatory: raw["mandatory"] === true,
  };
}

/** `1.2.10` → `[1, 2, 10]`; anything unparseable sorts as 0. */
function parseVersion(version: string): number[] {
  return version
    .split("-")[0]!
    .split(".")
    .map((part) => {
      const n = Number.parseInt(part, 10);
      return Number.isFinite(n) ? n : 0;
    });
}

/** True when `version` is older than `floor` (both semver-ish). */
function isBelow(version: string, floor: string): boolean {
  const a = parseVersion(version);
  const b = parseVersion(floor);
  const len = Math.max(a.length, b.length);
  for (let i = 0; i < len; i += 1) {
    const left = a[i] ?? 0;
    const right = b[i] ?? 0;
    if (left !== right) return left < right;
  }
  return false;
}

/* ------------------------------------------------------------------ *
 * Errors — plain language, never a raw Rust string on its own
 * ------------------------------------------------------------------ */

function explain(err: unknown): string {
  const raw = err instanceof Error ? err.message : String(err);
  const text = raw.toLowerCase();

  if (text.includes("not found") && text.includes("plugin")) {
    return "This build cannot install updates by itself.";
  }
  // Checked before the generic "not found" below: the plugin's own wording is
  // "the platform `windows-x86_64` was not found on the response ...".
  if (text.includes("platforms") || text.includes("was not found on the")) {
    return "That release has no Windows build yet.";
  }
  if (
    text.includes("could not fetch a valid release") ||
    text.includes("404") ||
    text.includes("not found")
  ) {
    return "The update information wasn't there. A release may still be publishing — try again in a few minutes.";
  }
  if (
    text.includes("dns") ||
    text.includes("connect") ||
    text.includes("network") ||
    text.includes("timed out") ||
    text.includes("timeout") ||
    text.includes("offline") ||
    text.includes("request error")
  ) {
    return "Couldn't reach GitHub. Check your internet connection and try again — Sendro stays on this version.";
  }
  if (
    text.includes("signature") ||
    text.includes("minisign") ||
    text.includes("base64") ||
    text.includes("pubkey")
  ) {
    return "The download's signature didn't match Sendro's key, so nothing was installed. Get the installer from the release page instead.";
  }
  if (text.includes("permission") || text.includes("denied")) {
    return "Windows refused to run the installer. Get it from the release page and run it yourself.";
  }
  return `The update didn't go through: ${raw}`;
}

/* ------------------------------------------------------------------ *
 * State
 * ------------------------------------------------------------------ */

export type UpdatePhase =
  /** The updater is off in this build (no signing key / no endpoint). */
  | "unsupported"
  /** Nothing to say — no update known. */
  | "idle"
  | "checking"
  | "available"
  | "downloading"
  | "installing"
  | "error";

export interface UpdateError {
  message: string;
  /**
   * True when the failure followed something the user pressed. A silent
   * background check that fails while a laptop is offline should not throw a
   * card at the screen — but it is still reported in Settings, so nothing
   * fails invisibly either.
   */
  loud: boolean;
}

export interface UpdatesValue {
  phase: UpdatePhase;
  /** From `app.getVersion()` — never a literal in the source. */
  currentVersion: string;
  /** Why the updater is off, when `phase === "unsupported"`. */
  unsupportedReason: string | null;
  release: ReleaseInfo | null;
  /** Bytes fetched so far, and the total when the server sent a length. */
  downloadedBytes: number;
  totalBytes: number | null;
  error: UpdateError | null;
  lastCheckedMs: number | null;
  autoCheck: boolean;
  /** True when the user pressed "Later" on the update now on offer. */
  dismissed: boolean;
  /** Transfers in a non-terminal state — an install must wait for these. */
  blockingTransfers: number;
  /** This build is older than the release's `minSupported`. */
  required: boolean;
  /** `mandatory: true` in the manifest — the card cannot be dismissed. */
  mandatory: boolean;
  /** True when the card should be on screen. */
  cardVisible: boolean;

  checkNow: () => Promise<void>;
  install: () => Promise<void>;
  dismiss: () => void;
  setAutoCheck: (on: boolean) => void;
  pauseTransfers: () => Promise<void>;
  openReleasePage: () => Promise<void>;
}

interface InternalState {
  phase: UpdatePhase;
  currentVersion: string;
  unsupportedReason: string | null;
  release: ReleaseInfo | null;
  downloadedBytes: number;
  totalBytes: number | null;
  error: UpdateError | null;
  lastCheckedMs: number | null;
}

const INITIAL: InternalState = {
  // Optimistic until `updater_status` answers: it keeps the Settings row from
  // flashing "not configured" on every launch of a perfectly fine build.
  phase: "idle",
  currentVersion: "",
  unsupportedReason: null,
  release: null,
  downloadedBytes: 0,
  totalBytes: null,
  error: null,
  lastCheckedMs: null,
};

const UpdatesContext = createContext<UpdatesValue | null>(null);

export function useUpdates(): UpdatesValue {
  const value = useContext(UpdatesContext);
  if (!value) throw new Error("useUpdates must be used inside <UpdatesProvider>");
  return value;
}

export function UpdatesProvider({ children }: { children: ReactNode }) {
  const { queue } = useAppState();
  const [state, setState] = useState<InternalState>(INITIAL);
  const [prefs, setPrefsState] = useState<UpdatePrefs>(loadPrefs);

  /** The live plugin resource. Not React state: it owns a Rust handle. */
  const updateRef = useRef<Update | null>(null);
  /** Guards against two checks (timer + button) overlapping. */
  const checkingRef = useRef(false);
  /** The 10 s grace period is a launch thing, not a "toggle flipped" thing. */
  const firstCheckDone = useRef(false);
  /**
   * Resolves to "this build can update itself". A promise rather than a
   * boolean so a check fired before `updater_status` answered waits for the
   * answer instead of silently doing nothing for the next six hours.
   */
  const supportedRef = useRef<Promise<boolean>>(Promise.resolve(false));

  const blockingTransfers = useMemo(
    () => queue.filter((t) => !isTerminal(t.state)).length,
    [queue],
  );
  const blockingRef = useRef(blockingTransfers);
  blockingRef.current = blockingTransfers;

  const setPrefs = useCallback((next: UpdatePrefs) => {
    setPrefsState(next);
    savePrefs(next);
  }, []);

  /* -- version + "is this build even able to update itself?" -- */

  useEffect(() => {
    let disposed = false;

    getVersion()
      .then((version) => {
        if (!disposed) setState((s) => ({ ...s, currentVersion: version }));
      })
      .catch(() => undefined);

    supportedRef.current = api
      .updaterStatus()
      .then((status) => {
        if (!status.configured && !disposed) {
          setState((s) => ({
            ...s,
            phase: "unsupported",
            unsupportedReason:
              status.reason ?? "Updates are not configured in this build.",
          }));
        }
        return status.configured;
      })
      .catch((err) => {
        // A shell without the command (or a rejected invoke): treat it as
        // "cannot update", which is the safe direction.
        if (!disposed) {
          setState((s) => ({
            ...s,
            phase: "unsupported",
            unsupportedReason: `Updates are not configured in this build (${String(err)}).`,
          }));
        }
        return false;
      });

    return () => {
      disposed = true;
    };
  }, []);

  /* -- release the plugin resource with the component -- */

  useEffect(
    () => () => {
      updateRef.current?.close().catch(() => undefined);
      updateRef.current = null;
    },
    [],
  );

  /* -- the check itself -- */

  const runCheck = useCallback(async (manual: boolean) => {
    if (checkingRef.current) return;
    if (!(await supportedRef.current)) return;
    checkingRef.current = true;

    setState((s) => ({
      ...s,
      phase: "checking",
      error: manual ? null : s.error,
    }));

    try {
      const found = await check();

      // Drop the previous handle before adopting a new one — each `check()`
      // that finds something registers a resource on the Rust side.
      if (updateRef.current && updateRef.current !== found) {
        await updateRef.current.close().catch(() => undefined);
      }
      updateRef.current = found;

      setState((s) => ({
        ...s,
        phase: found ? "available" : "idle",
        release: found ? describe(found) : null,
        downloadedBytes: 0,
        totalBytes: null,
        error: null,
        lastCheckedMs: Date.now(),
      }));
    } catch (err) {
      console.error("update check failed", err);
      setState((s) => ({
        ...s,
        // A failed check leaves the app exactly where it was.
        phase: "error",
        release: null,
        error: { message: explain(err), loud: manual },
        lastCheckedMs: Date.now(),
      }));
    } finally {
      checkingRef.current = false;
    }
  }, []);

  /* -- schedule: 10 s after launch, then every 6 h, only when auto is on -- */

  useEffect(() => {
    if (!prefs.autoCheck) return;

    let cancelled = false;
    let interval: number | null = null;

    const start = () => {
      if (cancelled) return;
      firstCheckDone.current = true;
      void runCheck(false);
      interval = window.setInterval(() => {
        void runCheck(false);
      }, RECHECK_INTERVAL_MS);
    };

    const timer = window.setTimeout(
      start,
      firstCheckDone.current ? 0 : FIRST_CHECK_DELAY_MS,
    );

    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      if (interval !== null) window.clearInterval(interval);
    };
  }, [prefs.autoCheck, runCheck]);

  /* -- actions -- */

  const checkNow = useCallback(async () => {
    // A manual check is also "show me the card again".
    setPrefs({ ...prefs, dismissedVersion: null });
    await runCheck(true);
  }, [prefs, runCheck, setPrefs]);

  const install = useCallback(async () => {
    const update = updateRef.current;
    if (!update) return;
    // Belt and braces: the button is disabled too, but a transfer can start
    // between render and click.
    if (blockingRef.current > 0) return;

    setState((s) => ({
      ...s,
      phase: "downloading",
      downloadedBytes: 0,
      totalBytes: null,
      error: null,
    }));

    try {
      await update.downloadAndInstall((event) => {
        if (event.event === "Started") {
          const length = event.data.contentLength;
          setState((s) => ({
            ...s,
            phase: "downloading",
            totalBytes: typeof length === "number" && length > 0 ? length : null,
          }));
        } else if (event.event === "Progress") {
          setState((s) => ({
            ...s,
            downloadedBytes: s.downloadedBytes + event.data.chunkLength,
          }));
        } else {
          setState((s) => ({ ...s, phase: "installing" }));
        }
      });

      // On Windows the plugin hands the NSIS installer `/P /R` and exits this
      // process itself, so this line is normally never reached. It is the
      // documented tail of the flow and the safety net everywhere else.
      await relaunch();
    } catch (err) {
      console.error("update install failed", err);
      setState((s) => ({
        ...s,
        phase: "error",
        error: { message: explain(err), loud: true },
      }));
    }
  }, []);

  const dismiss = useCallback(() => {
    // Clearing an error drops back to "there is an update, you just didn't
    // take it" rather than to nothing, so Settings keeps telling the truth
    // and "Check now" can put the card back.
    setState((s) =>
      s.phase === "error"
        ? { ...s, error: null, phase: s.release ? "available" : "idle" }
        : s,
    );
    const version = state.release?.version;
    if (version) setPrefs({ ...prefs, dismissedVersion: version });
  }, [prefs, setPrefs, state.release]);

  const setAutoCheck = useCallback(
    (on: boolean) => setPrefs({ ...prefs, autoCheck: on }),
    [prefs, setPrefs],
  );

  const pauseTransfers = useCallback(async () => {
    try {
      await api.pauseTransfers(true);
    } catch (err) {
      console.error("pause before update failed", err);
    }
  }, []);

  const openReleasePage = useCallback(async () => {
    try {
      await openUrl(RELEASES_URL);
    } catch (err) {
      console.error("could not open the release page", err);
    }
  }, []);

  const mandatory = state.release?.mandatory === true;
  const floor = state.release?.minSupported ?? null;
  const required =
    floor !== null &&
    state.currentVersion !== "" &&
    isBelow(state.currentVersion, floor);
  const dismissed =
    !mandatory &&
    !required &&
    state.release !== null &&
    prefs.dismissedVersion === state.release.version;

  const cardVisible =
    state.phase === "downloading" ||
    state.phase === "installing" ||
    (state.phase === "available" && !dismissed) ||
    (state.phase === "error" && state.error?.loud === true);

  const value: UpdatesValue = {
    phase: state.phase,
    currentVersion: state.currentVersion,
    unsupportedReason: state.unsupportedReason,
    release: state.release,
    downloadedBytes: state.downloadedBytes,
    totalBytes: state.totalBytes,
    error: state.error,
    lastCheckedMs: state.lastCheckedMs,
    autoCheck: prefs.autoCheck,
    dismissed,
    blockingTransfers,
    required,
    mandatory,
    cardVisible,
    checkNow,
    install,
    dismiss,
    setAutoCheck,
    pauseTransfers,
    openReleasePage,
  };

  return (
    <UpdatesContext.Provider value={value}>{children}</UpdatesContext.Provider>
  );
}

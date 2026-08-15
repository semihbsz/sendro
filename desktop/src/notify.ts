/**
 * Windows toast notifications (tauri-plugin-notification).
 *
 * Rules, in one place so they cannot drift:
 *
 * * Never notify about something the user is already looking at — if the
 *   window has focus *and* the relevant view is on screen, stay quiet.
 * * Always notify when the window is not focused (minimized to tray counts).
 * * A text message notification carries the sender only. §11 bodies are
 *   ephemeral and private; putting one in a toast would write it to the
 *   Windows Action Center, which is a form of persistence.
 * * Permission is requested once, lazily, the first time we want to notify.
 */
import {
  isPermissionGranted,
  requestPermission,
  sendNotification,
} from "@tauri-apps/plugin-notification";
import type { View } from "./store";

export type NotifyCategory =
  | "incoming"
  | "completed"
  | "failed"
  | "message"
  | "watch"
  | "guest";

export interface NotifyPrefs {
  enabled: boolean;
  categories: Record<NotifyCategory, boolean>;
}

export const NOTIFY_CATEGORIES: ReadonlyArray<{
  key: NotifyCategory;
  label: string;
  hint: string;
}> = [
  {
    key: "incoming",
    label: "Incoming transfers",
    hint: "Your phone or a guest started sending something.",
  },
  {
    key: "completed",
    label: "Transfer completed",
    hint: "A file finished and passed verification.",
  },
  {
    key: "failed",
    label: "Transfer failed",
    hint: "Something was interrupted, rejected or didn't verify.",
  },
  {
    key: "message",
    label: "Text messages",
    hint: "Sender only — the text itself never goes into a toast.",
  },
  {
    key: "watch",
    label: "Watch folder detections",
    hint: "A new export showed up in a folder you're watching.",
  },
  {
    key: "guest",
    label: "Guest uploads",
    hint: "Someone pushed a file through a Sendro Link session.",
  },
];

const STORAGE_KEY = "sendro.notifications.v1";

export const DEFAULT_PREFS: NotifyPrefs = {
  enabled: true,
  categories: {
    incoming: true,
    completed: true,
    failed: true,
    message: true,
    watch: true,
    guest: true,
  },
};

export function loadPrefs(): NotifyPrefs {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return DEFAULT_PREFS;
    const parsed = JSON.parse(raw) as Partial<NotifyPrefs>;
    return {
      enabled: parsed.enabled ?? DEFAULT_PREFS.enabled,
      categories: {
        ...DEFAULT_PREFS.categories,
        ...(parsed.categories ?? {}),
      },
    };
  } catch {
    return DEFAULT_PREFS;
  }
}

export function savePrefs(prefs: NotifyPrefs): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
  } catch {
    // A blocked localStorage just means the choice does not survive a
    // restart; it must never break notifications themselves.
  }
}

/**
 * Which view makes a category redundant. `null` = redundant whenever the
 * window has focus at all (message cards are drawn app-wide).
 */
const OWNING_VIEW: Record<NotifyCategory, View | null> = {
  incoming: "flow",
  completed: "flow",
  failed: "flow",
  message: null,
  watch: "watch",
  // The Sendro Link panel lives on SEND.
  guest: "send",
};

export function isSuppressed(
  category: NotifyCategory,
  focused: boolean,
  view: View,
): boolean {
  if (!focused) return false;
  const owner = OWNING_VIEW[category];
  return owner === null || owner === view;
}

/* -- permission, asked once -- */

let permission: "unknown" | "granted" | "denied" = "unknown";
let inFlight: Promise<boolean> | null = null;

export async function ensurePermission(): Promise<boolean> {
  if (permission === "granted") return true;
  if (permission === "denied") return false;
  if (inFlight) return inFlight;

  inFlight = (async () => {
    try {
      let granted = await isPermissionGranted();
      if (!granted) {
        granted = (await requestPermission()) === "granted";
      }
      permission = granted ? "granted" : "denied";
      return granted;
    } catch (err) {
      console.error("notification permission check failed", err);
      permission = "denied";
      return false;
    } finally {
      inFlight = null;
    }
  })();
  return inFlight;
}

/** Forget a "denied" so the Settings button can ask again. */
export function resetPermission(): void {
  permission = "unknown";
}

export async function permissionGranted(): Promise<boolean> {
  try {
    return await isPermissionGranted();
  } catch {
    return false;
  }
}

/** Fire a toast, honouring prefs + suppression. Never throws. */
export async function notify(
  category: NotifyCategory,
  prefs: NotifyPrefs,
  focused: boolean,
  view: View,
  options: { title: string; body?: string },
): Promise<void> {
  if (!prefs.enabled || !prefs.categories[category]) return;
  if (isSuppressed(category, focused, view)) return;
  if (!(await ensurePermission())) return;
  try {
    sendNotification(
      options.body === undefined
        ? { title: options.title }
        : { title: options.title, body: options.body },
    );
  } catch (err) {
    console.error("notification failed", err);
  }
}

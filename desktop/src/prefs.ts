/**
 * Notification preferences, shared between the Settings panel and the
 * notification listener.
 *
 * They live in `localStorage`, not in core `Settings`: they are a property of
 * this Windows install's UI, the core has no field for them, and `core/` is
 * owned by someone else. A tiny external store keeps the two consumers in
 * sync without threading the value through the app reducer.
 */
import { useSyncExternalStore } from "react";
import { loadPrefs, savePrefs, type NotifyPrefs } from "./notify";

let current: NotifyPrefs = loadPrefs();
const listeners = new Set<() => void>();

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function snapshot(): NotifyPrefs {
  return current;
}

export function setPrefs(next: NotifyPrefs): void {
  current = next;
  savePrefs(next);
  listeners.forEach((listener) => listener());
}

/** `[prefs, setPrefs]`, like `useState` but shared app-wide. */
export function usePrefs(): [NotifyPrefs, (next: NotifyPrefs) => void] {
  const prefs = useSyncExternalStore(subscribe, snapshot, snapshot);
  return [prefs, setPrefs];
}

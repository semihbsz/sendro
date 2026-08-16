/**
 * The in-app update card — docs/UPDATES.md §3.
 *
 * Version, date, notes (Turkish first when the manifest carries them),
 * "Update now" / "Later", and a real progress bar fed by the plugin's
 * download events. Two things it deliberately refuses to do:
 *
 *  * download anything before the button is pressed, and
 *  * install while a transfer is in flight — the card says how many are
 *    running and offers to pause them instead.
 */
import { IconDownload, IconX } from "../icons";
import { formatBytes, formatDate, formatPercent } from "../format";
import { useUpdates } from "../updates";

function notesFor(notesTr: string | null, notes: string | null): string | null {
  // The person running Sendro is Turkish; the English notes are the fallback.
  return notesTr ?? notes;
}

export function UpdateCard() {
  const u = useUpdates();
  if (!u.cardVisible) return null;

  const busy = u.phase === "downloading" || u.phase === "installing";
  const blocked = u.blockingTransfers > 0;
  const notes = u.release ? notesFor(u.release.notesTr, u.release.notes) : null;
  const percent =
    u.totalBytes !== null ? formatPercent(u.downloadedBytes, u.totalBytes) : null;

  const title = u.error
    ? "The update didn't install"
    : u.required
      ? "Update required"
      : u.release
        ? `Sendro ${u.release.version} is available`
        : "Update";

  return (
    <div className="update-stack">
      <div className="update-card" role="status">
        <div className="update-head">
          <span className="update-icon">
            <IconDownload size={15} />
          </span>
          <div className="update-head-text">
            <div className="update-title">{title}</div>
            <div className="update-sub mono">
              {u.release ? (
                <>
                  {`v${u.currentVersion || "?"} → v${u.release.version}`}
                  {u.release.date ? ` · ${formatDate(Date.parse(u.release.date))}` : ""}
                </>
              ) : (
                `v${u.currentVersion || "?"}`
              )}
            </div>
          </div>
          {u.mandatory || u.required || busy ? null : (
            <button
              className="icon-btn update-close"
              title="Later"
              onClick={u.dismiss}
            >
              <IconX size={11} strokeWidth={2.4} />
            </button>
          )}
        </div>

        {u.error ? (
          <div className="update-body">
            <div className="update-error">{u.error.message}</div>
          </div>
        ) : notes ? (
          <div className="update-body">
            <div className="update-notes">{notes}</div>
          </div>
        ) : null}

        {u.required && !u.error ? (
          <div className="update-body">
            <div className="update-warn">
              This version is older than the release needs. Some transfers with
              your phone may not work until you update.
            </div>
          </div>
        ) : null}

        {busy ? (
          <div className="update-progress">
            <div className="update-bar">
              <span
                className={`update-bar-fill${percent === null ? " indeterminate" : ""}`}
                style={percent === null ? undefined : { width: `${percent}%` }}
              />
            </div>
            <div className="update-progress-meta mono">
              {u.phase === "installing"
                ? "installing — Sendro will restart"
                : u.totalBytes !== null
                  ? `${formatBytes(u.downloadedBytes)} / ${formatBytes(u.totalBytes)}`
                  : `${formatBytes(u.downloadedBytes)} downloaded`}
            </div>
          </div>
        ) : null}

        {blocked && !busy && !u.error ? (
          <div className="update-blocked">
            {u.blockingTransfers === 1
              ? "1 transfer running — pause or finish it first."
              : `${u.blockingTransfers} transfers running — pause or finish them first.`}
          </div>
        ) : null}

        <div className="update-actions">
          {u.error ? (
            <>
              <button
                className="btn-glass btn-sm"
                onClick={() => void u.openReleasePage()}
              >
                Open release page
              </button>
              <span className="update-actions-spacer" />
              <button className="btn-ghost-text" onClick={u.dismiss}>
                Dismiss
              </button>
            </>
          ) : busy ? (
            <span className="update-hint">
              Keep Sendro open until this finishes.
            </span>
          ) : (
            <>
              <button
                className="btn-solid btn-sm"
                disabled={blocked}
                title={
                  blocked
                    ? "Finish or pause the running transfers first"
                    : undefined
                }
                onClick={() => void u.install()}
              >
                Update now
              </button>
              {blocked ? (
                <button
                  className="btn-glass btn-sm"
                  onClick={() => void u.pauseTransfers()}
                >
                  Pause transfers
                </button>
              ) : null}
              <span className="update-actions-spacer" />
              {u.mandatory || u.required ? (
                <button
                  className="btn-ghost-text"
                  onClick={() => void u.openReleasePage()}
                >
                  Release page
                </button>
              ) : (
                <button className="btn-ghost-text" onClick={u.dismiss}>
                  Later
                </button>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

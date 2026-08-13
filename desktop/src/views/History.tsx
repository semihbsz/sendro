import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import {
  formatBytes,
  formatDuration,
  formatSpeed,
} from "../format";
import { IconHistory, IconShieldCheck, IconTrash } from "../icons";
import { DirArrow, EmptyState, stateLabel } from "../components/common";

export function History() {
  const { history } = useAppState();
  const dispatch = useAppDispatch();

  const clear = async () => {
    try {
      await api.clearHistory();
      dispatch({ type: "set-history", history: [] });
    } catch (err) {
      console.error("clear history failed", err);
    }
  };

  return (
    <div className="view">
      <div className="view-header">
        <div>
          <div className="view-title">History</div>
          <div className="view-subtitle">
            Every finished transfer, kept on this PC only.
          </div>
        </div>
        <div className="view-actions">
          <button
            className="btn btn-ghost"
            disabled={history.length === 0}
            onClick={() => void clear()}
          >
            <IconTrash size={14} />
            Clear History
          </button>
        </div>
      </div>

      <div className="panel" style={{ overflow: "hidden" }}>
        {history.length === 0 ? (
          <EmptyState
            icon={<IconHistory size={20} />}
            title="No transfers yet"
            subtitle="Once a transfer finishes — sent or received — it shows up here with speed and verification details."
          />
        ) : (
          <div style={{ overflowX: "auto" }}>
            <table className="table">
              <thead>
                <tr>
                  <th>File</th>
                  <th aria-label="Direction" />
                  <th>Peer</th>
                  <th>Size</th>
                  <th>Duration</th>
                  <th>Avg speed</th>
                  <th>Verified</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {history.map((h) => (
                  <tr key={h.transferId}>
                    <td className="td-file" title={h.fileName}>
                      {h.fileName}
                    </td>
                    <td>
                      <DirArrow direction={h.direction} />
                    </td>
                    <td>{h.peerName}</td>
                    <td>{formatBytes(h.sizeBytes)}</td>
                    <td>{formatDuration(h.durationMs)}</td>
                    <td>{formatSpeed(h.avgSpeedBps)}</td>
                    <td>
                      {h.verified ? (
                        <span className="verified-badge">
                          <IconShieldCheck size={13} />
                          SHA-256
                        </span>
                      ) : (
                        <span style={{ color: "var(--text-tertiary)" }}>—</span>
                      )}
                    </td>
                    <td>
                      <span
                        style={
                          h.finalState === "completed"
                            ? { color: "var(--text-primary)" }
                            : { color: "var(--danger)" }
                        }
                      >
                        {stateLabel(h.finalState)}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

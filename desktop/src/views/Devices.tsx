import { useAppDispatch, useAppState } from "../store";
import * as api from "../api";
import { formatDate, formatRelative, isOnline } from "../format";
import { IconPhone, IconTrash, IconWifi } from "../icons";
import { EmptyState } from "../components/common";

export function Devices() {
  const { devices } = useAppState();
  const dispatch = useAppDispatch();

  const revoke = async (deviceId: string) => {
    try {
      await api.revokeDevice(deviceId);
      const next = await api.trustedDevices();
      dispatch({ type: "set-devices", devices: next });
    } catch (err) {
      console.error("revoke failed", err);
    }
  };

  return (
    <div className="view">
      <div className="view-header">
        <div>
          <div className="view-title">Devices</div>
          <div className="view-subtitle">
            iPhones and iPads trusted to receive from this PC.
          </div>
        </div>
      </div>

      <div className="panel">
        {devices.length === 0 ? (
          <EmptyState
            icon={<IconPhone size={20} />}
            title="No devices paired yet"
            subtitle="Open Sendro on your iPhone and tap this PC to pair. A 6-digit code will appear here."
          />
        ) : (
          <div className="list">
            {devices.map((d) => {
              const online = isOnline(d.lastSeenMs);
              return (
                <div className="row" key={d.deviceId}>
                  <span className="row-icon">
                    <IconPhone size={17} />
                  </span>
                  <div className="row-main">
                    <div className="row-title">{d.deviceName}</div>
                    <div className="row-sub">
                      {d.platform === "ios" ? "iOS" : d.platform} · paired{" "}
                      {formatDate(d.pairedAtMs)} ·{" "}
                      {online
                        ? "online now"
                        : `last seen ${formatRelative(d.lastSeenMs)}`}
                    </div>
                  </div>
                  <span
                    className={`chip${online ? " chip-accent" : ""}`}
                    style={{ marginRight: "var(--s-2)" }}
                  >
                    <span
                      className={`chip-dot${online ? " pulse" : ""}`}
                    />
                    {online ? "Online" : "Offline"}
                  </span>
                  <div className="row-actions">
                    <button
                      className="btn btn-sm btn-danger-ghost"
                      onClick={() => void revoke(d.deviceId)}
                      title="Revoke trust — this device will need to pair again"
                    >
                      <IconTrash size={13} />
                      Revoke
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      <div className="hint-card">
        <span className="hint-icon">
          <IconWifi size={18} />
        </span>
        <div>
          <div className="hint-title">How to pair</div>
          <div className="hint-body">
            Make sure both devices are on the same Wi-Fi network, then:
            <ol>
              <li>Open Sendro on your iPhone.</li>
              <li>Tap this PC when it appears under nearby devices.</li>
              <li>Type the 6-digit code that pops up on this screen.</li>
            </ol>
          </div>
        </div>
      </div>
    </div>
  );
}

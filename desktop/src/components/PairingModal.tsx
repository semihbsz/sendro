import { Modal } from "./Modal";
import { useAppDispatch, useAppState } from "../store";

/** Shown app-wide when a PairingStarted core event arrives; auto-dismissed
 *  by the store on PairingCompleted / PairingFailed. */
export function PairingModal() {
  const { pairing } = useAppState();
  const dispatch = useAppDispatch();
  if (!pairing) return null;

  const digits = pairing.code.split("");

  return (
    <Modal onClose={() => dispatch({ type: "dismiss-pairing" })}>
      <div className="pairing-modal">
        <div className="pairing-hint">Enter this code on your iPhone</div>
        <div className="pairing-device">
          “{pairing.deviceName}” is trying to pair with this PC
        </div>
        <div className="pairing-code">
          {digits.map((d, i) => (
            <span key={i} className="pairing-digit">
              {d}
            </span>
          ))}
        </div>
        <div className="pairing-foot">
          expires in ~2 minutes · nothing leaves your network
        </div>
      </div>
    </Modal>
  );
}

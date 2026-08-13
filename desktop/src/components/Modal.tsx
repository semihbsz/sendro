import type { ReactNode } from "react";
import { IconX } from "../icons";

export function Modal({
  title,
  onClose,
  children,
}: {
  title?: string;
  onClose?: () => void;
  children: ReactNode;
}) {
  return (
    <div
      className="modal-backdrop"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && onClose) onClose();
      }}
    >
      <div className="modal" role="dialog" aria-modal="true">
        {title ? (
          <div className="modal-header">
            <div className="modal-title">{title}</div>
            {onClose ? (
              <button
                className="btn btn-icon btn-ghost"
                onClick={onClose}
                aria-label="Close"
              >
                <IconX size={16} />
              </button>
            ) : null}
          </div>
        ) : null}
        <div className="modal-body">{children}</div>
      </div>
    </div>
  );
}

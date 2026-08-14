import type { ReactNode } from "react";

export function Modal({
  title,
  onClose,
  cancelLabel,
  children,
}: {
  title?: string;
  onClose?: () => void;
  /** Optional centered dismiss link at the modal's foot (design language). */
  cancelLabel?: string;
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
        {title ? <div className="modal-title">{title}</div> : null}
        {children}
        {onClose && cancelLabel ? (
          <button className="modal-cancel" onClick={onClose}>
            {cancelLabel}
          </button>
        ) : null}
      </div>
    </div>
  );
}

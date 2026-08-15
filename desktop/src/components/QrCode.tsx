import { useMemo } from "react";
import { encodeQr } from "../qr";

/**
 * A scannable QR tile.
 *
 * Always dark modules on a **white** tile, even in the dark theme: phone
 * cameras expect that polarity, and an inverted code fails on a lot of
 * scanners. The tile carries its own quiet zone (from `encodeQr`), so it can
 * sit directly on a glass card.
 *
 * `size` is the rendered edge in CSS px — keep it ≥ 240 so the modules stay
 * large enough to scan across a desk.
 */
export function QrCode({
  data,
  size = 240,
  label,
}: {
  data: string;
  size?: number;
  label?: string;
}) {
  const matrix = useMemo(() => encodeQr(data), [data]);

  if (!matrix) {
    return (
      <div className="qr-tile qr-tile-failed" style={{ width: size, height: size }}>
        <span>Could not render a code for this address</span>
      </div>
    );
  }

  return (
    <div className="qr-tile" style={{ width: size, height: size }}>
      <svg
        viewBox={`0 0 ${matrix.size} ${matrix.size}`}
        width="100%"
        height="100%"
        shapeRendering="crispEdges"
        role="img"
        aria-label={label ?? "QR code"}
      >
        <rect width={matrix.size} height={matrix.size} fill="#ffffff" />
        <path d={matrix.path} fill="#000000" />
      </svg>
    </div>
  );
}

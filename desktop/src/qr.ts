/**
 * Offline QR encoding.
 *
 * `qrcode-generator` (MIT, Kazuhiko Arase, zero runtime dependencies) is
 * bundled at build time and does the whole thing in pure JS — no network, no
 * canvas API, no CDN. That matters: Sendro renders pairing and Sendro Link
 * codes on machines with no internet at all.
 *
 * We emit an SVG path rather than a canvas so the code stays crisp at any
 * size and scales with the layout.
 */
import qrcode from "qrcode-generator";

// The library's default byte encoder is Latin-1. Everything we encode is
// already ASCII (§13 percent-encodes every value, §14 tokens are base64url),
// but a device name could still slip through some day — so use real UTF-8.
qrcode.stringToBytes = (s: string) => Array.from(new TextEncoder().encode(s));

/** Quiet zone, in modules. The spec's minimum is 4 — scanners need it. */
export const QR_MARGIN = 4;

export interface QrMatrix {
  /** SVG path data covering every dark module, in module units. */
  path: string;
  /** Modules per side, excluding the quiet zone. */
  count: number;
  /** Modules per side including the quiet zone — the SVG viewBox size. */
  size: number;
}

/**
 * Encode `data` into a module matrix.
 *
 * Error correction level M: the sweet spot for a screen-to-camera scan —
 * enough redundancy for a glare-y phone photo without inflating the version
 * (and therefore shrinking the modules) the way H would.
 *
 * Returns `null` when the payload cannot be encoded (too long for version 40);
 * callers fall back to showing the URL as text.
 */
export function encodeQr(data: string): QrMatrix | null {
  if (data.length === 0) return null;
  try {
    // Type number 0 = pick the smallest version that fits.
    const qr = qrcode(0, "M");
    qr.addData(data);
    qr.make();

    const count = qr.getModuleCount();
    const parts: string[] = [];
    for (let row = 0; row < count; row++) {
      let runStart = -1;
      for (let col = 0; col <= count; col++) {
        const dark = col < count && qr.isDark(row, col);
        if (dark && runStart === -1) {
          runStart = col;
        } else if (!dark && runStart !== -1) {
          // One rect per horizontal run — far fewer path commands than one
          // per module, which keeps the DOM small for a version-10 code.
          const x = runStart + QR_MARGIN;
          const y = row + QR_MARGIN;
          parts.push(`M${x} ${y}h${col - runStart}v1h-${col - runStart}z`);
          runStart = -1;
        }
      }
    }

    return { path: parts.join(""), count, size: count + QR_MARGIN * 2 };
  } catch (err) {
    console.error("QR encoding failed", err);
    return null;
  }
}

/** Human label for a `QrUrl.kind` / `NetIface.kind`. */
export function kindLabel(kind: string): string {
  switch (kind) {
    case "lan":
      return "LAN";
    case "hotspot":
      return "Hotspot";
    default:
      return "Other";
  }
}

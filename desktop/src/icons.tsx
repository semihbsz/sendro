/** Minimal hand-drawn inline SVG icon set. Stroke 1.5, 24px viewBox. */
import type { SVGProps } from "react";

type IconProps = SVGProps<SVGSVGElement> & { size?: number };

function Svg({ size = 18, children, ...rest }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...rest}
    >
      {children}
    </svg>
  );
}

export const IconHome = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 10.5 12 4l8 6.5V19a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19v-8.5Z" />
    <path d="M9.5 20.5v-6h5v6" />
  </Svg>
);

export const IconPhone = (p: IconProps) => (
  <Svg {...p}>
    <rect x="7.5" y="3" width="9" height="18" rx="2.5" />
    <path d="M10.75 18h2.5" />
  </Svg>
);

export const IconQueue = (p: IconProps) => (
  <Svg {...p}>
    <path d="M7 4v13" />
    <path d="m3.5 13.5 3.5 3.5 3.5-3.5" />
    <path d="M17 20V7" />
    <path d="m13.5 10.5 3.5-3.5 3.5 3.5" />
  </Svg>
);

export const IconHistory = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="8.5" />
    <path d="M12 7.5V12l3 2" />
  </Svg>
);

export const IconWatch = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3.5 7A1.5 1.5 0 0 1 5 5.5h4l2 2.5h8A1.5 1.5 0 0 1 20.5 9.5V17A1.5 1.5 0 0 1 19 18.5H5A1.5 1.5 0 0 1 3.5 17V7Z" />
    <circle cx="12" cy="13" r="2" />
    <path d="M8 13c1-1.8 2.4-2.7 4-2.7s3 .9 4 2.7c-1 1.8-2.4 2.7-4 2.7s-3-.9-4-2.7Z" />
  </Svg>
);

export const IconSettings = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 7.5h9M17 7.5h3" />
    <circle cx="15" cy="7.5" r="2" />
    <path d="M4 16.5h3M11 16.5h9" />
    <circle cx="9" cy="16.5" r="2" />
  </Svg>
);

export const IconSend = (p: IconProps) => (
  <Svg {...p}>
    <path d="M20.5 3.5 10 14" />
    <path d="M20.5 3.5 14 20.5l-4-6.5-6.5-4 17-6.5Z" />
  </Svg>
);

export const IconPlus = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 5v14M5 12h14" />
  </Svg>
);

export const IconX = (p: IconProps) => (
  <Svg {...p}>
    <path d="m6 6 12 12M18 6 6 18" />
  </Svg>
);

export const IconCheck = (p: IconProps) => (
  <Svg {...p}>
    <path d="m5 12.5 4.5 4.5L19 7.5" />
  </Svg>
);

export const IconShieldCheck = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 3.5 5 6v5.5c0 4.2 2.8 7.2 7 9 4.2-1.8 7-4.8 7-9V6l-7-2.5Z" />
    <path d="m9 11.8 2.2 2.2 4-4.3" />
  </Svg>
);

export const IconPause = (p: IconProps) => (
  <Svg {...p}>
    <path d="M9 5.5v13M15 5.5v13" />
  </Svg>
);

export const IconPlay = (p: IconProps) => (
  <Svg {...p}>
    <path d="M8 5.5v13l10-6.5-10-6.5Z" />
  </Svg>
);

export const IconRetry = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4.5 12a7.5 7.5 0 1 1 2.2 5.3" />
    <path d="M4.5 21v-4.5H9" />
  </Svg>
);

export const IconTrash = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4.5 6.5h15" />
    <path d="M9.5 3.5h5" />
    <path d="M6.5 6.5 7.5 20a1 1 0 0 0 1 .9h7a1 1 0 0 0 1-.9l1-13.5" />
    <path d="M10 10.5v6M14 10.5v6" />
  </Svg>
);

export const IconFolder = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3.5 7A1.5 1.5 0 0 1 5 5.5h4l2 2.5h8A1.5 1.5 0 0 1 20.5 9.5V17A1.5 1.5 0 0 1 19 18.5H5A1.5 1.5 0 0 1 3.5 17V7Z" />
  </Svg>
);

export const IconFile = (p: IconProps) => (
  <Svg {...p}>
    <path d="M6 4.5A1.5 1.5 0 0 1 7.5 3H14l4.5 4.5v12A1.5 1.5 0 0 1 17 21H7.5A1.5 1.5 0 0 1 6 19.5v-15Z" />
    <path d="M14 3v5h5" />
  </Svg>
);

export const IconArrowUp = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 19V5" />
    <path d="m6 11 6-6 6 6" />
  </Svg>
);

export const IconArrowDown = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 5v14" />
    <path d="m6 13 6 6 6-6" />
  </Svg>
);

export const IconWifi = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 9.5c4.7-4 11.3-4 16 0" />
    <path d="M6.8 12.8c3.1-2.6 7.3-2.6 10.4 0" />
    <path d="M9.6 16c1.5-1.2 3.3-1.2 4.8 0" />
    <circle cx="12" cy="18.8" r="0.9" fill="currentColor" stroke="none" />
  </Svg>
);

export const IconBolt = (p: IconProps) => (
  <Svg {...p}>
    <path d="M13 3 5 13.5h5.5L11 21l8-10.5h-5.5L13 3Z" />
  </Svg>
);

export const IconDrop = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 3v11" />
    <path d="m7.5 9.5 4.5 4.5 4.5-4.5" />
    <path d="M4.5 16v2.5A2.5 2.5 0 0 0 7 21h10a2.5 2.5 0 0 0 2.5-2.5V16" />
  </Svg>
);

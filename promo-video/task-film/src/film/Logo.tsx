import React from "react";
import { clamp01 } from "../kit";

// 智禾农场标识（取自 film/public/brand/zhihe-logo.svg），叶片可按进度生长
export const Logo: React.FC<{ size: number; grow?: number }> = ({ size, grow = 1 }) => {
  const g = clamp01(grow);
  const leaf = (p: number) => `scale(${0.4 + 0.6 * p})`;
  return (
    <svg width={size} height={size} viewBox="0 0 64 64">
      <rect width="64" height="64" rx="18" fill="#234d3c" />
      <g style={{ transformOrigin: "32px 36px", transform: leaf(clamp01(g * 1.4)) }} opacity={clamp01(g * 2)}>
        <path d="M32 36C22 36 17 30 17 22C26 22 32 27 32 36Z" fill="#f5f2e6" />
      </g>
      <g style={{ transformOrigin: "32px 28px", transform: leaf(clamp01(g * 1.4 - 0.3)) }} opacity={clamp01(g * 2 - 0.4)}>
        <path d="M32 28C32 19 38 14 47 14C47 23 41 28 32 28Z" fill="#dfbc70" />
      </g>
      <path d="M32 27V43M16 49Q32 37 48 49" fill="none" stroke="#f5f2e6" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" pathLength={1} strokeDasharray={1} strokeDashoffset={1 - clamp01(g * 1.6)} />
    </svg>
  );
};

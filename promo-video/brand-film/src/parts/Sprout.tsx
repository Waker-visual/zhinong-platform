import React from "react";
import { P } from "../kit";

// 小禾形象：路径与配色取自产品 frontend/src/ai/SproutCompanion.vue 与 theme.css 的 --sprout-* 变量。
// hop / wave / blink 为 0→1 的一次性动作进度；busy 时嘴形收拢（与产品一致）。
export const Sprout: React.FC<{ size: number; hop?: number; wave?: number; blink?: number; busy?: boolean; sway?: number; style?: React.CSSProperties }> = ({ size, hop = 0, wave = 0, blink = 0, busy = false, sway = 0, style }) => {
  const k = P.sprout;
  const h = Math.sin(Math.min(1, hop) * Math.PI);
  const fig = `translateY(${-9 * h}px) rotate(${-3 * Math.sin(hop * Math.PI * 2)}deg)`;
  const leaves = `rotate(${sway * 4 + Math.sin(wave * Math.PI * 3) * 8 * (1 - wave)}deg)`;
  const arm = `rotate(${Math.sin(wave * Math.PI * 4) * -18 * (wave > 0 && wave < 1 ? 1 : 0)}deg)`;
  const eye = 1 - 0.9 * Math.sin(Math.min(1, blink) * Math.PI);
  const stroke = { stroke: k.line, strokeWidth: 2, strokeLinejoin: "round" as const };
  return (
    <svg width={size} height={(size * 168) / 150} viewBox="0 0 150 168" style={{ overflow: "visible", display: "block", ...style }}>
      <ellipse cx="76" cy="154" rx={33 - 5 * h} ry="5" fill="rgba(40,88,70,.14)" />
      <g style={{ transformOrigin: "75px 147px", transform: fig }}>
        <g style={{ transformOrigin: "75px 69px", transform: leaves }}>
          <path d="M76 71C61 51 62 27 74 12C89 35 91 53 76 71Z" fill={k.leaf} {...stroke} />
          <path d="M76 70C53 70 31 51 27 31C51 33 72 45 76 70Z" fill={k.young} {...stroke} />
          <path d="M75 69C78 42 98 25 124 24C119 47 102 67 75 69Z" fill={k.leaf} {...stroke} />
          <path d="M75 68C72 54 71 38 74 24M73 66C62 53 49 44 36 38M78 63C91 47 101 38 114 31" fill="none" stroke={k.vein} strokeWidth={1.4} strokeLinecap="round" opacity={0.72} />
        </g>
        <path d="M63 140L60 150L53 150M88 140L91 150L98 150" fill="none" stroke={k.limb} strokeWidth={4} strokeLinecap="round" strokeLinejoin="round" />
        <path d="M48 107Q33 106 34 94" fill="none" stroke={k.limb} strokeWidth={4} strokeLinecap="round" />
        <path d="M103 107Q115 104 118 93" fill="none" stroke={k.limb} strokeWidth={4} strokeLinecap="round" style={{ transformOrigin: "103px 107px", transform: arm }} />
        <path d="M75 64C60 68 43 91 43 111C43 133 55 145 75 145C96 145 108 132 108 111C108 90 91 68 75 64Z" fill={k.rice} stroke={k.line} strokeWidth={2.4} />
        <path d="M57 94C52 102 51 111 53 118" fill="none" stroke={k.hi} strokeWidth={4} strokeLinecap="round" />
        <ellipse cx="55" cy="119" rx="6" ry="3.7" fill={k.cheek} />
        <ellipse cx="97" cy="119" rx="6" ry="3.7" fill={k.cheek} />
        <g fill={k.line} style={{ transformOrigin: "76px 109px", transform: `scaleY(${eye})` }}>
          <ellipse cx="63" cy="109" rx="2.8" ry="4.1" />
          <ellipse cx="89" cy="109" rx="2.8" ry="4.1" />
        </g>
        <path d={busy ? "M73 122Q76 124 79 122" : "M69 121Q76 129 83 121"} fill="none" stroke={k.line} strokeWidth={2.3} strokeLinecap="round" />
        <path d="M73 133L77 133" fill="none" stroke={k.gold} strokeWidth={2.5} strokeLinecap="round" />
      </g>
      {wave > 0 && wave < 1 && <path d="M123 72L128 67M126 82L134 81" fill="none" stroke={k.gold} strokeWidth={2.5} strokeLinecap="round" opacity={Math.sin(wave * Math.PI)} />}
    </svg>
  );
};

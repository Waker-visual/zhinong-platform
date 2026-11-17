// 由 ≥4 组（地面坐标 → 屏幕坐标）求单应矩阵（最小二乘，DLT + 归一化），用于把地面上的任意点贴到实录画面上
function solve(A, b) { const n = b.length; const M = A.map((r, i) => [...r, b[i]]);
  for (let c = 0; c < n; c++) { let p = c; for (let r = c + 1; r < n; r++) if (Math.abs(M[r][c]) > Math.abs(M[p][c])) p = r; [M[c], M[p]] = [M[p], M[c]];
    for (let r = 0; r < n; r++) if (r !== c) { const f = M[r][c] / M[c][c]; for (let k = c; k <= n; k++) M[r][k] -= f * M[c][k]; } }
  return M.map((r, i) => r[n] / r[i]); }
export function fitH(src, dst) { // src: [[X,Z]], dst: [[u,v]]；h33 = 1
  const AtA = Array.from({ length: 8 }, () => Array(8).fill(0)), Atb = Array(8).fill(0);
  const add = (row, y) => { for (let i = 0; i < 8; i++) { Atb[i] += row[i] * y; for (let j = 0; j < 8; j++) AtA[i][j] += row[i] * row[j]; } };
  src.forEach(([X, Z], i) => { const [u, v] = dst[i]; add([X, Z, 1, 0, 0, 0, -u * X, -u * Z], u); add([0, 0, 0, X, Z, 1, -v * X, -v * Z], v); });
  return [...solve(AtA, Atb), 1]; }
export const applyH = (H, [X, Z]) => { const w = H[6] * X + H[7] * Z + H[8]; return [(H[0] * X + H[1] * Z + H[2]) / w, (H[3] * X + H[4] * Z + H[5]) / w, w]; };

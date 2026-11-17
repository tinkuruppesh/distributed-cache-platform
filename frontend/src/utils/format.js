export const fmtTime = (ms) => (ms ? new Date(ms).toLocaleTimeString([], { hour12: false }) : '–');
export const fmtDateTime = (ms) => (ms ? new Date(ms).toLocaleString([], { hour12: false }) : '–');

export function fmtDuration(ms) {
  if (ms === null || ms === undefined) return '–';
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} s`;
  const m = Math.floor(ms / 60000);
  const s = Math.round((ms % 60000) / 1000);
  if (m < 60) return `${m}m ${s}s`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
}

export function fmtBytes(b) {
  if (b === null || b === undefined) return '–';
  if (b < 1024) return `${b} B`;
  if (b < 1048576) return `${(b / 1024).toFixed(1)} KB`;
  if (b < 1073741824) return `${(b / 1048576).toFixed(1)} MB`;
  return `${(b / 1073741824).toFixed(2)} GB`;
}

export const fmtNum = (n) => (n === null || n === undefined ? '–' : Number(n).toLocaleString());
export const fmtPct = (n, d = 1) => (n === null || n === undefined ? '–' : `${Number(n).toFixed(d)}%`);
export const fmtMs = (n, d = 1) => (n === null || n === undefined ? '–' : `${Number(n).toFixed(d)} ms`);

// one stable colour per node id (used by the hash ring, charts and node chips)
const PALETTE = ['#2563eb', '#16a34a', '#ea8a0c', '#9333ea', '#0891b2', '#dc2626', '#65a30d', '#db2777', '#4f46e5', '#0d9488'];
export function nodeColor(id, order) {
  if (order && order.length) {
    const i = order.indexOf(id);
    if (i >= 0) return PALETTE[i % PALETTE.length];
  }
  let h = 0;
  for (const c of String(id)) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return PALETTE[h % PALETTE.length];
}

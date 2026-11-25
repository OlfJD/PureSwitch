// Pure logic with no browser or phone APIs, so it can be tested on its own (`npm test`).

/** Limits for saved outlets, all in one place. */
export const LIMITS = {
  maxOutlets: 100,
  maxName: 60,
  maxId: 80,
  maxDevId: 64,
  versions: ['', '3.1', '3.2', '3.3', '3.4', '3.5'],
};

// Only private (RFC 1918) IPv4 addresses are allowed, so an outlet can never point at something
// on the public internet. The phone side checks this again.
export function isLocalIPv4(value) {
  const m = String(value).match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (!m) return false;
  const [a, b, c, d] = m.slice(1).map(Number);
  if ([a, b, c, d].some((n) => n > 255)) return false;
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

const str = (v, max) => (typeof v === 'string' ? v.trim().slice(0, max) : '');

/**
 * Checks outlets loaded from storage: anything missing, of the wrong type or out of range is
 * repaired or dropped, so old or damaged saved data can't break the app. Returns the clean list
 * and how many entries were dropped.
 */
export function sanitizeOutlets(saved) {
  if (!Array.isArray(saved)) return { outlets: [], dropped: 0 };
  const outlets = [];
  const ids = new Set();
  let dropped = 0;
  for (const o of saved) {
    const id = o && typeof o === 'object' ? str(o.id, LIMITS.maxId) : '';
    if (!id || ids.has(id) || outlets.length >= LIMITS.maxOutlets) {
      dropped++;
      continue;
    }
    ids.add(id);
    const ip = isLocalIPv4(o.ip) ? o.ip : '';
    const clean = {
      id,
      name: str(o.name, LIMITS.maxName) || 'Outlet',
      isOn: o.isOn === true,
      safetyLock: o.safetyLock === true,
      ip,
      devId: str(o.devId, LIMITS.maxDevId),
      version: LIMITS.versions.includes(o.version) ? o.version : '',
      checkedIp: ip && o.checkedIp === ip ? ip : '',
    };
    if (o.demoKey === true) clean.demoKey = true;
    // Keys saved by early versions; moved into the phone's vault (or dropped in the demo) on start.
    if (typeof o.localKey === 'string' && o.localKey.length === 16) clean.localKey = o.localKey;
    outlets.push(clean);
  }
  return { outlets, dropped };
}

/** The order for "All on": unprotected outlets first, protected ones (a PC) last. */
export function allOnOrder(outlets) {
  return [...outlets].sort((a, b) => Number(!!a.safetyLock) - Number(!!b.safetyLock));
}

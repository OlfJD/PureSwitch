// Everything that talks to real outlets goes through this file.
//
// In the Android app, the native "TuyaLan" plugin (android/app/src/main/java/.../tuya)
// does the network work and keeps local keys encrypted in the Android Keystore.
// In a normal browser there is no plugin, so a demo backend stands in: it pretends
// to find a few outlets and every switch "succeeds". The browser can't protect keys, so the
// demo never stores them - it only remembers that one was entered (`demoKey`).

import { Capacitor, registerPlugin } from '@capacitor/core';

const native = Capacitor.isNativePlatform() ? registerPlugin('TuyaLan') : null;

export const isDemo = !native;

/** Outlet ids that have a key in the native vault. */
let keyIds = new Set();

// Only private (RFC 1918) IPv4 addresses are allowed, so an outlet can never
// point at something on the public internet. The native side checks this again.
export function isLocalIPv4(value) {
  const m = String(value).match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (!m) return false;
  const [a, b, c, d] = m.slice(1).map(Number);
  if ([a, b, c, d].some((n) => n > 255)) return false;
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

/**
 * Call once at startup. On the phone, moves any keys still stored with the outlets into the
 * encrypted vault. Returns true if outlets changed and should be saved.
 */
export async function init(outlets) {
  let changed = false;
  if (!native) {
    // Older demo versions saved real keys in browser storage: drop them.
    for (const o of outlets) {
      if (!('localKey' in o)) continue;
      if (o.localKey) o.demoKey = true;
      delete o.localKey;
      changed = true;
    }
    return changed;
  }
  for (const o of outlets) {
    if (!o.localKey) continue;
    if (typeof o.localKey === 'string' && o.localKey.length === 16) {
      await native.saveKey({ id: o.id, localKey: o.localKey });
    }
    delete o.localKey;
    changed = true;
  }
  const { ids } = await native.listKeys();
  keyIds = new Set(ids);
  return changed;
}

export function hasKey(outlet) {
  return native ? keyIds.has(outlet.id) : !!outlet.demoKey;
}

export async function storeKey(outlet, localKey) {
  if (!native) {
    outlet.demoKey = true; // the key itself is thrown away
    return;
  }
  await native.saveKey({ id: outlet.id, localKey });
  keyIds.add(outlet.id);
}

export async function forgetKey(outlet) {
  if (!native) {
    delete outlet.demoKey;
    return;
  }
  await native.deleteKey({ id: outlet.id });
  keyIds.delete(outlet.id);
}

/** Can this outlet actually be switched? (Demo mode can always "switch".) */
export function isReady(outlet) {
  return missing(outlet).length === 0;
}

/** What this outlet still needs before it can be switched: any of 'ip', 'devId', 'key'. */
export function missing(outlet) {
  if (isDemo) return [];
  const needs = [];
  if (!outlet.ip) needs.push('ip');
  if (!outlet.devId) needs.push('devId');
  if (!hasKey(outlet)) needs.push('key');
  return needs;
}

/**
 * Listen on the Wi-Fi for outlets announcing themselves.
 * Resolves to [{ devId, ip, version }]. Outlets never broadcast their local key.
 */
export async function discover(seconds = 6) {
  if (native) {
    const { devices } = await native.discover({ seconds });
    return devices.filter((d) => isLocalIPv4(d.ip));
  }
  await sleep(2200);
  return DEMO_FOUND.map((d) => ({ ...d }));
}

function target(outlet) {
  if (!isLocalIPv4(outlet.ip)) throw new Error('Not local');
  return { id: outlet.id, ip: outlet.ip, devId: outlet.devId, version: outlet.version || '' };
}

/**
 * Switch an outlet on or off. Resolves to { version } (the protocol that worked);
 * rejects with a short, human-readable message.
 */
export async function setPower(outlet, on) {
  if (native) return native.setPower({ ...target(outlet), on });
  await sleep(300);
  return { version: outlet.version || '' };
}

/** Ask an outlet whether it's on. Resolves to { on, version }. */
export async function getPower(outlet) {
  if (native) return native.getPower(target(outlet));
  return { on: !!outlet.isOn, version: outlet.version || '' };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const DEMO_FOUND = [
  { devId: 'bf3a9c27e10d44f1a8kq2x', ip: '192.168.1.41', version: '3.3' },
  { devId: 'bf71d0e5c3a2b98f64tz0m', ip: '192.168.1.42', version: '3.4' },
  { devId: 'eb5c08f1a9d34e7b20pw4r', ip: '192.168.1.47', version: '3.5' },
];

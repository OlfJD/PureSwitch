import './style.css';
import qrcode from 'qrcode-generator';
import {
  cloudCancel,
  cloudPoll,
  cloudSaveKeys,
  cloudStart,
  discover,
  forgetKey,
  getPower,
  hasKey,
  init,
  isDemo,
  isLocalIPv4,
  isReady,
  missing,
  setPower,
  storeKey,
} from './devices.js';

// Same key as before, so outlets saved by older versions carry over.
const STORAGE_KEY = 'pureswitch_devices_v1';

let outlets = load();
let editing = false;
const busy = new Set(); // outlet ids currently being switched
const errors = new Map(); // outlet id -> { message, token }

function load() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY));
    if (Array.isArray(saved)) return saved;
  } catch {
    // ignore corrupt or unavailable storage
  }
  return [];
}

function save() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(outlets));
  } catch {
    // storage unavailable - changes just won't persist
  }
}

const newId = () => `outlet-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;

/* ---------- Confirm dialog ---------- */

const confirmDialog = document.getElementById('confirmDialog');
let answerConfirm = null; // resolves the question currently on screen

function finishConfirm(result) {
  const answer = answerConfirm;
  answerConfirm = null;
  if (confirmDialog.open) confirmDialog.close();
  answer?.(result);
}

const confirmAlt = document.getElementById('confirmAlt');

/** Resolves to 'ok', 'alt' (the optional middle choice) or null for cancel. */
function askConfirm({ title, text, ok, alt }) {
  finishConfirm(null); // never leave an older question hanging
  document.getElementById('confirmTitle').textContent = title;
  document.getElementById('confirmText').textContent = text;
  document.getElementById('confirmOk').textContent = ok;
  confirmAlt.textContent = alt ?? '';
  confirmAlt.hidden = !alt;
  confirmDialog.classList.toggle('stacked', !!alt);
  confirmDialog.showModal(); // focuses Cancel first, so Enter never cuts power by accident
  return new Promise((resolve) => (answerConfirm = resolve));
}

document.getElementById('confirmOk').addEventListener('click', () => finishConfirm('ok'));
confirmAlt.addEventListener('click', () => finishConfirm('alt'));
document.getElementById('confirmCancel').addEventListener('click', () => finishConfirm(null));
// Escape key, or closed any other way: treat as "no"
confirmDialog.addEventListener('cancel', () => finishConfirm(null));
confirmDialog.addEventListener('close', () => finishConfirm(null));

/* ---------- Rendering ---------- */

const listEl = document.getElementById('list');
const emptyEl = document.getElementById('empty');
const addBtn = document.getElementById('addBtn');
const editBtn = document.getElementById('editBtn');

const LOCK_ICON =
  '<svg class="lock" viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg>';

function stateText(o) {
  if (editing) return 'Edit';
  if (busy.has(o.id)) return '…';
  if (errors.has(o.id)) return errors.get(o.id).message;
  const needs = missing(o);
  if (needs.length === 1 && needs[0] === 'key') return 'Needs key';
  if (needs.length === 1 && needs[0] === 'ip') return 'Not found yet';
  if (needs.length) return 'Set up';
  return o.isOn ? 'On' : 'Off';
}

function render() {
  listEl.replaceChildren(
    ...outlets.map((o) => {
      const li = document.createElement('li');
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'outlet';
      btn.classList.toggle('on', !!o.isOn);
      btn.classList.toggle('busy', busy.has(o.id));
      btn.classList.toggle('error', errors.has(o.id));
      btn.dataset.id = o.id;
      if (!editing && isReady(o)) btn.setAttribute('aria-pressed', String(!!o.isOn));

      const name = document.createElement('span');
      name.className = 'name';
      const label = document.createElement('span');
      label.className = 'label';
      label.textContent = o.name;
      name.append(label);
      if (o.safetyLock) {
        name.insertAdjacentHTML('beforeend', LOCK_ICON);
        name.title = 'Asks before turning off';
      }

      const state = document.createElement('span');
      state.className = 'state';
      state.textContent = stateText(o);

      btn.append(name, state);
      li.append(btn);
      return li;
    })
  );

  const ready = outlets.filter(isReady);
  allBar.hidden = editing || ready.length < 2;
  allOnBtn.disabled = allOnRunning || !ready.some((o) => !o.isOn && !busy.has(o.id));
  allOffBtn.disabled = !ready.some((o) => o.isOn && !busy.has(o.id));

  document.body.classList.toggle('editing', editing);
  editBtn.textContent = editing ? 'Done' : 'Edit';
  emptyEl.hidden = outlets.length > 0;
  addBtn.hidden = !editing && outlets.length > 0;
}

function showError(outlet, message) {
  const token = {};
  errors.set(outlet.id, { message, token });
  setTimeout(() => {
    if (errors.get(outlet.id)?.token !== token) return; // a newer error replaced it
    errors.delete(outlet.id);
    render();
  }, 4000);
}

/**
 * Asks the outlet at `ip` for its state. Only the real plug can answer, because the reply has to
 * be encrypted or signed with its local key (the phone side rejects anything else). On success
 * the outlet's address, state and protocol are updated and marked as checked.
 */
async function verifyAddress(outlet, ip, version) {
  const result = await getPower({ ...outlet, ip, version: version ?? outlet.version });
  outlet.ip = ip;
  outlet.checkedIp = ip;
  outlet.isOn = result.on;
  if (result.version) outlet.version = result.version;
  return result;
}

/** Switch one outlet, showing "…" while it works and an error if it fails. */
async function switchOutlet(outlet, target) {
  if (busy.has(outlet.id)) return;
  busy.add(outlet.id);
  errors.delete(outlet.id);
  render();
  try {
    // Never send a switch command to an address that hasn't proved it's the real outlet.
    // (Older plugs accept a recorded command again later, so it mustn't reach anyone else.)
    if (!isDemo && outlet.checkedIp !== outlet.ip) await verifyAddress(outlet, outlet.ip);
    const { version } = await setPower(outlet, target);
    outlet.isOn = target;
    if (version) outlet.version = version; // remember which protocol worked
    save();
  } catch (err) {
    showError(outlet, err?.message || "Couldn't reach it");
  } finally {
    busy.delete(outlet.id);
    render();
  }
}

async function toggle(outlet) {
  if (busy.has(outlet.id)) return;
  if (outlet.isOn && outlet.safetyLock) {
    const answer = await askConfirm({
      title: `Turn off ${outlet.name}?`,
      text: 'This outlet is protected. Anything plugged into it will lose power.',
      ok: 'Turn off',
    });
    if (answer !== 'ok') return;
  }
  await switchOutlet(outlet, !outlet.isOn);
}

/**
 * Outlets that have their key but no address yet (e.g. added with Connect Smart Life away from
 * home): look for them on the Wi-Fi. Each one still has to prove its key before it's used.
 */
let finding = false;
async function findMissing() {
  const lost = outlets.filter((o) => {
    const needs = missing(o);
    return needs.length === 1 && needs[0] === 'ip';
  });
  if (!lost.length || finding || addDialog.open) return;
  finding = true;
  try {
    const found = await discover();
    await Promise.all(
      found.map(async (d) => {
        const o = lost.find((x) => x.devId === d.devId);
        if (!o || o.ip || busy.has(o.id)) return;
        try {
          await verifyAddress(o, d.ip, o.version || d.version);
        } catch {
          // not the real outlet, or not reachable: try again next time
        }
      })
    );
  } catch {
    // Wi-Fi off or scanning not possible right now
  } finally {
    finding = false;
  }
}

/** Ask every outlet whether it's really on (someone may have pressed the button on the plug). */
async function refreshAll() {
  if (isDemo || editing) return;
  await Promise.all([
    findMissing(),
    ...outlets
      .filter((o) => isReady(o) && !busy.has(o.id))
      .map(async (o) => {
        try {
          await verifyAddress(o, o.ip);
          errors.delete(o.id);
        } catch (err) {
          showError(o, err?.message || 'Offline');
        }
      }),
  ]);
  save();
  render();
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') refreshAll();
});

/* ---------- All on / All off ---------- */

const allBar = document.getElementById('allBar');
const allOnBtn = document.getElementById('allOnBtn');
const allOffBtn = document.getElementById('allOffBtn');

const switchable = () => outlets.filter((o) => isReady(o) && !busy.has(o.id));

// Pause between outlets for "All on". Whatever is plugged in draws a short burst of current when
// power comes on (a PC power supply charging its capacitors, even with the PC off). In older
// installations several bursts at once can trip the breaker, so outlets go on one at a time.
const STAGGER_MS = 2000;
let allOnRunning = false;

allOnBtn.addEventListener('click', async () => {
  if (allOnRunning) return;
  allOnRunning = true;
  render();
  try {
    // Protected outlets (a PC, a 3D printer) go last, after everything else is already on.
    const targets = switchable()
      .filter((o) => !o.isOn)
      .sort((a, b) => Number(!!a.safetyLock) - Number(!!b.safetyLock));
    let first = true;
    for (const o of targets) {
      if (o.isOn || busy.has(o.id)) continue; // changed meanwhile
      if (!first) await sleep(STAGGER_MS);
      first = false;
      await switchOutlet(o, true);
    }
  } finally {
    allOnRunning = false;
    render();
  }
});

allOffBtn.addEventListener('click', async () => {
  let targets = switchable().filter((o) => o.isOn);
  const locked = targets.filter((o) => o.safetyLock);

  if (locked.length) {
    const names = locked.map((o) => o.name).join(', ');
    const onlyLocked = locked.length === targets.length;
    const answer = await askConfirm({
      title: 'Turn off all outlets?',
      text: `${names} ${locked.length === 1 ? 'is' : 'are'} protected. Anything plugged into ${
        locked.length === 1 ? 'it' : 'them'
      } will lose power.`,
      ok: 'Turn off all',
      // "Skip protected" only makes sense when there's something else to turn off
      alt: onlyLocked ? undefined : 'Skip protected',
    });
    if (!answer) return;
    if (answer === 'alt') targets = targets.filter((o) => !o.safetyLock);
    // Re-check: something may have changed while the question was open
    targets = targets.filter((o) => o.isOn && !busy.has(o.id));
  }

  Promise.all(targets.map((o) => switchOutlet(o, false)));
});

listEl.addEventListener('click', (e) => {
  const btn = e.target.closest('.outlet');
  if (!btn) return;
  const outlet = outlets.find((o) => o.id === btn.dataset.id);
  if (!outlet) return;

  if (editing || !isReady(outlet)) openEditor(outlet);
  else toggle(outlet);
});

editBtn.addEventListener('click', () => {
  editing = !editing;
  render();
});

addBtn.addEventListener('click', () => openAddSheet());

/* ---------- Add sheet: scan / import / manual ---------- */

const addDialog = document.getElementById('addDialog');
const scanStatus = document.getElementById('scanStatus');
const scanList = document.getElementById('scanList');
const scanAgainBtn = document.getElementById('scanAgainBtn');
const importFile = document.getElementById('importFile');
let scanRun = 0; // ignores results from a scan the user walked away from

function showView(name) {
  for (const section of addDialog.querySelectorAll('[data-view]')) {
    section.hidden = section.dataset.view !== name;
  }
}

function openAddSheet() {
  showView('choose');
  addDialog.showModal();
}

function showInfo(title, text) {
  document.getElementById('infoTitle').textContent = title;
  document.getElementById('infoText').textContent = text;
  showView('info');
  if (!addDialog.open) addDialog.showModal();
}

addDialog.addEventListener('click', (e) => {
  if (e.target.closest('[data-close]')) addDialog.close();
});
addDialog.addEventListener('close', () => scanRun++);

document.getElementById('manualBtn').addEventListener('click', () => {
  addDialog.close();
  openEditor(null);
});

/* Scan */

document.getElementById('scanBtn').addEventListener('click', () => {
  showView('scan');
  runScan();
});
scanAgainBtn.addEventListener('click', runScan);

async function runScan() {
  const run = ++scanRun;
  scanList.replaceChildren();
  scanStatus.textContent = 'Searching your Wi-Fi…';
  scanStatus.classList.add('searching');
  scanAgainBtn.disabled = true;

  let found = [];
  let failure = null;
  try {
    found = await discover();
  } catch (err) {
    failure = err?.message || "Couldn't scan. Make sure Wi-Fi is on.";
  }
  if (run !== scanRun) return;

  scanStatus.classList.remove('searching');
  scanAgainBtn.disabled = false;

  if (failure) {
    scanStatus.textContent = failure;
    return;
  }
  if (found.length === 0) {
    scanStatus.textContent = 'No outlets found. Make sure your phone is on the same Wi-Fi.';
    return;
  }

  // Outlets we already know may have a new address (routers hand out new ones). Anyone on the
  // Wi-Fi can send a fake announcement, so an outlet that has a key only moves to the new address
  // once the device there proves it has that key. Without a key there's nothing to protect yet,
  // and switching checks the address first anyway (see switchOutlet).
  const unconfirmed = new Set();
  let changed = false;
  await Promise.all(
    found.map(async (d) => {
      const known = outlets.find((o) => o.devId === d.devId);
      if (!known || known.ip === d.ip || busy.has(known.id)) return;
      if (isDemo || !hasKey(known)) {
        known.ip = d.ip;
        if (d.version) known.version = d.version;
        changed = true;
        return;
      }
      try {
        // Keep the protocol we already know works, so a fake can't push us to an older one.
        await verifyAddress(known, d.ip, known.version || d.version);
        changed = true;
      } catch {
        unconfirmed.add(d.devId);
      }
    })
  );
  if (run !== scanRun) {
    if (changed) save();
    return;
  }
  if (changed) {
    save();
    render();
  }

  scanStatus.textContent = `Found ${found.length} outlet${found.length === 1 ? '' : 's'}.`;
  const needKey = found.some((d) => {
    const known = outlets.find((o) => o.devId === d.devId);
    return !isDemo && (!known || !hasKey(known));
  });
  if (needKey) {
    scanStatus.textContent +=
      " To switch them, each also needs its key: a scan can't find it. Use Connect Smart Life.";
  }
  scanList.replaceChildren(...found.map((d) => foundRow(d, unconfirmed.has(d.devId))));
}

function foundRow(d, unconfirmed = false) {
  const known = outlets.find((o) => o.devId === d.devId);
  const li = document.createElement('li');

  const info = document.createElement('div');
  info.className = 'found-info';
  const title = document.createElement('span');
  title.className = 'found-title';
  title.textContent = known ? known.name : 'New outlet';
  const meta = document.createElement('span');
  meta.className = 'hint';
  meta.textContent = `${d.ip} · ID …${d.devId.slice(-6)}`;
  info.append(title, meta);
  li.append(info);

  if (known) {
    const added = document.createElement('span');
    added.className = 'hint';
    added.textContent = unconfirmed ? 'Address not confirmed' : 'Added';
    if (unconfirmed) added.title = `Kept ${known.ip}: the device at ${d.ip} didn't prove it's this outlet.`;
    li.append(added);
  } else {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = 'Add';
    btn.addEventListener('click', () => {
      addDialog.close();
      openEditor(null, { ip: d.ip, devId: d.devId, version: d.version });
    });
    li.append(btn);
  }
  return li;
}

/* Import a tinytuya devices.json (read locally, never uploaded anywhere) */

// Tuya category codes for plugs, power strips, switches and breakers.
const PLUG_CATEGORIES = new Set(['cz', 'pc', 'kg', 'tdq']);

document.getElementById('importBtn').addEventListener('click', () => {
  importFile.value = '';
  importFile.click();
});

importFile.addEventListener('change', async () => {
  const file = importFile.files?.[0];
  if (!file) return;
  let list;
  try {
    list = JSON.parse(await file.text());
    if (!Array.isArray(list)) throw new Error();
  } catch {
    showInfo("Couldn't read file", 'Pick the devices.json file that tinytuya wizard created.');
    return;
  }
  try {
    showInfo('Import done', await importDevices(list));
  } catch {
    showInfo("Couldn't save keys", 'Something went wrong storing the keys. Try again.');
  }
});

async function importDevices(list) {
  let added = 0;
  let updated = 0;
  let skipped = 0;
  let missingIp = 0;

  for (const d of list) {
    if (!d || typeof d.id !== 'string' || typeof d.key !== 'string' || !d.id || d.key.length !== 16) {
      skipped++;
      continue;
    }
    if (d.category && !PLUG_CATEGORIES.has(d.category)) {
      skipped++; // lights, sensors, cameras...
      continue;
    }
    const ip = typeof d.ip === 'string' && isLocalIPv4(d.ip) ? d.ip : '';
    const version = d.version ? String(d.version) : '';
    const known = outlets.find((o) => o.devId === d.id);

    if (known) {
      await storeKey(known, d.key);
      known.checkedIp = ''; // a new key has to be proven again
      if (ip) known.ip = ip;
      if (version) known.version = version;
      updated++;
      if (!known.ip) missingIp++;
    } else {
      const outlet = {
        id: newId(),
        name: (typeof d.name === 'string' && d.name.trim()) || 'Outlet',
        isOn: false,
        safetyLock: false,
        ip,
        devId: d.id,
        version,
      };
      await storeKey(outlet, d.key);
      outlets.push(outlet);
      added++;
      if (!ip) missingIp++;
    }
  }

  save();
  render();

  const parts = [];
  if (added) parts.push(`Added ${added} outlet${added === 1 ? '' : 's'}.`);
  if (updated) parts.push(`Updated ${updated}.`);
  if (!added && !updated) parts.push('No outlets found in this file.');
  if (skipped) parts.push(`Skipped ${skipped} other device${skipped === 1 ? '' : 's'}.`);
  if (missingIp) parts.push('Run "Scan network" to find their addresses.');
  return parts.join(' ');
}

/* ---------- Connect Smart Life ---------- */

const cloudCodeStep = document.getElementById('cloudCodeStep');
const cloudQrStep = document.getElementById('cloudQrStep');
const cloudUserCode = document.getElementById('cloudUserCode');
const cloudError = document.getElementById('cloudError');
const cloudContinue = document.getElementById('cloudContinue');
const cloudQr = document.getElementById('cloudQr');
const cloudStatus = document.getElementById('cloudStatus');
const USER_CODE_KEY = 'pureswitch_user_code'; // not secret: it only identifies the account
const LOGIN_TIMEOUT_MS = 4 * 60 * 1000;
let cloudRun = 0; // ignores a login the user walked away from

function openCloud() {
  cloudRun++;
  cloudCodeStep.hidden = false;
  cloudQrStep.hidden = true;
  cloudError.hidden = true;
  cloudContinue.disabled = false;
  try {
    cloudUserCode.value = localStorage.getItem(USER_CODE_KEY) || '';
  } catch {
    // storage unavailable
  }
  showView('cloud');
  if (!addDialog.open) addDialog.showModal();
  cloudUserCode.focus();
}

document.getElementById('cloudBtn').addEventListener('click', openCloud);

addDialog.addEventListener('close', () => {
  cloudRun++;
  cloudCancel();
});

function cloudFail(message) {
  cloudCodeStep.hidden = false;
  cloudQrStep.hidden = true;
  cloudContinue.disabled = false;
  cloudError.textContent = message;
  cloudError.hidden = false;
}

cloudCodeStep.addEventListener('submit', async (e) => {
  e.preventDefault();
  const code = cloudUserCode.value.trim();
  if (!code) {
    cloudFail('Enter your user code first.');
    return;
  }
  const run = ++cloudRun;
  cloudError.hidden = true;
  cloudContinue.disabled = true;

  let qrText;
  try {
    qrText = await cloudStart(code);
  } catch (err) {
    if (run === cloudRun) cloudFail(err?.message || "Couldn't reach Smart Life. Check your internet connection.");
    return;
  }
  if (run !== cloudRun) return;
  try {
    localStorage.setItem(USER_CODE_KEY, code);
  } catch {
    // storage unavailable
  }

  drawQr(cloudQr, qrText);
  cloudCodeStep.hidden = true;
  cloudQrStep.hidden = false;
  cloudStatus.textContent = 'Waiting for Smart Life…';
  cloudStatus.classList.add('searching');
  waitForLogin(run);
});

async function waitForLogin(run) {
  const deadline = Date.now() + LOGIN_TIMEOUT_MS;
  while (run === cloudRun) {
    if (Date.now() > deadline) {
      cloudCancel();
      cloudFail('The QR code expired. Tap Continue for a new one.');
      return;
    }
    await sleep(2000);
    if (run !== cloudRun) return;
    let devices;
    try {
      devices = await cloudPoll();
    } catch (err) {
      if (run === cloudRun) cloudFail(err?.message || "Couldn't get your outlets from Smart Life.");
      return;
    }
    if (run !== cloudRun) return;
    if (devices) {
      cloudStatus.textContent = 'Connected. Saving your outlets…';
      await finishCloud(devices);
      return;
    }
  }
}

async function finishCloud(devices) {
  // Only plugs, power strips and switches; lights, sensors etc. are left out.
  const plugs = devices.filter((d) => d.hasKey && (!d.category || PLUG_CATEGORIES.has(d.category)));
  const others = devices.length - plugs.length;

  let added = 0;
  const pairs = [];
  for (const d of plugs) {
    let o = outlets.find((x) => x.devId === d.devId);
    if (!o) {
      o = {
        id: newId(),
        name: (d.name || '').trim() || 'Outlet',
        isOn: false,
        safetyLock: false,
        ip: '',
        devId: d.devId,
        version: '',
      };
      outlets.push(o);
      added++;
    }
    pairs.push({ id: o.id, devId: d.devId });
  }

  let saved = [];
  try {
    saved = await cloudSaveKeys(pairs);
  } catch {
    // handled below: nothing saved
  }
  for (const id of saved) {
    const o = outlets.find((x) => x.id === id);
    if (!o) continue;
    o.checkedIp = ''; // a new key has to be proven again
    if (isDemo) o.demoKey = true;
  }
  save();
  render();

  if (!plugs.length) {
    showInfo(
      'No outlets found',
      others
        ? `Your Smart Life account has ${others} other device${others === 1 ? '' : 's'}, but no plugs or power strips.`
        : 'Your Smart Life account has no devices.'
    );
    return;
  }
  if (saved.length < pairs.length) {
    showInfo("Couldn't save keys", 'Something went wrong storing the keys. Try Connect Smart Life again.');
    return;
  }

  // Got the keys. Now find the outlets on the Wi-Fi.
  const updated = pairs.length - added;
  const summary = [
    added ? `Added ${added} outlet${added === 1 ? '' : 's'}.` : '',
    updated ? `Updated the key of ${updated}.` : '',
  ]
    .filter(Boolean)
    .join(' ');
  showView('scan');
  const run = scanRun + 1;
  await runScan();
  if (run !== scanRun || !addDialog.open) return;
  const notHere = outlets.some((o) => pairs.some((p) => p.id === o.id) && !o.ip);
  scanStatus.textContent = `${summary} ${scanStatus.textContent}${
    notHere ? " Outlets that weren't found are found automatically once you're on their Wi-Fi." : ''
  }`;
}

/** Draws a QR code with a white border (scanners need the "quiet zone"). */
function drawQr(canvas, text) {
  const qr = qrcode(0, 'Q');
  qr.addData(text);
  qr.make();
  const n = qr.getModuleCount();
  const size = canvas.width;
  const cell = Math.floor(size / (n + 8));
  const offset = Math.floor((size - cell * n) / 2);
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, size, size);
  ctx.fillStyle = '#000';
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      if (qr.isDark(r, c)) ctx.fillRect(offset + c * cell, offset + r * cell, cell, cell);
    }
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ---------- Edit dialog ---------- */

const dialog = document.getElementById('editDialog');
const form = document.getElementById('editForm');
const fName = document.getElementById('fName');
const fSafety = document.getElementById('fSafety');
const fConnection = document.getElementById('fConnection');
const fIp = document.getElementById('fIp');
const fDevId = document.getElementById('fDevId');
const fKey = document.getElementById('fKey');
const deleteBtn = document.getElementById('deleteBtn');
let current = null; // outlet being edited, or null when adding
let draft = {}; // details found by a scan, for a new outlet

function checkIp() {
  const value = fIp.value.trim();
  fIp.setCustomValidity(
    value && !isLocalIPv4(value)
      ? 'Use a local network address: 192.168.x.x, 10.x.x.x or 172.16–31.x.x'
      : ''
  );
}

fIp.addEventListener('input', checkIp);

function checkKey() {
  const value = fKey.value.trim();
  fKey.setCustomValidity(value && value.length !== 16 ? 'Local keys are 16 characters' : '');
}

fKey.addEventListener('input', checkKey);

// A field inside the collapsed "Connection" section can't show its error, so open it.
form.addEventListener('invalid', (e) => e.target.closest('details')?.setAttribute('open', ''), true);

function openEditor(outlet, found = {}) {
  current = outlet;
  draft = found;
  const src = outlet ?? found;
  document.getElementById('editTitle').textContent = outlet ? 'Edit outlet' : 'New outlet';
  fName.value = outlet?.name ?? '';
  fSafety.checked = !!outlet?.safetyLock;
  fIp.value = src.ip ?? '';
  fDevId.value = src.devId ?? '';
  // A saved key is never shown again; typing a new one replaces it.
  const keySaved = !!outlet && hasKey(outlet);
  fKey.value = '';
  fKey.placeholder = keySaved ? 'Saved (leave empty to keep)' : '16 characters';
  // Open "Connection" when something there still needs filling in
  fConnection.open = !isDemo && !keySaved;
  showMissing(outlet ?? { ...found, id: '' });
  checkIp();
  checkKey();
  deleteBtn.hidden = !outlet;
  dialog.showModal();
  fName.focus();
}

/** Says in the editor why this outlet can't be switched yet, so saving doesn't feel like a no-op. */
const fMissing = document.getElementById('fMissing');
const fMissingText = document.getElementById('fMissingText');

function showMissing(outlet) {
  const needs = missing(outlet);
  fMissing.hidden = needs.length === 0;
  if (!needs.length) return;
  fMissingImport.hidden = !needs.includes('key');
  if (needs.includes('key')) {
    fMissingText.textContent =
      "This outlet can't be switched yet: it needs its key. Outlets don't share their key over " +
      "Wi-Fi, so a scan can't find it. Connect Smart Life gets the keys for all your outlets at once.";
  } else if (needs.length === 1 && needs[0] === 'ip') {
    fMissingText.textContent =
      "This outlet hasn't been found on your Wi-Fi yet. It's found automatically when your phone is " +
      'on the same Wi-Fi, or use Scan network.';
  } else {
    fMissingText.textContent =
      "This outlet can't be switched yet: it needs its IP address and device ID. Use Scan network to fill them in.";
  }
}

const fMissingImport = document.getElementById('fMissingImport');
fMissingImport.addEventListener('click', () => {
  dialog.close();
  openCloud();
});

form.addEventListener('submit', async () => {
  const fields = {
    name: fName.value.trim() || 'Outlet',
    safetyLock: fSafety.checked,
    ip: fIp.value.trim(),
    devId: fDevId.value.trim(),
  };
  let outlet = current;
  if (outlet) {
    // A different device ID or address may mean a different protocol version
    if (outlet.devId !== fields.devId || outlet.ip !== fields.ip) {
      outlet.version = '';
      outlet.checkedIp = '';
    }
    Object.assign(outlet, fields);
  } else {
    outlet = { id: newId(), isOn: false, version: draft.version ?? '', ...fields };
    outlets.push(outlet);
  }
  save();
  render();

  const key = fKey.value.trim();
  if (key) {
    try {
      await storeKey(outlet, key);
      outlet.checkedIp = ''; // a new key has to be proven again
      save();
    } catch {
      showError(outlet, 'Key not saved');
    }
    render();
  }
});

deleteBtn.addEventListener('click', async () => {
  if (!current) return;
  const answer = await askConfirm({
    title: `Remove ${current.name}?`,
    text: 'It will be removed from PureSwitch. The outlet itself is not affected.',
    ok: 'Remove',
  });
  if (answer !== 'ok') return;
  const removed = current;
  outlets = outlets.filter((o) => o !== removed);
  save();
  forgetKey(removed).catch(() => {});
  dialog.close();
  render();
});

document.getElementById('cancelBtn').addEventListener('click', () => dialog.close());

// Tap outside a sheet to close it (a tap on the sheet's own padding doesn't count)
function tappedOutside(e, d) {
  if (e.target !== d) return false;
  const r = d.getBoundingClientRect();
  return e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom;
}
for (const d of [dialog, addDialog]) {
  d.addEventListener('click', (e) => {
    if (tappedOutside(e, d)) d.close();
  });
}
confirmDialog.addEventListener('click', (e) => {
  if (tappedOutside(e, confirmDialog)) finishConfirm(null);
});

document.getElementById('demoNote').hidden = !isDemo;
render();

init(outlets)
  .then((changed) => {
    if (changed) save(); // keys moved into the encrypted vault
    render();
    refreshAll();
  })
  .catch(() => {});

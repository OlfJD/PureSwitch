// Run with `npm test` (Node's built-in test runner, no extra packages).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { allOnOrder, isLocalIPv4, LIMITS, sanitizeOutlets } from '../src/outlets.js';

test('home-network addresses are allowed, everything else is not', () => {
  for (const ip of ['192.168.2.101', '10.0.0.1', '172.16.0.5', '172.31.255.255']) assert.ok(isLocalIPv4(ip), ip);
  for (const ip of ['8.8.8.8', '172.32.0.1', '192.169.0.1', '256.1.1.1', '192.168.1', '192.168.1.1 ', 'x', '', null, undefined, 42]) {
    assert.ok(!isLocalIPv4(ip), String(ip));
  }
});

test('a normal saved outlet comes through unchanged', () => {
  const o = { id: 'outlet-1', name: 'PC', isOn: true, safetyLock: true, ip: '192.168.2.101', devId: 'bf123abc', version: '3.4', checkedIp: '192.168.2.101' };
  assert.deepEqual(sanitizeOutlets([o]), { outlets: [o], dropped: 0 });
});

test('anything that is not a list gives an empty list', () => {
  for (const v of [null, undefined, {}, 'text', 5, true]) assert.deepEqual(sanitizeOutlets(v), { outlets: [], dropped: 0 });
});

test('entries without an id, duplicates and non-objects are dropped', () => {
  const { outlets, dropped } = sanitizeOutlets([null, 7, 'x', [], {}, { id: '' }, { id: 'a' }, { id: 'a' }]);
  assert.deepEqual(outlets.map((o) => o.id), ['a']);
  assert.equal(dropped, 7);
});

test('wrong types and absurd values are repaired', () => {
  const [o] = sanitizeOutlets([
    { id: 'b', name: 'x'.repeat(500), isOn: 'yes', safetyLock: 1, ip: '8.8.8.8', devId: { a: 1 }, version: '9.9', checkedIp: '8.8.8.8' },
  ]).outlets;
  assert.equal(o.name.length, LIMITS.maxName);
  assert.equal(o.isOn, false);
  assert.equal(o.safetyLock, false);
  assert.equal(o.ip, '');
  assert.equal(o.devId, '');
  assert.equal(o.version, '');
  assert.equal(o.checkedIp, '');
});

test('an empty name becomes "Outlet"', () => {
  assert.equal(sanitizeOutlets([{ id: 'c', name: '   ' }]).outlets[0].name, 'Outlet');
});

test('a checked address only counts for the current address', () => {
  const [o] = sanitizeOutlets([{ id: 'd', ip: '192.168.2.5', checkedIp: '192.168.2.9' }]).outlets;
  assert.equal(o.checkedIp, '');
});

test('the number of outlets is capped', () => {
  const many = Array.from({ length: LIMITS.maxOutlets + 5 }, (_, i) => ({ id: `o${i}` }));
  const { outlets, dropped } = sanitizeOutlets(many);
  assert.equal(outlets.length, LIMITS.maxOutlets);
  assert.equal(dropped, 5);
});

test('old saved keys are kept only if they look like keys', () => {
  const [a, b] = sanitizeOutlets([
    { id: 'e', localKey: '0123456789abcdef' },
    { id: 'f', localKey: 'short' },
  ]).outlets;
  assert.equal(a.localKey, '0123456789abcdef');
  assert.equal('localKey' in b, false);
});

test('All on switches protected outlets last and keeps the order otherwise', () => {
  const order = allOnOrder([
    { id: 'pc', safetyLock: true },
    { id: 'lamp' },
    { id: 'printer', safetyLock: true },
    { id: 'fan', safetyLock: false },
  ]).map((o) => o.id);
  assert.deepEqual(order, ['lamp', 'fan', 'pc', 'printer']);
});

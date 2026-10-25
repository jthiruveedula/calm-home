'use strict';
// Unit tests for the CLI's pure helpers. Run: npm test
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const lib = require('./lib');

let n = 0;
const t = (name, fn) => {
  try {
    fn();
    n++;
  } catch (e) {
    console.error('FAIL: ' + name + '\n  ' + e.message);
    process.exitCode = 1;
  }
};

t('parseArgs: command, positionals, flags', () => {
  const a = lib.parseArgs(['add', 'smarttube', '--device', '10.0.0.5', '--yes', '--subnet=192.168.1.1', '-y']);
  assert.strictEqual(a.cmd, 'add');
  assert.deepStrictEqual(a.args, ['smarttube']);
  assert.strictEqual(a.flags.device, '10.0.0.5');
  assert.strictEqual(a.flags.yes, true);
  assert.strictEqual(a.flags.subnet, '192.168.1.1');
});
t('parseArgs: boolean flag does not swallow the next word', () => {
  const a = lib.parseArgs(['debloat', '--dry-run', 'extra']);
  assert.strictEqual(a.flags['dry-run'], true);
  assert.deepStrictEqual(a.args, ['extra']);
});
t('parseArgs: -d shorthand and empty argv', () => {
  assert.strictEqual(lib.parseArgs(['status', '-d', '1.2.3.4']).flags.device, '1.2.3.4');
  assert.strictEqual(lib.parseArgs([]).cmd, null);
});

t('normalizeTarget', () => {
  assert.strictEqual(lib.normalizeTarget('192.168.1.146'), '192.168.1.146:5555');
  assert.strictEqual(lib.normalizeTarget(' 10.0.0.2:5037 '), '10.0.0.2:5037');
  for (const bad of ['', null, 'tv.local', '1.2.3', '300.1.1.1', '1.2.3.4:0', '1.2.3.4:99999', '1.2.3.4; rm -rf /']) {
    assert.strictEqual(lib.normalizeTarget(bad), null, String(bad));
  }
});

t('isPrivateIPv4 and subnetHosts', () => {
  assert.ok(lib.isPrivateIPv4('192.168.1.10') && lib.isPrivateIPv4('10.1.2.3') && lib.isPrivateIPv4('172.20.10.4'));
  assert.ok(!lib.isPrivateIPv4('8.8.8.8') && !lib.isPrivateIPv4('172.32.0.1') && !lib.isPrivateIPv4('x'));
  const h = lib.subnetHosts('192.168.1.236');
  assert.strictEqual(h.length, 253);
  assert.ok(!h.includes('192.168.1.236') && !h.includes('192.168.1.0') && !h.includes('192.168.1.255'));
  assert.deepStrictEqual(lib.subnetHosts('8.8.8.8'), [], 'never scans public networks');
});

t('parseDevices', () => {
  const out = 'List of devices attached\n192.168.1.146:5555\tdevice\nemulator-5554\toffline\n* daemon started\n\n';
  assert.deepStrictEqual(lib.parseDevices(out), [
    { serial: '192.168.1.146:5555', state: 'device' },
    { serial: 'emulator-5554', state: 'offline' },
  ]);
  assert.deepStrictEqual(lib.parseDevices(''), []);
});

t('deviceKind', () => {
  assert.strictEqual(lib.deviceKind('Amazon', true), 'firetv');
  assert.strictEqual(lib.deviceKind(' amazon ', false), 'firetv');
  assert.strictEqual(lib.deviceKind('Google', true), 'androidtv');
  assert.strictEqual(lib.deviceKind('Xiaomi', false), 'android');
});

t('preferredAbis', () => {
  assert.deepStrictEqual(lib.preferredAbis('armeabi-v7a,armeabi'), ['armeabi-v7a']);
  assert.deepStrictEqual(lib.preferredAbis('arm64-v8a,armeabi-v7a,armeabi'), ['arm64-v8a', 'armeabi-v7a']);
  assert.deepStrictEqual(lib.preferredAbis(''), ['armeabi-v7a']);
});

const smart = [
  'SmartTube_stable_32.68_arm64-v8a.apk', 'SmartTube_stable_32.68_armeabi-v7a.apk',
  'SmartTube_stable_32.68_universal.apk', 'SmartTube_stable_32.68_x86.apk', 'SmartTube_beta_32.70_armeabi-v7a.apk',
].map((name) => ({ name }));
const pattern = require('./catalog.json').smarttube.asset;

t('pickAsset: exact ABI, preference order, universal fallback, stable only', () => {
  assert.strictEqual(lib.pickAsset(smart, pattern, ['armeabi-v7a']).name, 'SmartTube_stable_32.68_armeabi-v7a.apk');
  assert.strictEqual(lib.pickAsset(smart, pattern, ['arm64-v8a', 'armeabi-v7a']).name, 'SmartTube_stable_32.68_arm64-v8a.apk');
  assert.strictEqual(lib.pickAsset(smart, pattern, ['x86_64']).name, 'SmartTube_stable_32.68_universal.apk');
  assert.strictEqual(lib.pickAsset([{ name: 'SmartTube_beta_1.0_x86.apk' }], pattern, ['x86']), null);
  assert.strictEqual(lib.pickAsset([], pattern, ['x86']), null);
  const calm = require('./catalog.json')['calm-home'].asset;
  assert.strictEqual(lib.pickAsset([{ name: 'CalmHome.apk.sha256' }, { name: 'CalmHome.apk' }], calm, ['armeabi-v7a']).name, 'CalmHome.apk');
});

t('shaFromNotes: SmartTube VirusTotal table and plain sums', () => {
  const sha = '2130b23b17ed5cfd324baec81750a2be22059212b6ee17449f21cae2cb3a60d9';
  const body = '| Build Variant | VirusTotal Status | Detailed Report |\n' +
    `| [SmartTube_stable_32.68_armeabi-v7a.apk](https://github.com/x) | [![](badge)](https://www.virustotal.com/gui/file/${sha}/detection) | [View Report](https://www.virustotal.com/gui/file/${sha}/detection) |\n` +
    '| [SmartTube_stable_32.68_x86.apk](u) | [![](b)](https://www.virustotal.com/gui/file/67d7d9b9e4dd629331e751e47f5e1e23dd0d8fa5e34c6d901ba0b97eaa002781/detection) |';
  assert.strictEqual(lib.shaFromNotes(body, 'SmartTube_stable_32.68_armeabi-v7a.apk'), sha);
  assert.strictEqual(lib.shaFromNotes(body.replace(/_/g, '\\_'), 'SmartTube_stable_32.68_armeabi-v7a.apk'), sha, 'escaped underscores');
  assert.strictEqual(lib.shaFromNotes(`${sha.toUpperCase()}  app.apk`, 'app.apk'), sha);
  assert.strictEqual(lib.shaFromNotes(body, 'Other.apk'), null);
  assert.strictEqual(lib.shaFromNotes(null, 'a.apk'), null);
});

t('parseShaFile', () => {
  assert.strictEqual(lib.parseShaFile('ab'.repeat(32) + '  CalmHome.apk\n'), 'ab'.repeat(32));
  assert.strictEqual(lib.parseShaFile('nope'), null);
});

t('debloat list is well-formed and never touches essentials', () => {
  const list = lib.parsePackageList(fs.readFileSync(path.join(__dirname, 'debloat-firetv.txt'), 'utf8'));
  assert.strictEqual(list.length, 45);
  const essential = ['com.amazon.tv.launcher', 'com.amazon.venezia', 'com.amazon.tv.settings.v2', 'com.amazon.firebat',
    'com.amazon.device.software.ota', 'com.amazon.tv.oobe', 'com.android.systemui', 'com.amazon.tv.ime',
    'com.amazon.cloud9', 'com.amazon.bueller.photos', 'com.amazon.tahoe'];
  for (const e of essential) assert.ok(!list.includes(e), 'must keep ' + e);
});

t('parsePackageList and debloatPlan', () => {
  assert.deepStrictEqual(lib.parsePackageList('# c\na.b\n\na.b\nbad line\nc.d # note\n'), ['a.b', 'c.d']);
  const inst = lib.parsePmList('package:a.b\npackage:c.d\npackage:e.f\n');
  const en = lib.parsePmList('package:a.b\npackage:e.f');
  assert.deepStrictEqual(lib.debloatPlan(['a.b', 'c.d', 'x.y', 'e.f'], inst, en), ['a.b', 'e.f']);
});

t('catalog entries are complete', () => {
  for (const [id, a] of Object.entries(require('./catalog.json'))) {
    for (const k of ['name', 'about', 'repo', 'package', 'asset', 'checksum']) assert.ok(a[k], `${id}.${k}`);
    assert.ok(/^[\w.-]+\/[\w.-]+$/.test(a.repo), id + ' repo');
    assert.doesNotThrow(() => new RegExp(a.asset.replace('{abi}', 'x')), id + ' pattern');
  }
});

console.log(`${n} CLI tests passed` + (process.exitCode ? ' (with failures above)' : ''));

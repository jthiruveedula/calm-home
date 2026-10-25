#!/usr/bin/env node
'use strict';
// calm-home: install Calm Home and friends on a Fire TV or Android TV over Wi-Fi ADB.
// Zero dependencies. Node 18+. Run with: npx github:jthiruveedula/calm-home <command>

const fs = require('fs');
const os = require('os');
const path = require('path');
const net = require('net');
const crypto = require('crypto');
const readline = require('readline');
const { spawnSync } = require('child_process');
const lib = require('./lib');

const VERSION = require('../package.json').version;
const HOME = path.join(os.homedir(), '.calm-home');
const CATALOG = require('./catalog.json');
const tty = process.stdout.isTTY;
const c = (code) => (s) => (tty ? `\x1b[${code}m${s}\x1b[0m` : String(s));
const bold = c(1), dim = c(2), green = c(32), red = c(31), yellow = c(33), cyan = c(36);

const say = (...a) => console.log(...a);
const ok = (m) => say(green('✓ ') + m);
const warn = (m) => say(yellow('! ') + m);
class Fail extends Error {}
const fail = (m) => { throw new Fail(m); };

// ---- adb ------------------------------------------------------------------------------------

let ADB = null;

function run(bin, args, opts = {}) {
  const r = spawnSync(bin, args, { encoding: 'utf8', timeout: opts.timeout || 120000, maxBuffer: 64 << 20 });
  return { code: r.status, out: (r.stdout || '') + (r.stderr || ''), error: r.error };
}

function adbWorks(bin) {
  const r = run(bin, ['version'], { timeout: 15000 });
  return !r.error && r.code === 0 && /Android Debug Bridge/.test(r.out);
}

async function findAdb(flags) {
  const local = path.join(HOME, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  for (const cand of [flags.adb, process.env.ADB, 'adb', local]) {
    if (cand && adbWorks(cand)) return cand;
  }
  const plat = { darwin: 'darwin', linux: 'linux', win32: 'windows' }[process.platform];
  if (!plat) fail('adb not found. Install Android platform-tools and put adb on your PATH.');
  warn('adb (Android platform-tools) is not installed.');
  if (!(await confirm(flags, 'Download the official platform-tools from Google into ~/.calm-home?'))) {
    fail('adb is required. Install platform-tools, or rerun and answer yes.');
  }
  const url = `https://dl.google.com/android/repository/platform-tools-latest-${plat}.zip`;
  const zip = path.join(HOME, 'platform-tools.zip');
  await download(url, zip);
  const r = process.platform === 'win32'
    ? run('tar', ['-xf', zip, '-C', HOME])
    : run('unzip', ['-qo', zip, '-d', HOME]);
  if (r.code !== 0) fail('Could not unzip platform-tools: ' + r.out.trim());
  fs.rmSync(zip, { force: true });
  if (!adbWorks(local)) fail('Downloaded platform-tools, but adb does not run here.');
  ok('platform-tools installed in ' + path.join(HOME, 'platform-tools'));
  return local;
}

function adb(args, opts) {
  return run(ADB, args, opts);
}

function shell(serial, cmd, opts) {
  return adb(['-s', serial, 'shell', cmd], opts).out.trim();
}

function devices() {
  return lib.parseDevices(adb(['devices']).out);
}

async function connect(target) {
  const t = lib.normalizeTarget(target);
  if (!t) fail(`"${target}" is not an IP address like 192.168.1.20`);
  let state = '';
  for (let i = 0; i < 20; i++) {
    adb(['connect', t], { timeout: 15000 });
    state = (devices().find((d) => d.serial === t) || {}).state || '';
    if (state === 'device') return t;
    if (i === 0 && state) say(dim(`  waiting for the TV (state: ${state}). If a prompt is on screen, tick "Always allow" and press OK.`));
    if (state === 'offline' && i % 4 === 3) adb(['disconnect', t]);
    await sleep(1500);
  }
  fail(`Could not connect to ${t} (state: ${state || 'none'}).\n` +
    '  On the TV: Settings > My Fire TV (or Device Preferences) > Developer options > ADB debugging on.\n' +
    '  If it is already on, turn it off and on again, then accept the prompt.');
}

/** The device to use: --device, the only connected one, or one found by scanning. */
async function pickDevice(flags) {
  if (flags.device) return connect(flags.device);
  const ready = devices().filter((d) => d.state === 'device');
  if (ready.length === 1) return ready[0].serial;
  if (ready.length > 1) fail('Several devices are connected. Choose one with --device <ip>:\n  ' + ready.map((d) => d.serial).join('\n  '));
  say(dim('No device given; looking for TVs with ADB on this network...'));
  const found = await scanNetwork(flags);
  if (found.length === 0) fail('No TV with network ADB found. Pass --device <ip> (find it in Settings > My Fire TV > About > Network).');
  if (found.length > 1) fail('Several TVs found. Choose one with --device:\n  ' + found.join('\n  '));
  return connect(found[0]);
}

// ---- device info ----------------------------------------------------------------------------

function info(serial) {
  const props = {};
  for (const k of ['ro.product.manufacturer', 'ro.product.model', 'ro.build.version.release',
    'ro.product.cpu.abilist', 'ro.build.version.sdk', 'net.dns1', 'net.dns2']) {
    props[k] = shell(serial, `getprop ${k}`);
  }
  const leanback = /android\.software\.leanback/.test(shell(serial, 'pm list features'));
  return {
    serial,
    kind: lib.deviceKind(props['ro.product.manufacturer'], leanback),
    model: props['ro.product.model'],
    android: props['ro.build.version.release'],
    sdk: Number(props['ro.build.version.sdk']) || 0,
    abis: lib.preferredAbis(props['ro.product.cpu.abilist']),
    dns: [props['net.dns1'], props['net.dns2']].filter(Boolean),
  };
}

const KIND = { firetv: 'Fire TV', androidtv: 'Android TV', android: 'Android phone/tablet' };

function versionOf(serial, pkg) {
  const m = shell(serial, `dumpsys package ${pkg}`).match(/versionName=(\S+)/);
  return m ? m[1] : null;
}

// ---- network --------------------------------------------------------------------------------

function localIPv4s() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) if (a.family === 'IPv4' && !a.internal && lib.isPrivateIPv4(a.address)) out.push(a.address);
  }
  return [...new Set(out)];
}

function probe(host, port, ms) {
  return new Promise((resolve) => {
    const s = net.connect({ host, port });
    const done = (v) => { s.destroy(); resolve(v); };
    s.setTimeout(ms, () => done(false));
    s.once('connect', () => done(true));
    s.once('error', () => done(false));
  });
}

async function scanNetwork(flags) {
  const bases = flags.subnet ? [flags.subnet] : localIPv4s();
  if (!bases.length) fail('Not on a private Wi-Fi/LAN network.');
  const hosts = [...new Set(bases.flatMap((ip) => lib.subnetHosts(ip)))];
  const found = [];
  for (let i = 0; i < hosts.length; i += 64) {
    const batch = hosts.slice(i, i + 64);
    const res = await Promise.all(batch.map((h) => probe(h, 5555, 600)));
    batch.forEach((h, j) => res[j] && found.push(`${h}:5555`));
  }
  return found;
}

// ---- downloads ------------------------------------------------------------------------------

async function getJson(url) {
  const r = await fetch(url, { headers: { 'User-Agent': 'calm-home-cli', Accept: 'application/vnd.github+json' } });
  if (!r.ok) fail(`GitHub said ${r.status} for ${url}`);
  return r.json();
}

async function download(url, file) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const r = await fetch(url, { headers: { 'User-Agent': 'calm-home-cli' } });
  if (!r.ok) fail(`Download failed (${r.status}): ${url}`);
  const buf = Buffer.from(await r.arrayBuffer());
  fs.writeFileSync(file, buf);
  return buf;
}

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

/** Downloads a catalog app's APK for this device and verifies its published SHA-256. */
async function fetchApp(id, dev, flags) {
  const app = CATALOG[id];
  const rel = await getJson(`https://api.github.com/repos/${app.repo}/releases/latest`);
  const asset = lib.pickAsset(rel.assets, app.asset, dev.abis);
  if (!asset) fail(`${app.name} ${rel.tag_name} has no APK for ${dev.abis.join('/')}.`);
  say(dim(`  ${app.name} ${rel.tag_name}: ${asset.name} (${(asset.size / 1048576).toFixed(1)} MB)`));
  const file = path.join(HOME, 'cache', asset.name);
  const buf = await download(asset.browser_download_url, file);
  let want = null;
  if (app.checksum === 'sha256-asset') {
    const sumAsset = rel.assets.find((a) => a.name === asset.name + '.sha256');
    if (sumAsset) want = lib.parseShaFile(await (await fetch(sumAsset.browser_download_url)).text());
  } else if (app.checksum === 'release-notes') {
    want = lib.shaFromNotes(rel.body, asset.name);
  }
  const got = sha256(buf);
  if (want && want !== got) fail(`Checksum mismatch for ${asset.name}: expected ${want}, got ${got}. Not installing.`);
  if (want) ok(`Checksum verified (${got.slice(0, 12)}...)`);
  else if (!flags['allow-unverified']) fail(`No published checksum for ${asset.name}; rerun with --allow-unverified to install anyway.`);
  else warn('No published checksum; installing unverified because you asked.');
  return file;
}

function installApk(serial, file) {
  const r = adb(['-s', serial, 'install', '-r', file], { timeout: 300000 });
  if (!/Success/.test(r.out)) fail('Install failed: ' + r.out.trim().split('\n').pop());
}

// ---- prompts ----------------------------------------------------------------------------------

async function confirm(flags, q) {
  if (flags.yes) return true;
  if (!process.stdin.isTTY) return false;
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const a = await new Promise((res) => rl.question(`${q} [y/N] `, res));
  rl.close();
  return /^y(es)?$/i.test(a.trim());
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- commands ---------------------------------------------------------------------------------

const commands = {
  async scan(a) {
    const found = await scanNetwork(a.flags);
    if (!found.length) return say('No devices with network ADB found on ' + (a.flags.subnet || localIPv4s().join(', ')));
    say(bold('Devices with ADB on port 5555:'));
    found.forEach((f) => say('  ' + f));
    say(dim('Install with: npx github:jthiruveedula/calm-home install --device ' + found[0].replace(':5555', '')));
  },

  async connect(a) {
    const s = await connect(a.args[0] || a.flags.device || fail('Usage: calm-home connect <ip>'));
    const d = info(s);
    ok(`Connected to ${d.model} (${KIND[d.kind]}, Android ${d.android})`);
  },

  async status(a) {
    const s = await pickDevice(a.flags);
    const d = info(s);
    say(bold(`${d.model}`) + dim(`  ${s}`));
    say(`  Type        ${KIND[d.kind]}, Android ${d.android} (SDK ${d.sdk}), ${d.abis[0]}`);
    say(`  DNS         ${d.dns.join(', ') || 'automatic'}${d.dns.some((x) => /^94\.140\./.test(x)) ? green('  (AdGuard ad blocking)') : ''}`);
    for (const [id, app] of Object.entries(CATALOG)) {
      const v = versionOf(s, app.package);
      say(`  ${app.name.padEnd(11)} ${v ? green('installed ' + v) : dim('not installed')}`);
    }
    if (d.kind === 'firetv') {
      const list = lib.parsePackageList(fs.readFileSync(path.join(__dirname, 'debloat-firetv.txt'), 'utf8'));
      const disabled = lib.parsePmList(shell(s, 'pm list packages -d'));
      say(`  Debloat     ${list.filter((p) => disabled.has(p)).length} of ${list.length} listed packages disabled`);
    }
  },

  async install(a) {
    const s = await pickDevice(a.flags);
    const d = info(s);
    say(`Installing Calm Home on ${bold(d.model)} (${KIND[d.kind]})`);
    if (d.sdk && d.sdk < 22) fail('Calm Home needs Android 5.1 or newer.');
    const file = a.flags.apk ? path.resolve(a.flags.apk) : await fetchApp('calm-home', d, a.flags);
    installApk(s, file);
    ok(`Calm Home ${versionOf(s, CATALOG['calm-home'].package) || ''} installed`);
    if (d.kind === 'firetv') say(dim('  Open it from Your Apps. Fire OS keeps Amazon\'s Home button; restart the TV if its tile shows a placeholder.'));
    else say(dim('  Press Home and pick Calm Home to make it your launcher.'));
    if (a.flags.launch !== false) shell(s, 'am start -n com.jay.calmhome/.MainActivity');
  },

  async apps() {
    say(bold('Catalog') + dim('  (install with: calm-home add <id>)'));
    for (const [id, app] of Object.entries(CATALOG)) say(`  ${cyan(id.padEnd(10))} ${app.name}: ${app.about}`);
  },

  async add(a) {
    const id = (a.args[0] || '').toLowerCase();
    if (!CATALOG[id]) fail(`Unknown app "${id}". Try: calm-home apps`);
    const s = await pickDevice(a.flags);
    const d = info(s);
    const app = CATALOG[id];
    say(`Installing ${bold(app.name)} on ${d.model} (${d.abis[0]})`);
    if (app.note) say(dim('  ' + app.note));
    installApk(s, await fetchApp(id, d, a.flags));
    ok(`${app.name} ${versionOf(s, app.package) || ''} installed`);
  },

  async sideload(a) {
    const file = a.args[0] || fail('Usage: calm-home sideload <file.apk>');
    if (!fs.existsSync(file)) fail('No such file: ' + file);
    const s = await pickDevice(a.flags);
    installApk(s, path.resolve(file));
    ok(path.basename(file) + ' installed');
  },

  async uninstall(a) {
    const id = (a.args[0] || 'calm-home').toLowerCase();
    const app = CATALOG[id] || fail(`Unknown app "${id}".`);
    const s = await pickDevice(a.flags);
    const r = adb(['-s', s, 'uninstall', app.package]);
    if (/Success/.test(r.out)) ok(app.name + ' removed');
    else fail('Uninstall failed: ' + r.out.trim());
  },

  async debloat(a) {
    const s = await pickDevice(a.flags);
    const d = info(s);
    if (d.kind !== 'firetv') fail(`Debloat is for Fire TV only; this is ${KIND[d.kind]}. Nothing changed.`);
    const list = lib.parsePackageList(fs.readFileSync(path.join(__dirname, 'debloat-firetv.txt'), 'utf8'));
    const installed = lib.parsePmList(shell(s, 'pm list packages'));
    const enabled = lib.parsePmList(shell(s, 'pm list packages -e'));
    const plan = lib.debloatPlan(list, installed, enabled);
    if (!plan.length) return ok('Already debloated: nothing left to disable.');
    say(`Will disable ${bold(plan.length)} Amazon packages (telemetry, ads, promos, bundled extras).`);
    say(dim('  Reversible with: calm-home restore. Nothing is uninstalled.'));
    if (a.flags['dry-run']) return plan.forEach((p) => say('  ' + p));
    if (!(await confirm(a.flags, 'Continue?'))) return say('Cancelled.');
    const backupDir = path.join(HOME, 'backups');
    fs.mkdirSync(backupDir, { recursive: true });
    const done = [];
    const failed = [];
    for (const p of plan) {
      const out = shell(s, `pm disable-user --user 0 ${p}`);
      (/disabled/.test(out) ? done : failed).push(p);
    }
    const file = path.join(backupDir, `${d.model.replace(/\W+/g, '_')}-${Date.now()}.json`);
    fs.writeFileSync(file, JSON.stringify({ device: s, model: d.model, at: new Date().toISOString(), disabled: done }, null, 2));
    ok(`Disabled ${done.length} packages. Backup: ${file}`);
    if (failed.length) warn(`${failed.length} could not be disabled (protected on this build): ${failed.join(', ')}`);
  },

  async restore(a) {
    const s = await pickDevice(a.flags);
    let pkgs;
    if (a.flags.all) {
      pkgs = lib.parsePackageList(fs.readFileSync(path.join(__dirname, 'debloat-firetv.txt'), 'utf8'));
    } else {
      const dir = path.join(HOME, 'backups');
      const files = a.flags.backup ? [a.flags.backup]
        : (fs.existsSync(dir) ? fs.readdirSync(dir).filter((f) => f.endsWith('.json')).sort().map((f) => path.join(dir, f)) : []);
      if (!files.length) fail('No debloat backup found. Use --all to re-enable every package on the list.');
      pkgs = JSON.parse(fs.readFileSync(files[files.length - 1], 'utf8')).disabled;
    }
    let n = 0;
    for (const p of pkgs) if (/enabled/.test(shell(s, `pm enable ${p}`))) n++;
    ok(`Re-enabled ${n} packages. Restart the TV to finish.`);
  },

  async help() {
    say(`${bold('calm-home')} ${VERSION}: an ad-free home screen and tools for Fire TV and Android TV

${bold('Usage')}  npx github:jthiruveedula/calm-home <command> [--device <ip>] [--yes]

${bold('Commands')}
  scan                  find TVs with network ADB on your Wi-Fi
  connect <ip>          connect and accept the TV's prompt
  status                device, DNS, installed apps, debloat state
  install               download and install the latest Calm Home  (--apk <file> for a local build)
  apps                  list the app catalog
  add <app>             install a catalog app, e.g. smarttube (right CPU build, checksum verified)
  sideload <file.apk>   install any APK
  uninstall [app]       remove Calm Home (or a catalog app)
  debloat               disable Amazon telemetry, ads and promos (Fire TV; reversible; --dry-run)
  restore               undo the last debloat (--all re-enables the whole list)

${bold('First time')}
  On the TV: Settings > My Fire TV > Developer options > ADB debugging ON
  (Android TV: Settings > Device Preferences > About > tap Build 7 times, then Developer options).
  Then: npx github:jthiruveedula/calm-home install`);
  },
};

async function main() {
  const a = lib.parseArgs(process.argv.slice(2));
  if (a.flags.version) return say(VERSION);
  const cmd = a.cmd || 'help';
  if (a.flags.help || cmd === 'help' || !commands[cmd]) {
    if (a.cmd && !commands[cmd]) warn(`Unknown command "${cmd}"`);
    return commands.help(a);
  }
  if (!['apps', 'help'].includes(cmd)) ADB = await findAdb(a.flags);
  await commands[cmd](a);
}

main().catch((e) => {
  console.error(red('✗ ') + (e instanceof Fail ? e.message : e.stack || e));
  process.exit(1);
});

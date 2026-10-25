'use strict';
// Pure helpers for the calm-home CLI. No I/O here, so every function is unit-tested in cli/test.js.

const ABIS = ['arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'];

/** Splits argv into a command, positional args and --flags (--k v, --k=v, --flag). */
function parseArgs(argv) {
  const out = { cmd: null, args: [], flags: {} };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const eq = a.indexOf('=');
      if (eq > 0) {
        out.flags[a.slice(2, eq)] = a.slice(eq + 1);
      } else {
        const key = a.slice(2);
        const next = argv[i + 1];
        if (next !== undefined && !next.startsWith('-') && VALUE_FLAGS.has(key)) {
          out.flags[key] = next;
          i++;
        } else {
          out.flags[key] = true;
        }
      }
    } else if (a === '-y') {
      out.flags.yes = true;
    } else if (a === '-d') {
      out.flags.device = argv[++i];
    } else if (out.cmd === null) {
      out.cmd = a;
    } else {
      out.args.push(a);
    }
  }
  return out;
}
const VALUE_FLAGS = new Set(['device', 'apk', 'adb', 'subnet', 'backup']);

/** "192.168.1.146" -> "192.168.1.146:5555"; keeps an explicit port; rejects junk. */
function normalizeTarget(t) {
  if (!t || typeof t !== 'string') return null;
  const m = t.trim().match(/^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?$/);
  if (!m) return null;
  if (m[1].split('.').some((o) => Number(o) > 255)) return null;
  const port = m[2] ? Number(m[2]) : 5555;
  if (port < 1 || port > 65535) return null;
  return `${m[1]}:${port}`;
}

/** True for RFC 1918 addresses: the only networks scan will touch. */
function isPrivateIPv4(ip) {
  const p = String(ip).split('.').map(Number);
  if (p.length !== 4 || p.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return false;
  return p[0] === 10 || (p[0] === 172 && p[1] >= 16 && p[1] <= 31) || (p[0] === 192 && p[1] === 168);
}

/** Every host of the /24 around ip except itself, network and broadcast. */
function subnetHosts(ip) {
  if (!isPrivateIPv4(ip)) return [];
  const p = ip.split('.');
  const base = p.slice(0, 3).join('.');
  const hosts = [];
  for (let i = 1; i < 255; i++) if (String(i) !== p[3]) hosts.push(`${base}.${i}`);
  return hosts;
}

/** Parses `adb devices` output into [{serial, state}]. */
function parseDevices(text) {
  return String(text)
    .split(/\r?\n/)
    .slice(1)
    .map((l) => l.trim().split(/\s+/))
    .filter((p) => p.length >= 2 && p[0] && !p[0].startsWith('*'))
    .map(([serial, state]) => ({ serial, state }));
}

/** Fire TV, Android TV or a phone/tablet, from getprop values and the leanback feature. */
function deviceKind(manufacturer, hasLeanback) {
  if (/^amazon$/i.test(String(manufacturer).trim())) return 'firetv';
  return hasLeanback ? 'androidtv' : 'android';
}

/** Best ABI for a device's list (ro.product.cpu.abilist), in device preference order. */
function preferredAbis(abilist) {
  const list = String(abilist || '').split(',').map((s) => s.trim()).filter(Boolean);
  const known = list.filter((a) => ABIS.includes(a));
  return known.length ? known : ['armeabi-v7a'];
}

/**
 * Picks the release asset for a device. pattern contains {abi}; falls back to the
 * "universal" build. Returns the asset object or null.
 */
function pickAsset(assets, pattern, abis) {
  const names = (assets || []).map((a) => a.name);
  const tryAbi = (abi) => {
    const re = new RegExp('^' + pattern.replace('{abi}', escapeRe(abi)) + '$');
    const i = names.findIndex((n) => re.test(n));
    return i >= 0 ? assets[i] : null;
  };
  for (const abi of abis) {
    const hit = tryAbi(abi);
    if (hit) return hit;
  }
  return tryAbi('universal');
}

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Finds the SHA-256 published for fileName in a release's notes: either "<sha>  file" lines
 * or a table row that links the file and a virustotal.com/gui/file/<sha> report.
 */
function shaFromNotes(body, fileName) {
  if (!body || !fileName) return null;
  for (const raw of String(body).split(/\r?\n/)) {
    const line = raw.replace(/\\_/g, '_'); // Markdown often escapes underscores in names
    if (!line.includes(fileName)) continue;
    const vt = line.match(/virustotal\.com\/gui\/file\/([a-f0-9]{64})/i);
    if (vt) return vt[1].toLowerCase();
    const plain = line.match(/\b([a-f0-9]{64})\b/i);
    if (plain) return plain[1].toLowerCase();
  }
  return null;
}

/** First 64-hex token of a .sha256 file ("<sha>  CalmHome.apk"). */
function parseShaFile(text) {
  const m = String(text || '').match(/\b([a-f0-9]{64})\b/i);
  return m ? m[1].toLowerCase() : null;
}

/** Package names from a list file: one per line, # comments and blanks ignored. */
function parsePackageList(text) {
  const seen = new Set();
  const out = [];
  for (const raw of String(text).split(/\r?\n/)) {
    const line = raw.replace(/#.*/, '').trim();
    if (/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/.test(line) && !seen.has(line)) {
      seen.add(line);
      out.push(line);
    }
  }
  return out;
}

/** `pm list packages` output -> Set of names. */
function parsePmList(text) {
  return new Set(
    String(text)
      .split(/\r?\n/)
      .map((l) => l.trim().replace(/^package:/, ''))
      .filter(Boolean),
  );
}

/** What debloat will do: packages present and still enabled, in list order. */
function debloatPlan(list, installed, enabled) {
  return list.filter((p) => installed.has(p) && enabled.has(p));
}

module.exports = {
  ABIS,
  parseArgs,
  normalizeTarget,
  isPrivateIPv4,
  subnetHosts,
  parseDevices,
  deviceKind,
  preferredAbis,
  pickAsset,
  shaFromNotes,
  parseShaFile,
  parsePackageList,
  parsePmList,
  debloatPlan,
};

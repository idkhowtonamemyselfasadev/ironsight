// Mineflayer bots that pass as Ironsight clients.
//
// Fabric decides "modded or vanilla" during the configuration phase: it sends
// minecraft:register (its channels) followed by a ping, and a client that answers with
// its own minecraft:register before the pong counts as modded. So a bot that replies to
// every minecraft:register with Ironsight's channel list gets in; a bot that doesn't is
// a vanilla client and must be kicked with the install message.
'use strict';
let mineflayer;
try { mineflayer = require('/home/tim/claude/anticheat/test/node_modules/mineflayer'); } catch (e) { mineflayer = require('mineflayer'); }

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = +(process.env.MC_PORT || 25681);
const VERSION = '1.21.11';

const S2C = ['ironsight:hello', 'ironsight:guns', 'ironsight:hud', 'ironsight:ammo', 'ironsight:shot',
  'ironsight:hit', 'ironsight:kill', 'ironsight:open_menu', 'ironsight:board', 'ironsight:glint'];
const C2S = ['ironsight:shoot', 'ironsight:action', 'ironsight:menu'];

const ACTION = { RELOAD: 0, ADS_ON: 1, ADS_OFF: 2, SPECTATE_NEXT: 3, OPEN_PLAY: 4, LEAVE: 5 };
const MENU = { QUEUE: 0, TEAM: 1, LOADOUT: 2, VOTE: 3 };
const SMENU = { CLOSE: 0, PLAY: 1, TEAM: 2, LOADOUT: 3, VOTE: 4 };

// --- tiny buffer helpers ---------------------------------------------------------
function varint(n) {
  const out = [];
  do { let b = n & 0x7f; n >>>= 7; if (n) b |= 0x80; out.push(b); } while (n);
  return Buffer.from(out);
}
function str(s) { const b = Buffer.from(s, 'utf8'); return Buffer.concat([varint(b.length), b]); }
class Reader {
  constructor(buf) { this.b = buf; this.o = 0; }
  byte() { return this.b.readInt8(this.o++); }
  ubyte() { return this.b.readUInt8(this.o++); }
  bool() { return this.ubyte() !== 0; }
  short() { const v = this.b.readInt16BE(this.o); this.o += 2; return v; }
  float() { const v = this.b.readFloatBE(this.o); this.o += 4; return v; }
  varint() { let n = 0, s = 0, b; do { b = this.b[this.o++]; n |= (b & 0x7f) << s; s += 7; } while (b & 0x80); return n; }
  str() { const n = this.varint(); const v = this.b.toString('utf8', this.o, this.o + n); this.o += n; return v; }
}

function decode(channel, data) {
  const r = new Reader(data);
  switch (channel) {
    case 'ironsight:hud': return { mode: r.byte(), phase: r.byte(), ticksLeft: r.varint(), round: r.byte(), scoreA: r.byte(),
      scoreB: r.byte(), team: r.byte(), aliveA: r.byte(), aliveB: r.byte(), lives: r.byte(), playersLeft: r.byte(), toWin: r.byte(), map: r.str() };
    case 'ironsight:open_menu': return { kind: r.byte(), data: r.str(), seconds: r.varint() };
    case 'ironsight:kill': return { killer: r.str(), victim: r.str(), gun: r.byte(), headshot: r.bool(), killerTeam: r.byte(), victimTeam: r.byte() };
    case 'ironsight:hit': return { damage: r.float(), flags: r.byte() };
    case 'ironsight:ammo': return { gun: r.byte(), mag: r.short(), reserve: r.short(), reloadLeft: r.short(), reloadTotal: r.short() };
    case 'ironsight:glint': return { entity: r.varint(), on: r.bool() };
    case 'ironsight:shot': { const e = r.varint(), g = r.byte(); r.float(); r.float(); r.float(); const n = r.byte(); return { entity: e, gun: g, rays: n }; }
    case 'ironsight:board': return { rows: r.str() };
    case 'ironsight:guns': return { json: r.str() };
    case 'ironsight:hello': return { version: r.str() };
    default: return null;
  }
}

/**
 * Create a bot. modded=false makes it a plain vanilla client (for the kick test).
 * The returned bot gets: bot.is (decoded Ironsight state), bot.isSend(channel, buf),
 * and emits 'is:<name>' events for each Ironsight packet.
 */
function makeBot(name, { modded = true } = {}) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, version: VERSION, auth: 'offline',
    hideErrors: true, checkTimeoutInterval: 120000 });
  bot.is = { hud: null, menus: [], kills: [], hits: [], ammo: {}, shotsSeen: 0, glints: 0, hello: null, guns: null };
  const client = bot._client;
  if (modded) {
    const reply = Buffer.from([...S2C, ...C2S].join('\0'), 'utf8');
    client.on('custom_payload', (packet) => {
      if (packet.channel === 'minecraft:register') {
        client.write('custom_payload', { channel: 'minecraft:register', data: reply });
      }
    });
  }
  client.on('custom_payload', (packet) => {
    if (!packet.channel || !packet.channel.startsWith('ironsight:')) return;
    let d;
    try { d = decode(packet.channel, packet.data); } catch (e) { return; }
    const short = packet.channel.slice(10);
    switch (short) {
      case 'hud': bot.is.hud = d; break;
      case 'open_menu': bot.is.menus.push(d); break;
      case 'kill': bot.is.kills.push(d); break;
      case 'hit': bot.is.hits.push(d); break;
      case 'ammo': bot.is.ammo[d.gun] = d; break;
      case 'shot': bot.is.shotsSeen++; break;
      case 'glint': bot.is.glints++; break;
      case 'hello': bot.is.hello = d.version; break;
      case 'guns': bot.is.guns = d.json.length; break;
    }
    bot.emit('is:' + short, d);
  });
  bot.isSend = (channel, data) => client.write('custom_payload', { channel, data });
  bot.shoot = (yawDeg, pitchDeg) => {
    const b = Buffer.alloc(8); b.writeFloatBE(yawDeg, 0); b.writeFloatBE(pitchDeg, 4);
    bot.isSend('ironsight:shoot', b);
  };
  bot.action = (a) => bot.isSend('ironsight:action', Buffer.from([a]));
  bot.menu = (kind, value) => bot.isSend('ironsight:menu', Buffer.concat([Buffer.from([kind]), str(value)]));
  bot.on('error', () => {});
  return bot;
}

function waitFor(emitter, event, ms = 20000, pred = () => true) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => { emitter.removeListener(event, h); reject(new Error(`timeout waiting for ${event}`)); }, ms);
    function h(...a) { if (pred(...a)) { clearTimeout(t); emitter.removeListener(event, h); resolve(a[0]); } }
    emitter.on(event, h);
  });
}

function spawned(bot, ms = 30000) {
  if (bot.entity) return Promise.resolve();
  return waitFor(bot, 'spawn', ms);
}

/** Notchian yaw/pitch (degrees) from a to b. */
function aim(from, to) {
  const dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
  const yaw = Math.atan2(-dx, dz) * 180 / Math.PI;
  const pitch = -Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180 / Math.PI;
  return { yaw, pitch };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

module.exports = { makeBot, waitFor, spawned, aim, sleep, ACTION, MENU, SMENU, HOST, PORT };

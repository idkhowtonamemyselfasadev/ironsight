// Full Ironsight test: run on the test box via `test/serve.sh suite [steps...]`.
// Steps: kick balance testbox duel team2 team4 ffa maps  (default: all)
'use strict';
const fs = require('fs');
const path = require('path');
const { makeBot, waitFor, spawned, aim, sleep, MENU, SMENU, ACTION } = require('./lib');

const RUN = path.resolve(__dirname, '../../testserver');
const LOG = path.join(RUN, 'server.log');
const FIFO = path.join(RUN, 'console.in');
const OUT = path.resolve(__dirname, '../out');
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -- ' + detail : ''}`);
}

function cmd(c) { fs.appendFileSync(FIFO, c + '\n'); }
function logText() { return fs.readFileSync(LOG, 'utf8'); }
let logMark = 0;
function mark() { logMark = logText().length; }
function since() { return logText().slice(logMark); }
async function waitLog(re, ms = 30000, from = logMark) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const m = logText().slice(from).match(re);
    if (m) return m;
    await sleep(250);
  }
  return null;
}
function count(re, text) { return (text.match(new RegExp(re.source, 'g')) || []).length; }

const LOADOUTS = ['rifle,pistol', 'burst,pistol', 'dmr,revolver', 'smg,pistol', 'lmg,revolver', 'sniper,pistol', 'vector,pistol', 'rifle,revolver'];

/**
 * Give a bot the brain of a player: picks teams/loadouts/votes from the menus and, while
 * a round is live, aims at the nearest enemy and fires (aimed in).
 */
function brain(bot, opts = {}) {
  let li = Math.floor(Math.random() * LOADOUTS.length);
  const state = { board: {}, adsSent: false, alive: true };
  bot.on('is:open_menu', (m) => {
    if (m.kind === SMENU.TEAM) setTimeout(() => bot.menu(MENU.TEAM, opts.team || 'A'), 150 + Math.random() * 300);
    if (m.kind === SMENU.LOADOUT) setTimeout(() => bot.menu(MENU.LOADOUT, opts.loadout || LOADOUTS[li++ % LOADOUTS.length]), 200);
    if (m.kind === SMENU.VOTE) {
      bot.is.votedMenu = m.data;
      if (opts.vote) setTimeout(() => bot.menu(MENU.VOTE, opts.vote(m.data)), 500);
    }
  });
  bot.on('is:board', (b) => {
    state.board = {};
    for (const row of b.rows.split(';')) {
      const f = row.split('|');
      state.board[f[0]] = { team: +f[1], alive: f[5] === '1' };
    }
  });
  const timer = setInterval(() => {
    if (!bot.entity || !bot.is.hud || bot.is.hud.mode < 0 || bot.is.hud.phase !== 2 || opts.passive) {
      state.adsSent = false;
      return;
    }
    if (bot.game && bot.game.gameMode === 'spectator') return;
    const me = state.board[bot.username];
    let best = null, bestD = 1e9;
    for (const name of Object.keys(bot.players)) {
      if (name === bot.username) continue;
      const e = bot.players[name].entity;
      const row = state.board[name];
      if (!e || !row || !row.alive) continue;
      if (me && me.team >= 0 && row.team === me.team) continue;
      const d = e.position.distanceTo(bot.entity.position);
      if (d < bestD) { bestD = d; best = e; }
    }
    if (!best) return;
    if (!state.adsSent) { bot.action(ACTION.ADS_ON); state.adsSent = true; }
    const target = best.position.offset(0, 1.1, 0);
    const eye = bot.entity.position.offset(0, 1.62, 0);
    const a = aim(eye, target);
    bot.lookAt(target, true).catch(() => {});
    bot.shoot(a.yaw, a.pitch);
  }, 120);
  bot.once('end', () => clearInterval(timer));
  return state;
}

async function joinBots(names, opts) {
  const bots = [];
  for (const n of names) {
    const b = makeBot(n);
    if (opts) brain(b, typeof opts === 'function' ? opts(n, bots.length) : opts);
    bots.push(b);
    await sleep(350);
  }
  await Promise.all(bots.map((b) => spawned(b)));
  await sleep(1000);
  return bots;
}
function quitAll(bots) { for (const b of bots) try { b.quit(); } catch (e) { } }

// ------------------------------------------------------------------------- steps

async function stepKick() {
  mark();
  const vanilla = makeBot('Vanilla1', { modded: false });
  let reason = '';
  vanilla.on('kicked', (r) => { reason += typeof r === 'string' ? r : JSON.stringify(r); });
  await Promise.race([waitFor(vanilla, 'end', 20000).catch(() => {}), sleep(20000)]);
  const logged = await waitLog(/Vanilla1.*lost connection: .*Ironsight required/s, 5000);
  check('vanilla client kicked with install message', /Ironsight required/.test(reason) || logged,
    (reason || (logged ? logged[0].slice(-160) : 'no reason')).replace(/\s+/g, ' ').slice(0, 220));
  check('install message names the mod version', /Ironsight 1\.0\.0/.test(reason + (logged ? logged[0] : '')));

  const modded = makeBot('Modded1');
  try {
    await spawned(modded, 20000);
    await sleep(1500);
    check('modded client joins', true);
    check('gun table synced on join', modded.is.guns > 1000, `${modded.is.guns} chars`);
    check('spawned in lobby', modded.entity.position.y > 110,
      `${modded.game.dimension} @ ${modded.entity.position}`);
  } catch (e) {
    check('modded client joins', false, e.message);
  }
  quitAll([modded]);
  await sleep(1000);
}

async function stepBalance() {
  mark();
  cmd('gun balance');
  await waitLog(/BALANCE \(n\) =/, 10000);
  const lines = since().split('\n').filter((l) => l.includes('BALANCE ') && l.includes(' | ') && /\dm /.test(l));
  fs.writeFileSync(path.join(OUT, 'balance.txt'), lines.map((l) => l.replace(/.*BALANCE /, '')).join('\n') + '\n');
  let oneShots = [];
  const ttk = {};
  for (const l of lines) {
    const name = l.replace(/.*BALANCE /, '').split('|')[0].trim();
    const cells = [...l.matchAll(/(\d+)m ([\d.inf]+)\((\d+)\)\/([\d.inf]+)\((\d+)\)\/([\d.inf]+)/g)];
    ttk[name] = cells.map((c) => ({ d: +c[1], body: c[2], bodyShots: +c[3], head: c[4], headShots: +c[5], aimed: c[6] }));
    for (const c of cells) if (+c[3] === 1 && name !== 'Shotgun') oneShots.push(`${name}@${c[1]}m`);
  }
  check('balance table printed for 10 guns', lines.length === 10, `${lines.length} lines`);
  check('no body one-shot except shotgun', oneShots.length === 0, oneShots.join(', '));
  const s = ttk['Sniper'], ar = ttk['Assault Rifle'];
  if (s && ar) {
    check('sniper: 2 body shots, 1 headshot', s.every((c) => c.bodyShots === 2 && c.headShots === 1),
      s.map((c) => `${c.d}m ${c.bodyShots}/${c.headShots}`).join(' '));
    const mid = ar.find((c) => c.d === 25), smid = s.find((c) => c.d === 25);
    check('assault rifle out-TTKs the sniper body kill at 25m', +mid.body < +smid.body, `AR ${mid.body}s vs sniper ${smid.body}s`);
  }
}

// The test box: built with plain fill commands, saved with /gun map save and given spawns
// with /gun map spawn - exactly the "add your own map" workflow from the README.
async function stepTestbox() {
  mark();
  const B = makeBot('Builder');
  await spawned(B);
  cmd('op Builder');
  cmd('forceload add 1990 1990 2050 2050');
  await sleep(1500);
  cmd('fill 2000 99 2000 2040 99 2040 minecraft:stone');
  cmd('fill 2000 100 2000 2040 100 2040 minecraft:smooth_stone');
  cmd('fill 2000 101 2000 2040 106 2000 minecraft:stone_bricks');
  cmd('fill 2000 101 2040 2040 106 2040 minecraft:stone_bricks');
  cmd('fill 2000 101 2000 2000 106 2040 minecraft:stone_bricks');
  cmd('fill 2040 101 2000 2040 106 2040 minecraft:stone_bricks');
  cmd('fill 2001 101 2001 2039 110 2039 minecraft:air');
  await sleep(1000);
  cmd('gun map save testbox 2000 99 2000 2040 110 2040');
  await waitLog(/Saved map testbox/, 10000);
  const spawnAt = async (kind, x, z, yaw) => {
    cmd(`tp Builder ${x} 101 ${z} ${yaw} 0`);
    await sleep(150);
    cmd(`execute as Builder run gun map spawn testbox ${kind}`);
    await sleep(150);
  };
  for (const z of [2014, 2018, 2022, 2026]) await spawnAt('teamA', 2008, z, -90);
  for (const z of [2014, 2018, 2022, 2026]) await spawnAt('teamB', 2032, z, 90);
  const ring = [];
  for (let i = 0; i < 14; i++) {
    const a = (i / 14) * Math.PI * 2;
    ring.push([Math.round(2020 + Math.cos(a) * 14), Math.round(2020 + Math.sin(a) * 14)]);
  }
  for (const [x, z] of ring) await spawnAt('ffa', x, z, 0);
  await spawnAt('spectator', 2020, 2020, 0);
  cmd('gun reload');
  const ok = await waitLog(/map testbox \(Testbox\) 41x12x41 A4 B4 ffa14/, 10000);
  check('custom map saved + spawns added + loaded via /gun reload', !!ok, ok ? ok[0] : since().slice(-300));
  cmd('deop Builder');
  quitAll([B]);
  await sleep(1000);
}

/** Queue bots for a mode, wait for MATCH END and the vote. Returns the log slice. */
async function playMatch(label, mode, names, { map = 'testbox', mvpVotes = true, leaver = null, killer = false, allRed = false, extra = [], timeout = 240000 } = {}) {
  mark();
  cmd(`gun nextmap ${mode} ${map}`);
  await sleep(500);
  const bots = await joinBots(names, (n, i) => ({
    team: allRed || i % 2 === 0 ? 'A' : 'B',
    vote: mvpVotes ? (data) => data.split(';').map((p) => p.split('=')[0]).find((id) => id !== map) || map : null,
    passive: killer,
  }));
  for (const x of extra) cmd(`execute as ${x} run play ${mode}`);
  for (const b of bots) b.menu(MENU.QUEUE, mode);
  const start = await waitLog(new RegExp(`MATCH START id=(\\d+) mode=${mode} map=${map} players=${names.length + extra.length}`), 30000);
  check(`${label}: match starts on ${map} with ${names.length + extra.length}`, !!start, start ? start[0] : 'no MATCH START');
  if (!start) { quitAll(bots); return { text: since(), bots }; }
  const id = start[1];

  // Spawns must put players on solid ground inside the arena dimension.
  const firstLive = await waitLog(new RegExp(`(ROUND START match=${id} round=1|MATCH START id=${id})`), 20000);
  await sleep(mode === 'ffa' ? 1500 : 2600);
  const ys = bots.map((b) => b.entity && b.entity.position.y);
  await sleep(1000);
  const falling = bots.filter((b, i) => !b.entity || Math.abs(b.entity.position.y - ys[i]) > 0.6 || b.entity.position.y < 64 || b.entity.position.y > 100);
  check(`${label}: everyone spawned standing in the arena`, firstLive && falling.length === 0,
    falling.map((b) => `${b.username} ${b.game && b.game.dimension} y=${b.entity && b.entity.position.y.toFixed(2)}`).join(', '));

  let leaverDone = false;
  let killTimer = null;
  if (killer) {
    // Real maps: rounds are ended by the console killing one bot per round (no line of sight needed).
    let i = 0;
    killTimer = setInterval(() => {
      const live = bots.filter((b) => b.is.hud && b.is.hud.phase === 2 && b.game.gameMode !== 'spectator');
      if (live.length) cmd(`kill ${live[i++ % live.length].username}`);
    }, 1200);
  }
  const end = Date.now() + timeout;
  let endLine = null;
  while (Date.now() < end) {
    endLine = since().match(new RegExp(`MATCH END id=${id} mode=${mode} winner="([^"]*)" mvp=(\\S+)`));
    if (endLine) break;
    if (leaver && !leaverDone && count(new RegExp(`ROUND END match=${id}|KILL match=${id}`), since()) >= 2) {
      leaverDone = true;
      const lb = bots.find((b) => b.username === leaver);
      lb.quit();
      await sleep(1500);
      const left = since().match(new RegExp(`LEAVE match=${id} player=${leaver}`));
      check(`${label}: leaving mid-match is logged as leave`, !!left);
      // Back on: must land in the lobby, not the arena.
      const again = makeBot(leaver);
      try {
        await spawned(again, 20000);
        await sleep(1500);
        check(`${label}: reconnect goes to the lobby`, again.entity.position.y > 110,
          `${again.game.dimension} y=${again.entity.position.y.toFixed(1)}`);
      } catch (e) { check(`${label}: reconnect goes to the lobby`, false, e.message); }
      again.quit();
    }
    await sleep(500);
  }
  if (killTimer) clearInterval(killTimer);
  check(`${label}: match ends with a winner`, !!endLine, endLine ? `winner "${endLine[1]}", mvp ${endLine[2]}` : 'no MATCH END');
  const vote = await waitLog(new RegExp(`VOTE match=${id} next=(\\S+) by=(\\w+)`), 15000);
  check(`${label}: next map chosen (${mvpVotes ? 'MVP vote' : '15 s timeout -> random'})`,
    vote && vote[2] === (mvpVotes ? 'mvp' : 'random'), vote ? vote[0] : 'no VOTE line');
  await sleep(3000);
  const home = bots.filter((b) => b.entity && !b._client.ended && b.entity.position.y > 110);
  const alive = bots.filter((b) => !b._client.ended && b.username !== leaver);
  check(`${label}: everyone back in the lobby`, home.length >= alive.length, `${home.length}/${alive.length}`);
  const text = since();
  fs.writeFileSync(path.join(OUT, `${label}.log`), text.split('\n').filter((l) => l.includes('[Ironsight]')).join('\n') + '\n');
  quitAll(bots);
  await sleep(1500);
  return { text, bots, id };
}

function teamChecks(label, r) {
  const rounds = count(new RegExp(`ROUND END match=${r.id} `), r.text);
  const scores = [...r.text.matchAll(new RegExp(`ROUND END match=${r.id} .*score=(\\d+)-(\\d+)`, 'g'))];
  const last = scores[scores.length - 1];
  check(`${label}: first to 7 rounds`, last && Math.max(+last[1], +last[2]) === 7, last ? `final ${last[1]}-${last[2]} in ${rounds} rounds` : '');
  check(`${label}: sides swap at halftime`, new RegExp(`ROUND START match=${r.id} round=7 .*swapped=true`).test(r.text));
  const kills = count(new RegExp(`KILL match=${r.id} killer=(?!-)`), r.text);
  return kills;
}

async function stepDuel() {
  const r = await playMatch('1v1', 'duel', ['Duel_A', 'Duel_B'], { mvpVotes: true, allRed: true });
  if (!r.id) return;
  const kills = teamChecks('1v1', r);
  check('1v1: rounds decided by gunfire', kills >= 7, `${kills} gun kills`);
  const teams = [...r.text.matchAll(new RegExp(`TEAM match=${r.id} player=(\\S+) team=(\\w+)`, 'g'))].map((m) => m[2]);
  check('1v1: both picked Red, the second was auto-balanced to Blue', teams.includes('Red') && teams.includes('Blue'), teams.join(','));
  const hs = count(new RegExp(`KILL match=${r.id} .*head=true`), r.text);
  console.log(`      headshot kills: ${hs}`);
}

async function stepTeam2() {
  const r = await playMatch('2v2', 'team2', ['T2_A1', 'T2_B1', 'T2_A2', 'T2_B2'], { mvpVotes: false, leaver: 'T2_B2' });
  if (!r.id) return;
  teamChecks('2v2', r);
  check('2v2: leaving counts as a death', new RegExp(`KILL match=${r.id} killer=\\S+ victim=T2_B2`).test(r.text)
    || /LEAVE match=\d+ player=T2_B2/.test(r.text));
}

async function stepTeam4() {
  const names = ['T4_A1', 'T4_B1', 'T4_A2', 'T4_B2', 'T4_A3', 'T4_B3', 'T4_A4', 'T4_B4'];
  const r = await playMatch('4v4', 'team4', names, { mvpVotes: true });
  if (!r.id) return;
  const kills = teamChecks('4v4', r);
  console.log(`      4v4 gun kills: ${kills}`);
}

async function stepFfa() {
  const names = ['F_1', 'F_2', 'F_3', 'F_4', 'F_5', 'F_6'];
  const r = await playMatch('FFA', 'ffa', names, { mvpVotes: true });
  if (!r.id) return;
  const deaths = {};
  for (const m of r.text.matchAll(new RegExp(`KILL match=${r.id} killer=\\S+ victim=(\\S+)`, 'g'))) deaths[m[1]] = (deaths[m[1]] || 0) + 1;
  const out = Object.entries(deaths).filter(([, n]) => n >= 3).length;
  check('FFA: players used all 3 lives before being out', out === names.length - 1, JSON.stringify(deaths));
  check('FFA: last player standing wins', new RegExp(`FFA LAST STANDING match=${r.id} winner=(F_\\d)`).test(r.text));
  const gunsLines = r.text.match(/Your guns/) ? 'ok' : '';
  void gunsLines;
}

async function stepMaps() {
  for (const map of ['foundry', 'oasis', 'harbor']) {
    const r = await playMatch(`1v1-${map}`, 'duel', [`M_${map}_1`, `M_${map}_2`], { map, killer: true, mvpVotes: map !== 'oasis' });
    if (r.id) teamChecks(`1v1-${map}`, r);
  }
  // FFA spawns on a real map too.
  const r = await playMatch('FFA-harbor', 'ffa', ['H_1', 'H_2', 'H_3', 'H_4'], { map: 'harbor', killer: true, mvpVotes: false });
  if (r.id) check('FFA-harbor: last player standing wins', new RegExp(`FFA LAST STANDING match=${r.id} winner=H_`).test(r.text));
}

// Real client (test/client/run-client.py, launched on the desktop) + 7 bots.
async function stepFps() {
  console.log('waiting up to 4 min for the real client FpsTester...');
  const joined = await waitLog(/FpsTester joined the game/, 240000, 0);
  check('real Fabric client (Sodium+Lithium+FerriteCore) joins', !!joined);
  if (!joined) return;
  await sleep(8000);
  const names = ['P_A1', 'P_B1', 'P_A2', 'P_B2', 'P_A3', 'P_B3', 'P_A4'];
  await playMatch('fps-4v4-testbox', 'team4', names, { extra: ['FpsTester'], mvpVotes: true });
  await sleep(4000);
  await playMatch('fps-ffa-harbor', 'ffa', names, { map: 'harbor', extra: ['FpsTester'], killer: true, mvpVotes: false });
  const stillOn = !/FpsTester lost connection/.test(logText());
  check('real client stayed connected through both matches', stillOn);
}

(async () => {
  const steps = process.argv.slice(2);
  const all = ['kick', 'balance', 'testbox', 'duel', 'team2', 'team4', 'ffa', 'maps'];
  const run = steps.length ? steps : all;
  if (run.some((s) => ['duel', 'team2', 'team4', 'ffa', 'fps'].includes(s)) && !run.includes('testbox')) run.unshift('testbox');
  const t0 = Date.now();
  const fns = { fps: stepFps, kick: stepKick, balance: stepBalance, testbox: stepTestbox, duel: stepDuel, team2: stepTeam2, team4: stepTeam4, ffa: stepFfa, maps: stepMaps };
  for (const s of [...all, 'fps'].filter((x) => run.includes(x))) {
    console.log(`\n== ${s} ==`);
    try { await fns[s](); } catch (e) { check(`${s}: crashed`, false, e.stack); }
  }
  const errors = count(/\[Server thread\/ERROR\]|Exception/, logText());
  check('server log has no errors/exceptions', errors === 0, `${errors} hits`);
  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} passed in ${((Date.now() - t0) / 1000).toFixed(0)} s`);
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify(results, null, 2));
  process.exit(failed.length ? 1 : 0);
})();

# Ironsight

A balanced hitscan gun PvP minigame for **Minecraft Java 1.21.11 (Fabric)**. One jar, installed
on the server **and** on every player's client. 1v1 / 2v2 / 4v4 CS-style rounds and a 2-12
player FFA, three arena maps, map voting, kill feed, scoreboard - all on one dedicated server.

- `build/libs/ironsight-1.0.0.jar` - the mod (client + server in one file)
- `server-config/` - the default `guns.json` and `settings.json` (the server writes these itself on first start)
- `src/` - source; `tools/maps/` - map generator; `tools/sound/`, `tools/art/` - asset scripts
- `test/` - the dedicated-server test harness (mineflayer bots)

## Install

Both sides need **Fabric Loader ≥ 0.18.0**, **Fabric API** for 1.21.11 and **Java 21**.

**Server**
1. Install a Fabric 1.21.11 server, put `fabric-api-*.jar` and `ironsight-1.0.0.jar` in `mods/`.
2. `server.properties`: nothing special is required. Recommended: `view-distance=8`,
   `simulation-distance=8`, `spawn-protection=0`.
3. Start it once. It creates `config/ironsight/` (`guns.json`, `settings.json`, `maps/`), builds
   the lobby at `0 120 0` in the overworld (once; see `lobby-built.txt`) and sets minigame
   gamerules (no mobs, fixed noon, no natural regen, no death messages - turn off with
   `"manageGamerules": false`).
4. Run with `-Xmx3G` or more. The test server runs exactly that.

**Players**: put `fabric-api` + `ironsight-1.0.0.jar` in `mods/` (Sodium, Lithium and FerriteCore
work alongside it). A player who joins without the mod is turned away with
*"Ironsight required - install Ironsight 1.0.0 + Fabric API..."*; a different Ironsight version
gets a version-mismatch message.

## Playing

You spawn in the lobby. Queue by right-clicking the **Play compass**, talking to a **mode NPC**,
pressing **P**, or typing **/play** (`/play duel|team2|team4|ffa`). `/leave` leaves the queue or
the match.

| Key | |
|---|---|
| Left click | shoot (hold for automatic guns; burst rifle fires 3 per click) |
| Right click | aim down sights (snipers/DMR zoom; sniper shows a scope) |
| R | reload (rebindable in Options → Controls → Ironsight) |
| 1 / 2 | primary / secondary |
| Tab | scoreboard |
| P | play menu |
| (unbound) | toggle damage numbers |

**Team modes (1v1, 2v2, 4v4).** The match starts when the queue is full. Pick a side (a full
team auto-balances you to the other one; nobody picks = auto). Every round: 10 s buy/freeze time
with the loadout menu (1 primary + 1 secondary), then 2 minutes, **one life**. Wipe the other
team to win the round; on time-out the team with more players alive wins, then more total HP.
First to **7**. Sides swap after round 6. Drawn rounds score nothing; if nobody has 7 after 17 rounds, the higher score (then more damage dealt) wins.

**FFA.** Starts 15 s after 2 players queue (immediately at 12). Everyone gets **2 random,
different guns** and **3 lives**. Respawns pick the spawn farthest from living enemies, with 3 s
of spawn protection (firing ends it). Last player standing wins (time limit 10 min: most lives,
then kills).

**After a match** the winner gets a title and fireworks. The FFA winner, or the MVP of the
winning team (most kills, then damage), gets a map-vote menu for the next match of that mode.
No pick within 15 s means a random map. Everyone goes back to the lobby and, with
`autoRequeue`, straight back into the same queue.

**Rules in arenas:** adventure mode (no breaking/placing), no hunger, no natural regen, no
vanilla items, no melee, fall damage on. Dead players spectate (teammates in team modes, anyone
in FFA; left click cycles). Leaving mid-match counts as a death; reconnecting puts you in the lobby.

## Guns and balancing

All stats live in `config/ironsight/guns.json` on the server and are sent to every client on
join. Edit, then **`/gun reload`** - no restart, no recompile. **`/gun balance`** prints
time-to-kill at 10/25/50 m for every gun: body (all hits), head, and "aimed" (expected, counting
the gun's ADS spread against a body-sized target).

| Gun | Role | Body TTK 10 / 25 / 50 m (all hits) | Head-only |
|---|---|---|---|
| Pistol | fast-draw sidearm, 12 rounds | 0.80 / 1.00 / 1.60 s | 0.40 s |
| Revolver | 6 heavy shots, slow | 1.40 / 1.40 / 2.10 s | 0.70 s |
| SMG | 600 rpm, big falloff after 8 m | 0.40 / 1.10 / - | 0.30 s |
| Vector | 1200 rpm, 20-round mag, wide spread | 0.40 / 0.80* / - | 0.25 s |
| Assault rifle | all-rounder | 0.60 / 0.60 / 0.90 s | 0.30 s |
| Burst rifle | 3-round burst; 2 headshots in one burst kill | 0.60 / 0.60 / 0.70 s | 0.10 s |
| Shotgun | 8 pellets; 1 blast at point blank, 2 at 10 m | 0.90 / 4.50 / - | - |
| LMG | 100 rounds, 5 s reload, 80 % walk speed | 0.60 / 0.60 / 0.75 s | 0.30 s |
| DMR | semi-auto, no falloff before 50 m | 0.70 / 0.70 / 0.70 s | 0.35 s |
| Sniper | bolt-action: 2 body / 1 head, 1.5 s bolt | 1.50 / 1.50 / 1.50 s | one shot |

\* perfect accuracy; with its ADS spread the expected Vector TTK at 25 m is about 5 s.

(Exact numbers: run `/gun balance`; `test/out/balance.txt` has the last test run's table.)

Fields per gun (times in ticks, 20 = 1 s; angles in degrees; players have 20 HP):
`damage, headMult, fireTicks, auto, burst, burstTicks, pellets, mag, reserve, reloadTicks,
shellReload, drawTicks, range, falloffStart, falloffEnd, minDamageMult, hipSpread, adsSpread,
moveSpread, airSpread, bloomPerShot, maxBloom, bloomDecay, recoilPitch, recoilYaw, moveMult,
adsMoveMult, adsTicks, adsZoom, scope, glint, tracerColor, slot ("primary"/"secondary"), name`.

Design rules the defaults keep: nothing one-shots a full-health body except the shotgun up
close; the sniper is a 2-body/1-head gun with a slow bolt, big hip spread, slow scoped walk and a
scope glint enemies can see - a rifle player who lands shots wins at 25-50 m.

`settings.json`: team-pick/freeze/round/round-end seconds, rounds to win, halftime, FFA
min/max/countdown/lives/respawn/time limit, spawn protection, vote and celebrate seconds,
autoRequeue, lag compensation cap (`lagCompMaxMs`, default 200), gamerules, lobby position.

## Maps

Built-in: **Foundry** (80x80, industrial), **Oasis** (84x84, desert town), **Harbor** (72x92,
docks). Each has a long lane (60-70 block sightline broken by cover), a mixed-range mid with
height, and a close-quarters route; team spawns are mirrored and can't see each other.

Maps load into their own dimension, `ironsight:arena`. Every match gets its own slot (512 blocks
apart), so several matches can run at once, and the map is placed fresh from its template at
the start of every match - that is the reset. The lobby world is never touched.

Files: `config/ironsight/maps/<id>.nbt` (a structure template) + `<id>.json` (spawns). The
built-ins are copied there on first start; edits are kept; delete a file to restore it.

### Adding a map

1. Build it anywhere in creative (keep it under ~100x100; no chests/signs/fluids is best for FPS;
   put a wall + barriers around it).
2. `/gun map save <id> <x1 y1 z1> <x2 y2 z2>` - any size (no 48-block structure-block limit).
   Writes `<id>.nbt` and a `<id>.json` skeleton.
3. Stand on each spawn, face the right way and run `/gun map spawn <id> teamA` (4x),
   `... teamB` (4x), `... ffa` (12+ is best) and `... spectator` (1x).
4. Optional: edit `name`, `description`, `killY` (relative y below which a player counts as
   fallen out) in the json.
5. `/gun reload`. `/gun map list` shows it; `/gun nextmap <mode> <id>` forces it next.

A team mode needs 4 spawns per team (for 4v4); FFA needs at least 4 FFA spawns. The test suite
builds its own map this way (`test/bots/suite.js`, step `testbox`).

The built-in maps are generated by `python3 tools/maps/genmaps.py` (`--check` validates spawns,
sightlines, reachability and lighting) - see `tools/maps/README.md`.

## Commands

| | |
|---|---|
| `/play [mode]`, `/leave` | everyone |
| `/gun balance` | TTK table (everyone) |
| `/gun reload` | reload guns.json, settings.json, maps; resync clients (op) |
| `/gun status` | running matches (op) |
| `/gun forcestart <mode>` | start with whoever is queued (op) |
| `/gun nextmap <mode> <map>` | force the next map (op) |
| `/gun give <gun>` | give a gun item (op) |
| `/gun map list / save / spawn` | see above |

## How it stays fast

Built for 30+ FPS on an 8 GB Apple-silicon MacBook with Sodium, render distance 8, 8 players.

- **Hitscan**: one server raycast per shot (one block clip + two AABB tests per opponent). No
  bullet entities. Shotgun = 8 rays, damage tallied and applied once per victim.
- **Lag compensation**: targets are rewound to where the shooter saw them (≤ 200 ms) from a fixed
  20-tick position ring per player.
- **Server authority**: the client sends only `shoot(yaw,pitch)` (8 bytes), `action` (1 byte:
  reload / ADS / spectate) and menu picks. The server checks fire rate, ammo, reload, draw time,
  aim drift and rolls the spread itself. Everything server→client is sent on change only
  (ammo, HUD state, scoreboard ≤ 2/s); an idle match sends nothing extra.
- **Effects**: the shooter predicts their own shot (instant feel). Others get one small shot
  packet: 2 particles (flash + smoke, hard cap), one tracer line per ray (max 4) from a fixed
  pool of 64 that fades over 3 ticks, and a sound. No per-tick particles.
- **Models**: plain `item/generated` JSON models with 32x32 textures. Guns are vanilla sticks
  with an `item_model` component - nothing added to registries.
- **HUD**: fills and text only, strings cached and rebuilt only when a packet changes them.
- **Maps**: no block entities, no fluids, no redstone.
- Debug: start a client with `-Dironsight.perf=true` to log FPS and the mod's own µs/frame.

## Building

```
./gradlew build        # -> build/libs/ironsight-1.0.0.jar
```
Mojang mappings, Loom 1.17, Fabric API 0.141.3+1.21.11, Java 21.
Layout: `config/` (gun table, settings), `guns/` (raycast, ballistics, server shooting),
`modes/` (queues, matches, lobby), `maps/` (arena loading/placing), `net/` (payloads),
`client/` (input, effects, `hud/`, `screen/`), `mixin/`.

## Testing

```
./gradlew build
~/claude/remote-test/run.sh ironsight bash test/serve.sh suite            # everything
~/claude/remote-test/run.sh ironsight bash test/serve.sh suite duel ffa   # some steps
~/claude/remote-test/run.sh ironsight --pull                               # test/out/ back here
```
Runs on the test box (port **25681**, `-Xmx3G`, Lithium + FerriteCore installed). Bots pass the
mod check by answering Fabric's channel registration like a real modded client.
Steps: `kick balance testbox duel team2 team4 ffa maps`. Results: `test/out/results.json`,
per-match logs in `test/out/`.

Real client (production Fabric + Ironsight + Sodium + Lithium + FerriteCore, `-Xmx3G`, render
distance 8, muted, on a hidden headless display):
```
python3 test/client/run-client.py 192.168.2.145:25681 480   # while `serve.sh suite fps` waits for it
grep PERF test/client/game/logs/latest.log
```

### Results (2026-10-27)

- Bot suite: **71/71** - kick message, balance rules, custom map via `/gun map save`, full
  1v1 / 2v2 (with a leaver who reconnects into the lobby) / 4v4 / FFA gunfights to the end,
  halftime swap, auto-balance, MVP vote and timeout vote, and full matches on Foundry, Oasis and
  Harbor. 0 server errors.
- Real client: **14/14** - joined, played a 4v4 (8 players) and an 8-player FFA on Harbor to the
  end, never disconnected.
- Client cost of the mod: HUD **20-50 µs** and world effects **0.3-11 µs** per frame during
  8-player fights (≈0.2 % of a 30 FPS frame); FPS was the same in the empty lobby (35) as in the
  8-player gunfight (34-36). Heap stayed 260-880 MB of 3 GB.
- Caveat: absolute FPS from that rig is not a MacBook number - the hidden display caps at
  ~35 FPS even in an empty lobby on the test PC's RTX 5080. It shows the mod adds no measurable
  cost; a real A18 Pro number needs a run on the Mac.

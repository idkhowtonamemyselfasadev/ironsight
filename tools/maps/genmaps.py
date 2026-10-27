#!/usr/bin/env python3
"""Ironsight arena generator.

Writes vanilla structure templates (gzipped big-endian NBT, DataVersion 4671 = MC 1.21.11)
plus JSON metadata for every arena and the lobby, and top-down PNG previews.

    python3 tools/maps/genmaps.py            # generate everything, then run the checks
    python3 tools/maps/genmaps.py --check    # only re-read and verify the written files
    python3 tools/maps/genmaps.py foundry    # generate one map (plus checks)

Stdlib only: the NBT writer/reader and the PNG writer are below.
Coordinates: x = width, y = up, z = the team axis. Team A spawns at low z, team B at high z.
Every arena is mirror-symmetric across the plane z = (sizeZ-1)/2: the A half is designed
and then copied over (facing north<->south swapped). Floor surface is y=1, feet stand at y=2.
"""
import argparse
import gzip
import json
import math
import os
import struct
import sys
import zlib
from array import array
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
OUT_DIR = os.path.join(ROOT, 'src', 'main', 'resources', 'data', 'ironsight', 'arenas')
PREVIEW_DIR = os.path.join(HERE, 'preview')
DATA_VERSION = 4671  # Minecraft Java 1.21.11

# --------------------------------------------------------------------------- NBT


class Raw:
    """Pre-encoded list payload (used for the big 'blocks' list)."""

    def __init__(self, elem_tid, count, data):
        self.elem_tid, self.count, self.data = elem_tid, count, data


def _str(s):
    b = s.encode('utf-8')
    return struct.pack('>H', len(b)) + b


def _tid(v):
    if isinstance(v, Raw):
        return 9
    if isinstance(v, bool) or isinstance(v, int):
        return 3
    if isinstance(v, str):
        return 8
    if isinstance(v, dict):
        return 10
    if isinstance(v, list):
        return 9
    raise TypeError(type(v))


def _payload(v):
    if isinstance(v, Raw):
        return bytes([v.elem_tid]) + struct.pack('>i', v.count) + v.data
    if isinstance(v, bool) or isinstance(v, int):
        return struct.pack('>i', int(v))
    if isinstance(v, str):
        return _str(v)
    if isinstance(v, dict):
        return b''.join(bytes([_tid(x)]) + _str(k) + _payload(x) for k, x in v.items()) + b'\x00'
    if isinstance(v, list):
        t = _tid(v[0]) if v else 0
        return bytes([t]) + struct.pack('>i', len(v)) + b''.join(_payload(x) for x in v)
    raise TypeError(type(v))


def nbt_write(path, root):
    data = b'\x0a' + _str('') + _payload(root)
    with gzip.open(path, 'wb', compresslevel=9) as f:
        f.write(data)


def nbt_read(path):
    with gzip.open(path, 'rb') as f:
        d = f.read()
    pos = [0]

    def rd(n):
        p = pos[0]
        pos[0] = p + n
        return d[p:p + n]

    def rstr():
        (n,) = struct.unpack('>H', rd(2))
        return rd(n).decode('utf-8')

    def payload(t):
        if t == 1:
            return struct.unpack('>b', rd(1))[0]
        if t == 2:
            return struct.unpack('>h', rd(2))[0]
        if t == 3:
            return struct.unpack('>i', rd(4))[0]
        if t == 4:
            return struct.unpack('>q', rd(8))[0]
        if t == 5:
            return struct.unpack('>f', rd(4))[0]
        if t == 6:
            return struct.unpack('>d', rd(8))[0]
        if t == 7:
            (n,) = struct.unpack('>i', rd(4))
            return list(rd(n))
        if t == 8:
            return rstr()
        if t == 9:
            et = rd(1)[0]
            (n,) = struct.unpack('>i', rd(4))
            return [payload(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = rd(1)[0]
                if tt == 0:
                    return out
                k = rstr()
                out[k] = payload(tt)
        if t == 11:
            (n,) = struct.unpack('>i', rd(4))
            return list(struct.unpack('>%di' % n, rd(4 * n)))
        if t == 12:
            (n,) = struct.unpack('>i', rd(4))
            return list(struct.unpack('>%dq' % n, rd(8 * n)))
        raise ValueError('bad tag %d' % t)

    t = rd(1)[0]
    assert t == 10, 'root must be a compound'
    rstr()
    return payload(10)

# --------------------------------------------------------------------------- block helpers


def norm(b):
    return b if b.startswith('minecraft:') else 'minecraft:' + b


def parse_state(s):
    s = norm(s)
    if '[' in s:
        name, rest = s.split('[', 1)
        props = {}
        for kv in rest.rstrip(']').split(','):
            if kv:
                k, v = kv.split('=')
                props[k] = v
        return name, props
    return s, {}


def fmt_state(name, props):
    if not props:
        return name
    return name + '[' + ','.join('%s=%s' % (k, props[k]) for k in sorted(props)) + ']'


NONFULL_SUFFIX = ('_stairs', '_slab', '_fence', '_fence_gate', '_wall', '_pane', '_trapdoor', '_button',
                  '_pressure_plate', '_carpet', '_lantern', '_bars', '_chain', '_rod', '_door')
NONFULL_EXACT = {'minecraft:air', 'minecraft:ladder', 'minecraft:lantern', 'minecraft:soul_lantern',
                 'minecraft:iron_chain', 'minecraft:iron_bars', 'minecraft:anvil', 'minecraft:grindstone',
                 'minecraft:stonecutter', 'minecraft:dead_bush', 'minecraft:flower_pot', 'minecraft:barrier',
                 'minecraft:end_rod', 'minecraft:short_dry_grass', 'minecraft:tall_dry_grass'}
SEE_THROUGH_SUFFIX = ('_fence', '_pane', '_bars', '_chain', '_lantern', '_trapdoor', '_button', '_carpet')
SEE_THROUGH_EXACT = {'minecraft:air', 'minecraft:iron_bars', 'minecraft:iron_chain', 'minecraft:lantern',
                     'minecraft:ladder', 'minecraft:dead_bush', 'minecraft:flower_pot',
                     'minecraft:short_dry_grass', 'minecraft:tall_dry_grass'}
LIGHT = {'minecraft:sea_lantern': 15, 'minecraft:glowstone': 15, 'minecraft:shroomlight': 15,
         'minecraft:lantern': 15, 'minecraft:soul_lantern': 10, 'minecraft:ochre_froglight': 15,
         'minecraft:pearlescent_froglight': 15, 'minecraft:verdant_froglight': 15, 'minecraft:magma_block': 3}
FORBIDDEN = ('water', 'lava', 'chest', 'barrel', 'sign', 'banner', 'bed', 'head', 'skull', 'furnace',
             'hopper', 'redstone', 'piston', 'spawner', 'shulker', 'decorated_pot', 'structure_void',
             'beacon', 'lectern', 'campfire', 'bell', 'jukebox', 'conduit', 'vault', 'crafter', 'dispenser',
             'dropper', 'observer', 'comparator', 'repeater', 'brewing', 'enchanting', 'command',
             'suspicious', 'trial_spawner', 'chiseled_bookshelf', 'creaking')


def is_nonfull(name):
    if name.startswith('minecraft:potted_'):
        return True
    return name in NONFULL_EXACT or name.endswith(NONFULL_SUFFIX)


def is_see_through(name):
    if name.startswith('minecraft:potted_'):
        return True
    return name in SEE_THROUGH_EXACT or name.endswith(SEE_THROUGH_SUFFIX)


def is_passable(name):
    """Can a player's body occupy this cell?"""
    return name in ('minecraft:air', 'minecraft:ladder', 'minecraft:dead_bush', 'minecraft:short_dry_grass',
                    'minecraft:tall_dry_grass')


def stair(name, facing, half='bottom'):
    return '%s[facing=%s,half=%s,shape=straight,waterlogged=false]' % (norm(name), facing, half)


def slab(name, typ='bottom'):
    return '%s[type=%s,waterlogged=false]' % (norm(name), typ)


def ladder(facing):
    return 'minecraft:ladder[facing=%s,waterlogged=false]' % facing


def trapdoor(name, facing, half='top', open_='false'):
    return '%s[facing=%s,half=%s,open=%s,powered=false,waterlogged=false]' % (norm(name), facing, half, open_)


HANG_LANTERN = 'minecraft:lantern[hanging=true,waterlogged=false]'
STAND_LANTERN = 'minecraft:lantern[hanging=false,waterlogged=false]'
CHAIN_Y = 'minecraft:iron_chain[axis=y,waterlogged=false]'

DIRS = {'east': (1, 0), 'west': (-1, 0), 'south': (0, 1), 'north': (0, -1)}


def facing_of(dx, dz):
    for k, v in DIRS.items():
        if v == (dx, dz):
            return k
    raise ValueError((dx, dz))


def hsh(*a):
    h = 2166136261
    for v in a:
        h ^= (v * 0x9E3779B1) & 0xffffffff
        h = (h * 16777619) & 0xffffffff
        h ^= h >> 13
    return h


def choose(r, opts):
    tot = sum(w for _, w in opts)
    r %= tot
    for b, wgt in opts:
        if r < wgt:
            return b
        r -= wgt
    return opts[-1][0]

# --------------------------------------------------------------------------- world


class World:
    def __init__(self, sx, sy, sz, seed=1):
        self.sx, self.sy, self.sz, self.seed = sx, sy, sz, seed
        self.pal = ['minecraft:air']
        self.idx = {'minecraft:air': 0}
        self.g = array('H', [0]) * (sx * sy * sz)

    def i(self, x, y, z):
        return (y * self.sz + z) * self.sx + x

    def inb(self, x, y, z):
        return 0 <= x < self.sx and 0 <= y < self.sy and 0 <= z < self.sz

    def sid(self, b):
        b = norm(b)
        if '[' in b:
            b = fmt_state(*parse_state(b))
        k = self.idx.get(b)
        if k is None:
            k = len(self.pal)
            self.pal.append(b)
            self.idx[b] = k
        return k

    def r(self, x, y, z, salt=0):
        return hsh(x, y, z, self.seed, salt)

    def set(self, x, y, z, b):
        if not self.inb(x, y, z):
            return
        if callable(b):
            b = b(x, y, z)
            if b is None:
                return
        self.g[self.i(x, y, z)] = self.sid(b)

    def get(self, x, y, z):
        if not self.inb(x, y, z):
            return 'minecraft:air'
        return self.pal[self.g[self.i(x, y, z)]]

    def name(self, x, y, z):
        return self.get(x, y, z).split('[')[0]

    def fill(self, x0, y0, z0, x1, y1, z1, b):
        xa, xb = sorted((x0, x1))
        ya, yb = sorted((y0, y1))
        za, zb = sorted((z0, z1))
        xa, ya, za = max(xa, 0), max(ya, 0), max(za, 0)
        xb, yb, zb = min(xb, self.sx - 1), min(yb, self.sy - 1), min(zb, self.sz - 1)
        if callable(b):
            for y in range(ya, yb + 1):
                for z in range(za, zb + 1):
                    for x in range(xa, xb + 1):
                        v = b(x, y, z)
                        if v is not None:
                            self.g[self.i(x, y, z)] = self.sid(v)
        else:
            s = self.sid(b)
            for y in range(ya, yb + 1):
                for z in range(za, zb + 1):
                    base = self.i(0, y, z)
                    for x in range(xa, xb + 1):
                        self.g[base + x] = s

    def air(self, x0, y0, z0, x1, y1, z1):
        self.fill(x0, y0, z0, x1, y1, z1, 'minecraft:air')

    # ---- composite pieces

    def base(self, floor, wall, wall_top=8, foundation='minecraft:deepslate'):
        sx, sy, sz = self.sx, self.sy, self.sz
        self.fill(0, 0, 0, sx - 1, 0, sz - 1, foundation)
        self.fill(1, 1, 1, sx - 2, 1, sz - 2, lambda x, y, z: floor(x, z))
        for x in range(sx):
            for z in range(sz):
                if x in (0, sx - 1) or z in (0, sz - 1):
                    for y in range(1, wall_top + 1):
                        self.set(x, y, z, wall(x, y, z))
                    for y in range(wall_top + 1, sy):
                        self.set(x, y, z, 'minecraft:barrier')

    def crate(self, x0, y0, z0, dx, dy, dz, frame, face, top=None):
        """Box from (x0,y0,z0) with size dx,dy,dz. Edges get `frame`, faces `face`."""
        for y in range(y0, y0 + dy):
            for z in range(z0, z0 + dz):
                for x in range(x0, x0 + dx):
                    edges = 0
                    big = 0
                    for v, a, n in ((x, x0, dx), (y, y0, dy), (z, z0, dz)):
                        if n >= 3:
                            big += 1
                            if v in (a, a + n - 1):
                                edges += 1
                    if big == 0:
                        b = frame if (x + y + z) % 2 == 0 else face
                    elif big == 1:
                        b = frame if edges else face
                    else:
                        b = frame if edges >= 2 else face
                    if top and y == y0 + dy - 1:
                        b = top
                    self.set(x, y, z, b)

    def stairs_up(self, x0, z0, d, y0, n, width_axis_cells, stair_block, support):
        """Stair run starting at (x0,z0) going in direction d ('south' etc.), first stair at y0,
        n stairs; width given as list of perpendicular offsets. Solid support below each step."""
        dx, dz = DIRS[d]
        for k in range(n):
            for o in width_axis_cells:
                x = x0 + dx * k + (o if dx == 0 else 0)
                z = z0 + dz * k + (o if dz == 0 else 0)
                y = y0 + k
                self.set(x, y, z, stair(stair_block, d))
                if y - 1 >= y0:
                    self.fill(x, y0, z, x, y - 1, z, support)

    def ladder_col(self, x, z, y0, y1, facing):
        for y in range(y0, y1 + 1):
            self.set(x, y, z, ladder(facing))

    def lights_grid(self, x0, z0, x1, z1, y, step, block='minecraft:sea_lantern', ox=0, oz=0):
        for x in range(x0 + ox, x1 + 1, step):
            for z in range(z0 + oz, z1 + 1, step):
                self.set(x, y, z, block)

    def palm(self, x, z, y0, h, lean=(1, 0), log='minecraft:stripped_jungle_log',
             leaf='minecraft:jungle_leaves[distance=1,persistent=true,waterlogged=false]'):
        cx, cz = x, z
        for k in range(h):
            if k in (h // 2, h - 2):
                cx += lean[0]
                cz += lean[1]
            self.set(cx, y0 + k, cz, log + '[axis=y]')
        ty = y0 + h
        self.set(cx, ty, cz, leaf)
        for ddx, ddz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            self.set(cx + ddx, ty, cz + ddz, leaf)
            self.set(cx + 2 * ddx, ty - 1, cz + 2 * ddz, leaf)

    # ---- symmetry and post passes

    def mirror_z(self):
        cache = {}

        def tr(k):
            if k in cache:
                return cache[k]
            name, p = parse_state(self.pal[k])
            q = dict(p)
            if 'facing' in q:
                q['facing'] = {'north': 'south', 'south': 'north'}.get(q['facing'], q['facing'])
            if 'shape' in q and name.endswith('_stairs'):
                q['shape'] = {'inner_left': 'inner_right', 'inner_right': 'inner_left',
                              'outer_left': 'outer_right', 'outer_right': 'outer_left'}.get(q['shape'], q['shape'])
            if 'north' in q and 'south' in q:
                q['north'], q['south'] = p['south'], p['north']
            v = self.sid(fmt_state(name, q))
            cache[k] = v
            return v

        sx, sy, sz = self.sx, self.sy, self.sz
        for y in range(sy):
            for z in range(sz // 2):
                zz = sz - 1 - z
                for x in range(sx):
                    self.g[self.i(x, y, zz)] = tr(self.g[self.i(x, y, z)])

    def connect_pass(self):
        """Compute fence / pane / bars / wall connection properties from neighbours."""
        sx, sy, sz = self.sx, self.sy, self.sz
        kinds = {}
        for k, s in enumerate(self.pal):
            n = s.split('[')[0]
            if n.endswith('_fence'):
                kinds[k] = 'fence'
            elif n.endswith('_pane') or n.endswith('_bars'):
                kinds[k] = 'pane'
            elif n.endswith('_wall'):
                kinds[k] = 'wall'
        if not kinds:
            return
        fullk = set(k for k, s in enumerate(self.pal) if not is_nonfull(s.split('[')[0])
                    and 'leaves' not in s and s != 'minecraft:barrier')
        gate = set(k for k, s in enumerate(self.pal) if s.split('[')[0].endswith('_fence_gate'))
        updates = []
        for y in range(sy):
            for z in range(sz):
                for x in range(sx):
                    k = self.g[self.i(x, y, z)]
                    kind = kinds.get(k)
                    if not kind:
                        continue
                    con = {}
                    for d, (dx, dz) in DIRS.items():
                        nx, nz = x + dx, z + dz
                        if not (0 <= nx < sx and 0 <= nz < sz):
                            con[d] = False
                            continue
                        nk = self.g[self.i(nx, y, nz)]
                        nkind = kinds.get(nk)
                        if nk in fullk:
                            c = True
                        elif kind == 'fence':
                            c = nkind == 'fence' or nk in gate
                        else:
                            c = nkind in ('pane', 'wall') or nk in gate
                        con[d] = c
                    name, p = parse_state(self.pal[k])
                    if kind == 'wall':
                        above = self.g[self.i(x, y + 1, z)] if y + 1 < sy else 0
                        tall = above in fullk
                        for d in DIRS:
                            p[d] = ('tall' if tall else 'low') if con[d] else 'none'
                        straight = (con['north'] and con['south'] and not con['east'] and not con['west']) or \
                                   (con['east'] and con['west'] and not con['north'] and not con['south'])
                        p['up'] = 'false' if straight and not (above and kinds.get(above) == 'wall') else 'true'
                    else:
                        for d in DIRS:
                            p[d] = 'true' if con[d] else 'false'
                    p['waterlogged'] = 'false'
                    updates.append((self.i(x, y, z), fmt_state(name, p)))
        for i, s in updates:
            self.g[i] = self.sid(s)

    # ---- output

    def to_nbt(self):
        sx, sy, sz = self.sx, self.sy, self.sz
        used = sorted(set(self.g))
        remap = {k: n for n, k in enumerate(used)}
        palette = []
        for k in used:
            name, props = parse_state(self.pal[k])
            c = {'Name': name}
            if props:
                c['Properties'] = {kk: props[kk] for kk in sorted(props)}
            palette.append(c)
        full_first, rest = [], []
        nonfull_k = set(k for k in used if is_nonfull(self.pal[k].split('[')[0]))
        for y in range(sy):
            for x in range(sx):
                for z in range(sz):
                    k = self.g[self.i(x, y, z)]
                    (rest if k in nonfull_k else full_first).append((x, y, z, remap[k]))
        parts = []
        hdr_pos = b'\x09' + _str('pos') + b'\x03' + struct.pack('>i', 3)
        hdr_state = b'\x03' + _str('state')
        for x, y, z, s in full_first + rest:
            parts.append(hdr_pos + struct.pack('>iii', x, y, z) + hdr_state + struct.pack('>i', s) + b'\x00')
        n = len(parts)
        return {
            'DataVersion': DATA_VERSION,
            'size': [sx, sy, sz],
            'palette': palette,
            'blocks': Raw(10, n, b''.join(parts)),
            'entities': [],
        }

# --------------------------------------------------------------------------- spawn helpers


def mirror_spawn(s, sz):
    x, y, z, yaw = s
    ny = 180 - yaw
    while ny > 180:
        ny -= 360
    while ny <= -180:
        ny += 360
    return [x, y, sz - 1 - z, ny]


def yaw_towards(x, z, tx, tz):
    """Minecraft yaw: 0 = +Z, 90 = -X, 180 = -Z, -90 = +X. Snapped to 45 degrees."""
    dx, dz = tx - x, tz - z
    a = math.degrees(math.atan2(-dx, dz))
    a = round(a / 45.0) * 45
    if a <= -180:
        a += 360
    return int(a)


def ffa_list(w, half_pts, extra=()):
    sx, sz = w.sx, w.sz
    out = []
    for (x, y, z) in half_pts:
        yaw = yaw_towards(x, z, sx / 2, sz / 2)
        out.append([x, y, z, yaw])
        out.append(mirror_spawn([x, y, z, yaw], sz))
    for (x, y, z) in extra:
        out.append([x, y, z, yaw_towards(x, z, sx / 2, sz / 2)])
    return out

# =========================================================================== FOUNDRY


def build_foundry():
    SX, SY, SZ = 80, 24, 80
    w = World(SX, SY, SZ, seed=11)

    def floor(x, z):
        r = w.r(x, 1, z)
        if x <= 17:  # rail yard (long lane)
            if x in (8, 10):
                return 'polished_blackstone'
            if 7 <= x <= 11 and z % 3 == 0:
                return 'stripped_dark_oak_log[axis=x]'
            return choose(r, [('deepslate_bricks', 5), ('cracked_deepslate_bricks', 2), ('cobbled_deepslate', 2),
                              ('tuff', 1)])
        if x >= 58:  # factory hall
            if (x + z) % 9 == 0:
                return 'chiseled_polished_blackstone'
            return choose(r, [('polished_blackstone_bricks', 6), ('cracked_polished_blackstone_bricks', 2),
                              ('polished_blackstone', 2)])
        d = max(abs(x - 39.5), abs(z - 39.5))
        if 8 <= d < 9:  # hazard ring around the furnace
            return 'yellow_terracotta' if ((x + z) // 2) % 2 == 0 else 'black_terracotta'
        if x % 7 == 2 or z % 7 == 2:
            return choose(r, [('deepslate_tiles', 4), ('cracked_deepslate_tiles', 1)])
        return choose(r, [('polished_deepslate', 8), ('deepslate_tiles', 1), ('smooth_basalt', 1)])

    def wall(x, y, z):
        along = z if x in (0, SX - 1) else x
        if y == 8:
            return 'polished_deepslate'
        if along % 8 == 0:
            return 'polished_blackstone_bricks'
        if y == 5:
            return 'waxed_cut_copper'
        return choose(w.r(x, y, z), [('deepslate_bricks', 6), ('cracked_deepslate_bricks', 1),
                                     ('deepslate_tiles', 1)])

    w.base(floor, wall)
    # perimeter pilasters (small bumps of cover along the wall)
    for a in range(8, SZ - 8, 8):
        for (x, z) in ((1, a), (SX - 2, a)):
            w.fill(x, 2, z, x, 7, z, 'polished_blackstone_bricks')
    for a in range(8, SX - 8, 8):
        w.fill(a, 2, 1, a, 7, 1, 'polished_blackstone_bricks')

    COP = 'waxed_copper_block'
    RUST = 'waxed_weathered_copper'
    RUSTC = 'waxed_oxidized_cut_copper'
    EXPC = 'waxed_exposed_cut_copper'

    # ---------------- spawn A: roofed loading bay, x 27..52, z 1..9
    X0, X1, ZF = 27, 52, 9
    w.fill(X0, 2, 1, X1, 5, ZF, lambda x, y, z: 'polished_blackstone_bricks' if x % 4 == 0 or y == 5
           else 'deepslate_tiles')
    w.air(X0 + 1, 2, 1, X1 - 1, 5, ZF - 1)
    w.fill(X0, 6, 1, X1, 6, ZF, 'polished_blackstone_bricks')
    w.fill(X0, 7, ZF, X1, 7, ZF, 'polished_blackstone_brick_wall')  # parapet
    w.fill(X0, 7, 1, X0, 7, ZF, 'polished_blackstone_brick_wall')
    w.fill(X1, 7, 1, X1, 7, ZF, 'polished_blackstone_brick_wall')
    w.lights_grid(X0 + 3, 3, X1 - 2, 7, 6, 5, ox=0)
    w.lights_grid(X0 + 3, 3, X1 - 2, 7, 6, 4)
    w.fill(X0 + 1, 1, 1, X1 - 1, 1, ZF - 1, lambda x, y, z: (
        ('yellow_terracotta' if (x // 2) % 2 == 0 else 'black_terracotta') if z == ZF - 1 else
        ('polished_blackstone' if (x + z) % 2 else 'polished_deepslate')))
    for xd in (31, 32, 47, 48):  # front doorways
        w.air(xd, 2, ZF, xd, 4, ZF)
    w.air(X0, 2, 4, X0, 4, 6)  # side doors
    w.air(X1, 2, 4, X1, 4, 6)
    for x in range(X0 + 2, X1 - 1):  # lockers on the back wall
        if x % 3 != 0:
            w.set(x, 2, 1, stair('polished_blackstone_brick_stairs', 'north'))
        else:
            w.fill(x, 2, 1, x, 3, 1, RUST)
    w.crate(X0 + 1, 2, 7, 2, 2, 1, RUST, RUSTC)
    w.crate(X1 - 2, 2, 7, 2, 2, 1, RUST, RUSTC)

    # ---------------- transit strip z 10..13
    w.crate(38, 2, 11, 4, 2, 2, 'stripped_dark_oak_log[axis=x]', 'spruce_planks')
    w.crate(5, 2, 4, 3, 2, 2, RUST, RUSTC)
    w.crate(12, 2, 8, 2, 2, 2, 'spruce_planks', 'stripped_spruce_log[axis=z]')
    # buffer stop at the end of the rails
    w.fill(7, 2, 8, 11, 2, 9, 'polished_blackstone_bricks')
    w.fill(7, 3, 9, 11, 3, 9, 'yellow_terracotta')
    w.set(7, 3, 8, 'polished_blackstone_brick_wall')
    w.set(11, 3, 8, 'polished_blackstone_brick_wall')
    w.crate(16, 2, 11, 2, 2, 2, RUST, RUSTC)
    w.crate(25, 2, 11, 2, 2, 2, 'stripped_dark_oak_log[axis=y]', 'spruce_planks')
    w.crate(22, 2, 3, 3, 2, 2, RUST, RUSTC)
    w.crate(56, 2, 3, 2, 2, 2, 'stripped_dark_oak_log[axis=y]', 'spruce_planks')
    w.crate(63, 2, 6, 3, 1, 2, RUST, RUSTC)
    w.crate(72, 2, 2, 3, 2, 2, 'stripped_dark_oak_log[axis=y]', 'spruce_planks')
    w.set(68, 2, 3, 'anvil[facing=east]')

    # ---------------- long lane (rail yard) x 1..17
    for (xa, xb, z) in ((6, 12, 19), (5, 13, 33)):  # crouch walls across the sightline
        w.fill(xa, 2, z, xb, 2, z, lambda x, y, zz: 'polished_blackstone_bricks' if x in (xa, xb)
               else 'polished_deepslate')
    w.crate(2, 2, 14, 3, 2, 3, RUST, RUSTC)
    w.crate(13, 2, 22, 3, 3, 2, 'stripped_dark_oak_log[axis=y]', 'spruce_planks')
    w.crate(2, 2, 26, 2, 2, 3, RUST, RUSTC)
    w.crate(14, 2, 29, 3, 2, 2, RUST, RUSTC)
    w.crate(3, 2, 34, 2, 1, 1, 'spruce_planks', 'spruce_planks')
    # rail cart (a low cover piece on the tracks)
    w.fill(8, 2, 25, 10, 2, 27, 'waxed_exposed_copper')
    w.fill(9, 3, 26, 9, 3, 26, 'waxed_exposed_cut_copper')
    # gantry bridge over the lane, deck y=6 at z 37..42
    w.fill(1, 6, 37, 18, 6, 39, lambda x, y, z: 'polished_blackstone' if z == 37 else 'polished_blackstone_bricks')
    w.fill(1, 7, 37, 17, 7, 37, 'iron_bars')
    for xp in (2, 16):
        w.fill(xp, 2, 38, xp, 5, 39, 'polished_blackstone_bricks')
        w.set(xp, 5, 37, stair('polished_blackstone_brick_stairs', 'south', 'top'))
    w.set(9, 5, 38, HANG_LANTERN)

    # ---------------- separator (long lane | mid), x 18..22
    # ramp from spawn side up to the catwalk (top stair at y=6, z=18); solid wedge below
    w.stairs_up(19, 14, 'south', 2, 5, [0, 1, 2], 'deepslate_tile_stairs', 'deepslate_tiles')
    for xs in (18, 22):
        for z in range(14, 19):
            w.fill(xs, 2, z, xs, 2 + (z - 14), z, 'polished_blackstone_bricks')
    # building z 19..30 with a tight corridor inside, catwalk deck on top (y=6)
    w.fill(18, 2, 19, 22, 5, 30, lambda x, y, z: 'polished_blackstone_bricks' if z % 4 == 0 else (
        'waxed_cut_copper' if y == 5 else 'deepslate_tiles'))
    w.air(19, 2, 20, 21, 5, 29)
    w.fill(18, 6, 19, 22, 6, 30, 'polished_blackstone_bricks')
    for zd in (24, 25):
        w.air(18, 2, zd, 18, 4, zd)
        w.air(22, 2, zd, 22, 4, zd)
    w.set(20, 6, 22, 'sea_lantern')
    w.set(20, 6, 27, 'sea_lantern')
    w.fill(19, 2, 28, 21, 2, 29, lambda x, y, z: RUST if x != 20 else None)
    # catwalk deck continues as a bridge over the middle crossing, z 31..39
    w.fill(18, 6, 31, 22, 6, 39, lambda x, y, z: 'polished_blackstone' if x in (18, 22) else EXPC)
    for zp in (34, 35):
        w.fill(18, 2, zp, 18, 5, zp, 'polished_blackstone_bricks')
        w.fill(22, 2, zp, 22, 5, zp, 'polished_blackstone_bricks')
    w.fill(18, 7, 19, 18, 7, 36, 'iron_bars')
    w.fill(22, 7, 19, 22, 7, 39, 'iron_bars')
    w.set(20, 5, 33, HANG_LANTERN)

    # ---------------- mid: furnace platform x 33..46, z 33..46, deck y=6
    w.fill(33, 6, 33, 46, 6, 46, lambda x, y, z: 'polished_blackstone' if x in (33, 46) or z in (33, 46)
           else 'polished_blackstone_bricks')
    for (px, pz) in ((33, 33), (45, 33)):
        w.fill(px, 2, pz, px + 1, 5, pz + 1, 'polished_blackstone_bricks')
    w.fill(33, 2, 36, 33, 5, 36, 'polished_blackstone_bricks')
    w.fill(46, 2, 36, 46, 5, 36, 'polished_blackstone_bricks')
    for (lx, lz) in ((36, 36), (43, 36)):
        w.set(lx, 6, lz, 'shroomlight')
    # rails around the deck
    w.fill(33, 7, 33, 46, 7, 33, 'polished_blackstone_brick_wall')
    w.fill(33, 7, 33, 33, 7, 39, 'polished_blackstone_brick_wall')
    w.fill(46, 7, 33, 46, 7, 39, 'polished_blackstone_brick_wall')
    w.air(39, 7, 33, 40, 7, 33)  # stair landing
    w.set(33, 7, 36, 'air')
    w.set(46, 7, 36, 'air')
    # furnace core x 38..41, z 38..41
    w.fill(38, 2, 38, 41, 13, 41, lambda x, y, z: (
        'shroomlight' if y in (9, 10) and (x in (39, 40) or z in (39, 40)) else
        'polished_blackstone_bricks' if y in (5, 13) else
        COP if y in (6, 7) else
        'deepslate_tiles'))
    w.fill(37, 7, 37, 42, 7, 42, lambda x, y, z: 'polished_blackstone_bricks' if x in (37, 42) or z in (37, 42)
           else None)
    w.fill(38, 14, 38, 41, 14, 41, lambda x, y, z: 'polished_blackstone_brick_wall' if x in (38, 41) or z in (38, 41)
           else 'magma_block')
    # stairs to the deck
    w.stairs_up(39, 28, 'south', 2, 5, [0, 1], 'polished_blackstone_brick_stairs', 'polished_blackstone_bricks')
    # ladders on the side pillars
    w.ladder_col(32, 36, 2, 6, 'west')
    w.ladder_col(47, 36, 2, 6, 'east')
    # mid cover
    w.fill(25, 2, 21, 31, 2, 21, lambda x, y, z: COP if x % 2 else 'waxed_exposed_copper')
    w.fill(48, 2, 26, 55, 2, 26, lambda x, y, z: 'polished_blackstone_bricks' if x in (48, 55) else 'polished_deepslate')
    w.crate(24, 2, 27, 2, 2, 2, RUST, RUSTC)
    w.crate(54, 2, 15, 3, 2, 3, 'stripped_dark_oak_log[axis=y]', 'spruce_planks')
    w.crate(30, 2, 14, 4, 2, 2, RUST, RUSTC)
    w.crate(47, 2, 13, 3, 2, 2, 'stripped_dark_oak_log[axis=y]', 'spruce_planks')
    w.fill(26, 2, 31, 29, 3, 32, 'deepslate_tiles')
    w.fill(26, 4, 31, 29, 4, 32, slab('waxed_cut_copper_slab'))
    w.crate(35, 2, 34, 3, 2, 2, RUST, RUSTC)       # under-deck cover
    w.crate(42, 2, 35, 3, 2, 1, 'stripped_dark_oak_log[axis=y]', 'spruce_planks')
    w.crate(56, 2, 22, 2, 2, 2, 'stripped_dark_oak_log[axis=y]', 'spruce_planks')
    w.crate(23, 2, 17, 2, 2, 2, RUST, RUSTC)
    w.fill(52, 2, 29, 53, 3, 31, 'deepslate_tiles')
    w.fill(52, 4, 29, 53, 4, 31, slab('waxed_cut_copper_slab'))
    w.crate(55, 2, 37, 2, 2, 2, RUST, RUSTC)
    w.crate(36, 2, 26, 2, 1, 2, 'spruce_planks', 'stripped_spruce_log[axis=z]')
    w.crate(42, 2, 27, 2, 2, 2, RUST, RUSTC)
    w.crate(44, 2, 17, 2, 2, 2, RUST, RUSTC)
    w.crate(34, 2, 22, 2, 1, 2, 'spruce_planks', 'stripped_spruce_log[axis=x]')
    w.crate(50, 2, 33, 2, 2, 2, 'stripped_dark_oak_log[axis=y]', 'spruce_planks')
    w.crate(27, 2, 36, 2, 2, 2, RUST, RUSTC)
    w.set(29, 2, 36, 'waxed_cut_copper')
    # overhead pipe across mid at z=24 (balance beam from the catwalk)
    w.fill(23, 5, 24, 57, 5, 24, lambda x, y, z: COP if x % 6 else 'waxed_cut_copper')
    for xp in (33, 46):
        w.fill(xp, 2, 24, xp, 4, 24, 'polished_blackstone_brick_wall')

    # ---------------- factory hall (CQB) x 58..78, z 11..39 (+ mirror)
    w.fill(58, 2, 11, 78, 5, 39, lambda x, y, z: 'polished_blackstone_bricks' if (z % 4 == 0 and x == 58) or
           (x % 4 == 2 and z == 11) or y == 2 else ('waxed_cut_copper' if y == 5 else 'deepslate_tiles'))
    w.air(59, 2, 12, 78, 5, 39)
    for zz, doors in ((22, (73, 74)), (26, (61, 62)), (34, (66, 67, 75, 76))):
        w.fill(59, 2, zz, 78, 5, zz, lambda x, y, z: 'polished_blackstone_bricks' if y == 2 else 'deepslate_tiles')
        for xd in doors:
            w.air(xd, 2, zz, xd, 4, zz)
    for (z0, z1) in ((16, 17), (23, 25), (30, 31), (36, 37)):
        w.air(58, 2, z0, 58, 4, z1)
    for zw in (13, 14, 19, 20, 28, 39):
        w.fill(58, 3, zw, 58, 4, zw, 'iron_bars')
    for xd in (66, 67, 68, 75, 76):
        w.air(xd, 2, 11, xd, 4, 11)
    w.fill(58, 6, 11, 78, 6, 39, 'polished_blackstone_bricks')
    w.fill(58, 7, 11, 78, 7, 11, 'polished_blackstone_brick_wall')
    w.fill(58, 7, 11, 58, 7, 39, 'polished_blackstone_brick_wall')
    w.lights_grid(60, 13, 77, 39, 6, 4)
    for (cx, cz) in ((63, 16), (73, 29), (67, 36)):
        w.fill(cx, 7, cz, cx + 1, 8, cz + 1, 'polished_blackstone_bricks')
        w.fill(cx, 9, cz, cx + 1, 9, cz + 1, 'polished_blackstone_brick_wall')
    # room 1 (boiler room)
    w.set(61, 2, 13, 'anvil[facing=north]')
    w.crate(63, 2, 15, 2, 2, 2, COP, 'waxed_cut_copper')
    w.fill(69, 2, 18, 70, 4, 19, COP)
    w.fill(69, 5, 18, 70, 5, 19, 'waxed_cut_copper')
    w.fill(76, 2, 15, 77, 2, 17, RUST)
    w.set(66, 5, 19, HANG_LANTERN)
    # corridor crate
    w.set(68, 2, 24, 'spruce_planks')
    w.set(68, 2, 23, slab('spruce_slab'))
    # room 2
    w.fill(64, 2, 30, 64, 5, 30, 'polished_blackstone_bricks')
    w.fill(72, 2, 30, 72, 5, 30, 'polished_blackstone_bricks')
    w.crate(67, 2, 28, 2, 2, 2, RUST, RUSTC)
    w.crate(75, 2, 31, 2, 1, 2, 'spruce_planks', 'stripped_spruce_log[axis=x]')
    # centre hall machine x 66..70, z 38..41
    w.fill(66, 2, 38, 70, 3, 41, 'polished_blackstone_bricks')
    w.fill(67, 4, 39, 69, 5, 40, COP)
    w.fill(66, 4, 38, 70, 4, 38, 'iron_bars')
    w.fill(61, 2, 37, 61, 5, 37, 'polished_blackstone_bricks')
    w.fill(75, 2, 37, 75, 5, 37, 'polished_blackstone_bricks')
    w.set(63, 5, 39, HANG_LANTERN)
    w.set(73, 5, 39, HANG_LANTERN)

    w.mirror_z()
    w.connect_pass()

    teamA = [[33, 2, 4, 0], [38, 2, 5, 0], [41, 2, 5, 0], [46, 2, 4, 0]]
    ffa = ffa_list(w, [(3, 2, 3), (3, 2, 24), (20, 2, 26), (20, 7, 21), (30, 2, 4), (55, 2, 6),
                       (76, 2, 13), (71, 2, 24), (76, 2, 29), (25, 2, 31)])
    meta = {
        'id': 'foundry', 'name': 'Foundry',
        'description': 'Industrial foundry: rail-yard long lane, furnace platform mid, tight factory hall.',
        'size': [SX, SY, SZ],
        'teamA': teamA, 'teamB': [mirror_spawn(s, SZ) for s in teamA], 'ffa': ffa,
        'spectator': [40, 20, 10, 0], 'killY': 0,
    }
    return w, meta


# =========================================================================== OASIS


def house(w, x0, z0, x1, z1, wallfn, roof, doors=(), windows=(), lights=(), parapet='sandstone_wall', h=4,
          floor=None):
    """Closed one-storey building, walls y=2..1+h, roof at y=2+h. doors/windows: list of (x,z) wall cells.
    Doors are 3 tall (y2..4), windows are y3..4 holes filled with the given block (or air)."""
    top = 2 + h
    w.fill(x0, 2, z0, x1, top - 1, z1, lambda x, y, z: wallfn(x, y, z))
    w.air(x0 + 1, 2, z0 + 1, x1 - 1, top - 1, z1 - 1)
    w.fill(x0, top, z0, x1, top, z1, roof)
    if parapet:
        w.fill(x0, top + 1, z0, x1, top + 1, z1, lambda x, y, z: parapet if x in (x0, x1) or z in (z0, z1) else None)
    if floor:
        w.fill(x0 + 1, 1, z0 + 1, x1 - 1, 1, z1 - 1, floor)
    for (x, z) in doors:
        w.air(x, 2, z, x, 4, z)
    for wnd in windows:
        x, z = wnd[0], wnd[1]
        b = wnd[2] if len(wnd) > 2 else 'air'
        w.fill(x, 3, z, x, 4, z, b)
    for (x, z) in lights:
        w.set(x, top, z, 'minecraft:glowstone')
        w.set(x, top - 1, z, HANG_LANTERN)


def build_oasis():
    SX, SY, SZ = 84, 24, 84
    w = World(SX, SY, SZ, seed=23)
    FCX, FCZ = 39.5, 41.5  # fountain centre

    def floor(x, z):
        r = w.r(x, 1, z)
        if x <= 24:  # old town alleys
            return choose(r, [('packed_mud', 5), ('mud_bricks', 2), ('coarse_dirt', 2), ('sandstone', 1)])
        if x >= 62:  # market avenue
            if x in (67, 74):
                return 'cut_sandstone'
            if 68 <= x <= 73:
                return 'smooth_sandstone' if (z // 2) % 2 == 0 else choose(r, [('sandstone', 3), ('cut_sandstone', 1)])
            return choose(r, [('sand', 5), ('sandstone', 2), ('smooth_sandstone', 1)])
        d = math.hypot(x - FCX, z - FCZ)
        if d < 16:
            ring = int(d) // 3
            if int(d) % 3 == 0:
                return 'chiseled_sandstone' if (x + z) % 4 == 0 else 'cut_sandstone'
            return 'smooth_sandstone' if ring % 2 == 0 else choose(r, [('white_terracotta', 2), ('smooth_sandstone', 1)])
        return choose(r, [('sand', 6), ('smooth_sandstone', 2), ('sandstone', 2), ('red_sand', 1)])

    def wall(x, y, z):
        along = z if x in (0, SX - 1) else x
        if y == 8:
            return 'cut_sandstone'
        if along % 6 == 0:
            return 'chiseled_sandstone' if y == 7 else 'cut_sandstone'
        if y == 2:
            return 'smooth_sandstone'
        if y == 6:
            return 'orange_terracotta'
        return choose(w.r(x, y, z), [('sandstone', 6), ('smooth_sandstone', 1)])

    w.base(floor, wall, foundation='sandstone')
    for a in range(6, SZ - 6, 12):
        for x in (1, SX - 2):
            w.fill(x, 2, a, x, 8, a, 'cut_sandstone')
    for a in range(6, SX - 6, 12):
        w.fill(a, 2, 1, a, 8, 1, 'cut_sandstone')

    MUD = lambda x, y, z: 'mud_bricks' if y == 2 or (x + z) % 7 == 0 else ('packed_mud' if y < 5 else 'terracotta')
    SAND = lambda x, y, z: 'cut_sandstone' if y == 2 or y == 5 else 'smooth_sandstone'
    TERRA = lambda x, y, z: 'smooth_sandstone' if y == 2 else ('orange_terracotta' if y == 5 else 'white_terracotta')
    ROOF = 'smooth_sandstone'
    AW1, AW2 = 'orange_wool', 'white_wool'

    def stall(x0, z0, x1, z1, color):
        """Market stall: fence posts and a striped wool awning at y=5."""
        for (px, pz) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
            w.fill(px, 2, pz, px, 4, pz, 'acacia_fence')
        w.fill(x0, 5, z0, x1, 5, z1, lambda x, y, z: color if (z - z0) % 2 == 0 else AW2)

    def planter(x, z, bush=True):
        w.set(x, 2, z, 'cut_sandstone')
        w.set(x, 3, z, 'sand')
        if bush:
            w.set(x, 4, z, 'dead_bush')

    # ---------------- spawn A: caravanserai courtyard x 31..52, z 1..10
    X0, X1, ZF = 31, 52, 10
    w.fill(X0, 2, 1, X1, 5, ZF, lambda x, y, z: 'cut_sandstone' if x % 3 == 0 or y == 5 else 'sandstone')
    w.air(X0 + 1, 2, 1, X1 - 1, 5, ZF - 1)
    w.fill(X0, 6, ZF, X1, 6, ZF, lambda x, y, z: 'sandstone_wall' if x % 2 == 0 else 'cut_sandstone')
    w.fill(X0, 6, 1, X0, 6, ZF, 'cut_sandstone')
    w.fill(X1, 6, 1, X1, 6, ZF, 'cut_sandstone')
    # arcade roof along the back, pillars at z=4
    w.fill(X0 + 1, 6, 1, X1 - 1, 6, 4, ROOF)
    w.fill(X0 + 1, 5, 4, X1 - 1, 5, 4, slab('smooth_sandstone_slab', 'top'))
    for x in range(X0 + 2, X1 - 1, 4):
        w.fill(x, 2, 4, x, 5, 4, 'cut_sandstone')
        w.set(x + 2, 5, 2, HANG_LANTERN)
    w.fill(X0 + 1, 1, 1, X1 - 1, 1, ZF - 1, lambda x, y, z: 'terracotta' if (x + z) % 2 == 0 and z > 4 else
           'smooth_sandstone')
    for xd in (34, 35, 48, 49):
        w.air(xd, 2, ZF, xd, 4, ZF)
    for xd in (34, 48):
        w.set(xd, 4, ZF, stair('sandstone_stairs', 'east', 'top'))
        w.set(xd + 1, 4, ZF, stair('sandstone_stairs', 'west', 'top'))
    w.air(X0, 2, 6, X0, 4, 7)
    w.air(X1, 2, 6, X1, 4, 7)
    # dry well in the courtyard + benches
    w.fill(40, 2, 7, 43, 2, 8, 'cut_sandstone')
    w.fill(41, 2, 7, 42, 2, 8, 'air')
    w.fill(41, 1, 7, 42, 1, 8, 'sand')
    for x in (40, 43):
        w.fill(x, 3, 7, x, 4, 7, 'acacia_fence')
        w.fill(x, 3, 8, x, 4, 8, 'acacia_fence')
    w.fill(40, 5, 7, 43, 5, 8, slab('acacia_slab'))
    for x in range(X0 + 1, X1, 3):
        if 40 <= x <= 43:
            continue
        w.set(x, 2, 1, stair('smooth_sandstone_stairs', 'north'))
    planter(X0 + 1, 9)
    planter(X1 - 1, 9)

    # ---------------- front of spawn (z 11..16)
    w.crate(38, 2, 13, 6, 2, 1, 'cut_sandstone', 'sandstone')
    planter(37, 13)
    planter(44, 13)
    w.crate(29, 2, 12, 2, 2, 2, 'hay_block[axis=y]', 'hay_block[axis=y]')
    w.crate(53, 2, 12, 2, 2, 2, 'hay_block[axis=y]', 'hay_block[axis=y]')

    # ---------------- west: old town (CQB) x 1..24
    house(w, 1, 12, 10, 20, MUD, ROOF, doors=[(10, 15), (10, 16), (5, 20), (6, 20)],
          windows=[(7, 12, 'acacia_fence')], lights=[(7, 17), (3, 14)], floor='packed_mud')
    house(w, 14, 11, 23, 19, TERRA, ROOF, doors=[(14, 17), (14, 18), (23, 14), (23, 15), (18, 19), (19, 19)],
          windows=[(17, 11, 'acacia_fence'), (20, 11, 'acacia_fence')], lights=[(16, 13), (21, 17)], floor='smooth_sandstone')
    house(w, 1, 24, 9, 32, SAND, ROOF, doors=[(5, 24), (6, 24), (9, 29), (9, 30)],
          windows=[(5, 32, 'acacia_fence')], lights=[(6, 28), (3, 26)], floor='terracotta')
    house(w, 13, 24, 23, 32, MUD, ROOF, doors=[(13, 26), (13, 27), (23, 29), (23, 30), (17, 32), (18, 32)],
          windows=[(18, 24, 'acacia_fence'), (23, 26)], lights=[(16, 28), (20, 28)], floor='packed_mud')
    w.fill(16, 2, 25, 16, 4, 27, 'mud_bricks')
    w.crate(20, 2, 25, 2, 1, 2, 'stripped_acacia_log[axis=x]', 'acacia_planks')
    w.set(20, 3, 25, 'potted_dead_bush')
    w.crate(3, 2, 13, 2, 2, 1, 'hay_block[axis=y]', 'hay_block[axis=y]')
    w.crate(17, 2, 12, 2, 1, 1, 'stripped_acacia_log[axis=x]', 'acacia_planks')
    w.set(3, 2, 30, 'stripped_acacia_log[axis=y]')
    w.set(3, 3, 30, 'flower_pot')
    # rooftop ladder on house 3 (alley side)
    w.set(9, 7, 26, 'air')
    w.ladder_col(10, 26, 2, 6, 'east')
    # bath house (centre of the old town) x 3..20, z 36..47
    house(w, 3, 36, 20, 47, SAND, ROOF, doors=[(14, 36), (15, 36), (20, 39), (20, 40), (3, 38), (3, 39)],
          windows=[(7, 36, 'acacia_fence'), (16, 36, 'acacia_fence'), (20, 37)],
          lights=[(5, 41), (17, 41), (11, 37), (9, 39)], floor='smooth_sandstone')
    for (px, pz) in ((7, 39), (11, 39)):
        w.fill(px, 2, pz, px, 5, pz, 'cut_sandstone')
    w.fill(14, 2, 39, 15, 5, 39, 'cut_sandstone')
    w.fill(9, 2, 41, 14, 2, 41, 'cut_sandstone')  # dry bath rim (crouch cover)
    w.set(13, 5, 40, HANG_LANTERN)
    # alley clutter
    w.crate(11, 2, 22, 2, 2, 1, 'hay_block[axis=y]', 'hay_block[axis=y]')
    w.crate(24, 2, 21, 1, 2, 2, 'stripped_acacia_log[axis=y]', 'acacia_planks')
    w.crate(1, 2, 21, 1, 2, 2, 'stripped_acacia_log[axis=y]', 'acacia_planks')
    w.set(22, 2, 34, 'cut_sandstone')
    w.set(22, 3, 34, 'potted_dead_bush')
    planter(12, 33)
    w.crate(4, 2, 4, 3, 2, 2, 'hay_block[axis=y]', 'hay_block[axis=y]')
    w.crate(15, 2, 6, 2, 1, 2, 'stripped_acacia_log[axis=x]', 'acacia_planks')
    w.crate(23, 2, 3, 3, 2, 2, 'cut_sandstone', 'sandstone')
    w.fill(11, 5, 13, 13, 5, 18, lambda x, y, z: AW1 if z % 2 else AW2)  # awning over the alley

    # ---------------- mid: plaza + inn
    house(w, 25, 18, 34, 28, TERRA, ROOF, doors=[(29, 28), (30, 28), (34, 21), (34, 22), (25, 25), (25, 26)],
          windows=[(28, 18, 'acacia_fence'), (31, 18, 'acacia_fence'), (34, 25)],
          lights=[(28, 22), (31, 25)], floor='terracotta')
    w.crate(27, 2, 20, 2, 1, 1, 'stripped_acacia_log[axis=x]', 'acacia_planks')
    w.set(31, 2, 21, 'stripped_acacia_log[axis=y]')
    w.set(31, 3, 21, 'potted_dead_bush')
    # outside stair (plaza side) up to the inn's roof terrace: x 35..36, z 13..17, then a landing at z 18
    w.stairs_up(35, 13, 'south', 2, 5, [0, 1], 'smooth_sandstone_stairs', 'cut_sandstone')
    w.fill(35, 2, 18, 36, 6, 18, 'cut_sandstone')
    w.fill(37, 2, 13, 37, 7, 18, lambda x, y, z: 'sandstone_wall' if y == 7 else ('cut_sandstone' if y <= 2 + (z - 13) else None))
    w.fill(34, 7, 18, 36, 7, 18, 'air')
    w.set(26, 8, 19, STAND_LANTERN)
    for (px, pz) in ((27, 26), (32, 26)):
        w.fill(px, 7, pz, px, 8, pz, 'acacia_fence')
    w.fill(27, 9, 25, 32, 9, 27, lambda x, y, z: AW1 if x % 2 else AW2)
    # fountain
    for z in range(30, 42):
        for x in range(30, 50):
            d = math.hypot(x - FCX, z - FCZ)
            if 5 <= d < 6.2:
                w.set(x, 2, z, 'cut_sandstone' if (x + z) % 3 else 'chiseled_sandstone')
            elif d < 5:
                w.set(x, 1, z, choose(w.r(x, 1, z), [('sand', 3), ('smooth_sandstone', 2), ('terracotta', 1)]))
    w.fill(39, 2, 41, 40, 4, 42, 'chiseled_sandstone')
    w.fill(38, 5, 40, 41, 5, 43, 'cut_sandstone')
    w.fill(39, 5, 41, 40, 5, 42, 'smooth_sandstone')
    w.fill(39, 6, 41, 40, 6, 42, 'sandstone_wall')
    for (x, z) in ((36, 39), (43, 38)):
        w.set(x, 1, z, 'sand')
        w.set(x, 2, z, 'dead_bush')
    w.palm(30, 33, 2, 8, lean=(-1, 0))
    w.palm(50, 31, 2, 7, lean=(1, -1))
    # plaza cover
    w.crate(45, 2, 20, 3, 2, 2, 'hay_block[axis=y]', 'hay_block[axis=y]')
    w.set(45, 4, 20, 'flower_pot')
    w.crate(50, 2, 25, 2, 2, 3, 'stripped_acacia_log[axis=z]', 'acacia_planks')
    w.crate(48, 2, 16, 2, 2, 2, 'cut_sandstone', 'sandstone')
    w.set(48, 4, 16, 'potted_dead_bush')
    w.crate(54, 2, 18, 2, 2, 2, 'hay_block[axis=y]', 'hay_block[axis=y]')
    w.crate(41, 2, 17, 3, 2, 1, 'stripped_acacia_log[axis=x]', 'acacia_planks')
    w.crate(36, 2, 32, 3, 2, 1, 'cut_sandstone', 'sandstone')
    w.set(37, 4, 32, 'potted_dead_bush')
    w.crate(43, 2, 31, 2, 2, 1, 'hay_block[axis=y]', 'hay_block[axis=y]')
    w.crate(38, 2, 24, 4, 1, 1, 'cut_sandstone', 'cut_sandstone')
    planter(37, 24)
    planter(42, 24)
    w.crate(26, 2, 35, 2, 2, 2, 'cut_sandstone', 'sandstone')
    w.set(26, 4, 35, 'potted_dead_bush')
    w.crate(46, 2, 29, 3, 1, 1, 'stripped_acacia_log[axis=x]', 'acacia_planks')
    w.crate(52, 2, 38, 2, 2, 2, 'hay_block[axis=y]', 'hay_block[axis=y]')
    w.fill(32, 2, 28, 34, 2, 29, 'acacia_planks')  # cart
    w.fill(32, 3, 28, 34, 3, 28, 'acacia_fence')

    # ---------------- bazaar row between plaza and avenue, x 56..61
    house(w, 56, 12, 61, 22, SAND, ROOF, doors=[(56, 15), (56, 16), (61, 19), (61, 20)],
          windows=[(56, 20, 'acacia_fence'), (61, 14)], lights=[(58, 17)], floor='smooth_sandstone')
    house(w, 56, 26, 61, 35, TERRA, ROOF, doors=[(56, 31), (56, 32), (61, 28), (61, 29)],
          windows=[(56, 27), (61, 33, 'acacia_fence')], lights=[(58, 30)], floor='terracotta')
    w.crate(58, 2, 13, 2, 1, 1, 'hay_block[axis=y]', 'hay_block[axis=y]')
    w.crate(57, 2, 33, 1, 2, 2, 'stripped_acacia_log[axis=y]', 'acacia_planks')
    w.set(56, 7, 34, 'air')  # ladder onto the bazaar roof from the plaza
    w.ladder_col(55, 34, 2, 6, 'west')
    w.fill(56, 5, 23, 61, 5, 25, lambda x, y, z: AW1 if z == 24 else AW2)
    for (px, pz) in ((56, 23), (61, 23), (56, 25), (61, 25)):
        w.fill(px, 2, pz, px, 4, pz, 'acacia_fence')
    w.fill(57, 2, 39, 60, 2, 39, 'cut_sandstone')
    planter(56, 39)
    planter(61, 39)

    # ---------------- east: market avenue x 62..82
    stall(63, 13, 66, 17, AW1)
    stall(78, 19, 81, 23, 'red_wool')
    stall(63, 27, 66, 31, 'red_wool')
    stall(78, 32, 81, 36, AW1)
    w.crate(64, 2, 14, 2, 1, 3, 'stripped_acacia_log[axis=z]', 'acacia_planks')
    w.set(64, 3, 15, 'potted_dead_bush')
    w.crate(79, 2, 20, 2, 2, 2, 'hay_block[axis=y]', 'hay_block[axis=y]')
    w.crate(63, 2, 28, 3, 1, 1, 'cut_sandstone', 'cut_sandstone')
    w.crate(79, 2, 33, 1, 1, 3, 'stripped_acacia_log[axis=z]', 'acacia_planks')
    w.set(79, 3, 34, 'flower_pot')
    w.fill(68, 2, 22, 73, 2, 22, 'cut_sandstone')
    w.fill(66, 2, 34, 71, 2, 34, 'cut_sandstone')
    w.crate(76, 2, 12, 3, 2, 2, 'cut_sandstone', 'sandstone')
    w.crate(62, 2, 23, 2, 2, 2, 'hay_block[axis=y]', 'hay_block[axis=y]')
    w.crate(76, 2, 27, 2, 2, 2, 'cut_sandstone', 'sandstone')
    # the great gate arch over the avenue at z 39..41 (mirrors to 42..44)
    for px in (64, 76):
        w.fill(px, 2, 39, px + 1, 9, 41, lambda x, y, z: 'chiseled_sandstone' if y == 9 else 'cut_sandstone')
    w.fill(64, 8, 39, 77, 9, 41, lambda x, y, z: 'chiseled_sandstone' if y == 9 and x % 3 == 0 else 'cut_sandstone')
    w.fill(66, 7, 39, 75, 7, 39, stair('sandstone_stairs', 'north', 'top'))
    w.fill(66, 7, 40, 75, 7, 41, 'cut_sandstone')
    w.fill(64, 10, 39, 77, 10, 39, lambda x, y, z: 'sandstone_wall' if x % 2 == 0 else None)
    w.set(70, 6, 41, HANG_LANTERN)
    # town wall closing the avenue end at z=10, two gates, crates behind them
    w.fill(62, 2, 10, 82, 5, 10, lambda x, y, z: 'cut_sandstone' if x % 3 == 0 or y == 5 else 'sandstone')
    w.fill(62, 6, 10, 82, 6, 10, lambda x, y, z: 'sandstone_wall' if x % 2 == 0 else None)
    for xg in (64, 65, 79, 80):
        w.air(xg, 2, 10, xg, 4, 10)
    w.crate(63, 2, 6, 3, 2, 2, 'cut_sandstone', 'sandstone')
    w.crate(79, 2, 6, 3, 2, 1, 'hay_block[axis=y]', 'hay_block[axis=y]')
    # east spawn yard
    w.crate(58, 2, 3, 3, 2, 2, 'hay_block[axis=y]', 'hay_block[axis=y]')
    w.crate(70, 2, 5, 2, 1, 2, 'stripped_acacia_log[axis=x]', 'acacia_planks')
    w.crate(78, 2, 2, 3, 2, 3, 'cut_sandstone', 'sandstone')
    w.palm(71, 7, 2, 7, lean=(0, -1))

    w.mirror_z()
    w.connect_pass()

    teamA = [[36, 2, 6, 0], [39, 2, 5, 0], [44, 2, 5, 0], [47, 2, 6, 0]]
    ffa = ffa_list(w, [(3, 2, 3), (6, 2, 17), (20, 2, 27), (17, 2, 37), (28, 2, 25), (32, 7, 20),
                       (80, 2, 15), (59, 2, 17), (17, 2, 14), (56, 2, 5), (81, 2, 28)])
    meta = {
        'id': 'oasis', 'name': 'Oasis',
        'description': 'Desert town: market-avenue long lane, fountain plaza mid, tight old-town alleys.',
        'size': [SX, SY, SZ],
        'teamA': teamA, 'teamB': [mirror_spawn(s, SZ) for s in teamA], 'ffa': ffa,
        'spectator': [42, 20, 12, 0], 'killY': 0,
    }
    return w, meta

# =========================================================================== HARBOR


def container(w, x0, z0, length, axis, color, y0=2, open_=False):
    """Shipping container. Closed: 3 wide x 3 tall solid. Open (walk-through): 5 wide x 4 tall with a
    3x3 tunnel. Ribs alternate concrete / terracotta of the same colour; steel frame at the ends."""
    width, height = (5, 4) if open_ else (3, 3)
    con, ter = color + '_concrete', color + '_terracotta'
    for a in range(length):
        for b in range(width):
            for y in range(y0, y0 + height):
                x, z = (x0 + a, z0 + b) if axis == 'x' else (x0 + b, z0 + a)
                if a in (0, length - 1):
                    blk = 'iron_block' if (b in (0, width - 1) or y == y0 + height - 1) else con
                else:
                    blk = con if (a % 2 or y == y0 + height - 1) else ter
                w.set(x, y, z, blk)
    if open_:
        for a in range(length):
            for b in range(1, width - 1):
                x, z = (x0 + a, z0 + b) if axis == 'x' else (x0 + b, z0 + a)
                w.air(x, y0, z, x, y0 + 2, z)
        mid = length // 2
        x, z = (x0 + mid, z0 + width // 2) if axis == 'x' else (x0 + width // 2, z0 + mid)
        w.set(x, y0 + 3, z, 'sea_lantern')


def build_harbor():
    SX, SY, SZ = 72, 24, 92
    w = World(SX, SY, SZ, seed=37)

    def floor(x, z):
        r = w.r(x, 1, z)
        if x <= 17:  # quay boardwalk
            if x <= 2:
                return 'polished_andesite' if z % 2 else 'andesite'
            if x % 4 == 1:
                return 'stripped_spruce_log[axis=z]'
            return choose(r, [('spruce_planks', 8), ('stripped_spruce_wood[axis=y]', 1)])
        if x >= 52:  # warehouse
            return choose(r, [('smooth_stone', 4), ('stone_bricks', 2), ('cracked_stone_bricks', 1)])
        if x in (19, 50) or (x in (29, 40) and z % 4 < 2):
            return 'yellow_concrete'
        if x % 6 == 0 or z % 6 == 0:
            return 'polished_andesite'
        return choose(r, [('andesite', 3), ('stone', 3), ('gray_concrete', 1), ('gravel', 1)])

    def wall(x, y, z):
        along = z if x in (0, SX - 1) else x
        if y == 8:
            return 'polished_andesite'
        if x == 0 and y <= 4:
            return 'dark_prismarine' if y < 4 else 'prismarine_bricks'
        if along % 7 == 0:
            return 'stripped_dark_oak_log[axis=y]'
        return choose(w.r(x, y, z), [('stone_bricks', 5), ('mossy_stone_bricks', 1), ('cracked_stone_bricks', 1)])

    w.base(floor, wall, foundation='stone')
    for a in range(7, SZ - 7, 7):  # bollards along the quay edge
        w.set(2, 2, a, 'polished_andesite')
    for a in range(7, SX - 7, 7):
        w.fill(a, 2, 1, a, 7, 1, 'stripped_dark_oak_log[axis=y]')

    # ---------------- spawn A: dock office x 24..47, z 1..10
    X0, X1, ZF = 24, 47, 10
    w.fill(X0, 2, 1, X1, 5, ZF, lambda x, y, z: 'stripped_dark_oak_log[axis=y]' if x % 4 == 0 or x in (X0, X1)
           else ('dark_oak_planks' if y == 5 else 'spruce_planks'))
    w.air(X0 + 1, 2, 1, X1 - 1, 5, ZF - 1)
    w.fill(X0, 6, 1, X1, 6, ZF, 'dark_oak_planks')
    w.fill(X0, 7, 1, X1, 7, ZF, lambda x, y, z: slab('dark_oak_slab') if x in (X0, X1) or z == ZF else None)
    w.lights_grid(X0 + 3, 3, X1 - 2, 8, 6, 5, block='sea_lantern')
    w.fill(X0 + 1, 1, 1, X1 - 1, 1, ZF - 1, lambda x, y, z: 'spruce_planks' if (x + z) % 2 else 'stripped_spruce_wood[axis=y]')
    for xd in (28, 29, 42, 43):
        w.air(xd, 2, ZF, xd, 4, ZF)
    w.air(X0, 2, 5, X0, 4, 6)
    w.air(X1, 2, 5, X1, 4, 6)
    for x in range(X0 + 2, X1 - 1, 3):  # benches and shelves
        w.set(x, 2, 1, stair('spruce_stairs', 'north'))
        w.set(x + 1, 2, 1, 'spruce_planks')
        w.set(x + 1, 3, 1, 'flower_pot')
    w.crate(X0 + 1, 2, 8, 2, 2, 1, 'stripped_spruce_log[axis=x]', 'spruce_planks')
    w.crate(X1 - 2, 2, 8, 2, 2, 1, 'stripped_spruce_log[axis=x]', 'spruce_planks')
    w.fill(X0, 5, ZF + 1, X1, 5, ZF + 1, trapdoor('spruce_trapdoor', 'south', 'top'))  # awning

    # ---------------- west: quay (long lane) x 1..17
    w.crate(4, 2, 11, 5, 2, 2, 'stripped_spruce_log[axis=x]', 'spruce_planks')
    w.crate(9, 2, 10, 3, 2, 2, 'blue_terracotta', 'blue_concrete')
    w.crate(13, 2, 12, 3, 2, 2, 'stripped_spruce_log[axis=x]', 'spruce_planks')
    w.fill(16, 2, 13, 17, 3, 13, 'spruce_planks')
    w.fill(3, 2, 13, 3, 3, 13, 'hay_block[axis=y]')
    w.fill(3, 2, 18, 4, 3, 22, 'stripped_spruce_log[axis=z]')  # lumber stack
    w.crate(14, 2, 21, 3, 2, 2, 'stripped_spruce_log[axis=y]', 'oak_planks')
    w.fill(6, 2, 27, 11, 2, 27, 'stripped_spruce_log[axis=x]')  # crouch log
    w.crate(3, 2, 33, 2, 2, 2, 'hay_block[axis=y]', 'hay_block[axis=y]')
    w.fill(9, 2, 36, 13, 2, 36, 'stripped_spruce_log[axis=x]')
    w.crate(13, 2, 43, 2, 2, 2, 'stripped_spruce_log[axis=y]', 'oak_planks')
    # harbour-master tower x 13..17, z 26..30, deck y=10
    TX0, TZ0 = 13, 26
    w.fill(TX0, 2, TZ0, TX0 + 4, 9, TZ0 + 4, lambda x, y, z: 'stripped_dark_oak_log[axis=y]' if x in (TX0, TX0 + 4) and
           z in (TZ0, TZ0 + 4) else ('stone_bricks' if y <= 3 else 'spruce_planks'))
    w.air(TX0 + 1, 2, TZ0 + 1, TX0 + 3, 9, TZ0 + 3)
    w.air(TX0 + 2, 2, TZ0, TX0 + 2, 4, TZ0)  # door toward own spawn
    w.air(TX0, 2, TZ0 + 2, TX0, 4, TZ0 + 2)  # door toward the quay
    w.fill(TX0 - 1, 10, TZ0 - 1, TX0 + 5, 10, TZ0 + 5, 'dark_oak_planks')
    w.ladder_col(TX0 + 2, TZ0 + 3, 2, 10, 'north')
    w.fill(TX0 - 1, 11, TZ0 - 1, TX0 + 5, 11, TZ0 + 5, lambda x, y, z: 'spruce_fence' if x in (TX0 - 1, TX0 + 5) or
           z in (TZ0 - 1, TZ0 + 5) else None)
    for (px, pz) in ((TX0 - 1, TZ0 - 1), (TX0 + 5, TZ0 - 1), (TX0 - 1, TZ0 + 5), (TX0 + 5, TZ0 + 5)):
        w.fill(px, 11, pz, px, 13, pz, 'stripped_dark_oak_log[axis=y]')
    w.fill(TX0 - 1, 14, TZ0 - 1, TX0 + 5, 14, TZ0 + 5, slab('dark_oak_slab'))
    w.set(TX0 + 2, 13, TZ0 + 1, HANG_LANTERN)
    w.set(TX0 + 1, 6, TZ0 + 1, 'sea_lantern')
    w.set(TX0 + 3, 9, TZ0 + 1, 'sea_lantern')
    w.set(TX0 + 1, 3, TZ0 + 1, 'sea_lantern')

    # ---------------- mid: container yard x 19..50
    container(w, 21, 14, 8, 'z', 'red')
    container(w, 27, 15, 8, 'x', 'blue')
    container(w, 38, 13, 8, 'x', 'orange')
    container(w, 39, 13, 6, 'x', 'green', y0=5)          # stacked
    container(w, 24, 24, 9, 'z', 'cyan', open_=True)      # walk-through
    container(w, 33, 22, 8, 'z', 'white')
    container(w, 43, 21, 6, 'x', 'green')
    w.crate(49, 2, 21, 2, 2, 2, 'stripped_spruce_log[axis=y]', 'spruce_planks')   # steps onto the green one
    w.crate(49, 2, 23, 2, 1, 2, 'stripped_spruce_log[axis=y]', 'spruce_planks')
    container(w, 20, 36, 7, 'z', 'yellow')
    container(w, 40, 30, 8, 'z', 'red')
    container(w, 45, 33, 5, 'x', 'blue')
    container(w, 29, 41, 9, 'x', 'orange')                 # centre alley (mirrors to z 48..50)
    w.crate(31, 2, 34, 2, 2, 2, 'stripped_spruce_log[axis=y]', 'oak_planks')
    w.crate(36, 2, 33, 2, 1, 2, 'stripped_spruce_log[axis=x]', 'spruce_planks')
    w.crate(47, 2, 40, 2, 2, 2, 'stripped_spruce_log[axis=y]', 'spruce_planks')
    w.fill(26, 2, 37, 26, 2, 39, 'hay_block[axis=y]')
    w.crate(19, 2, 26, 1, 2, 2, 'stripped_spruce_log[axis=y]', 'oak_planks')
    # crane straddling the centre: legs at x 23..24 / 43..44, z 38..39 (+ mirror), girder deck y=10
    for lx in (23, 43):
        w.fill(lx, 2, 38, lx + 1, 17, 39, 'yellow_concrete')
    w.fill(22, 10, 37, 45, 10, 39, lambda x, y, z: 'yellow_concrete' if z == 38 else 'yellow_terracotta')
    w.fill(22, 11, 37, 45, 11, 37, 'iron_bars')
    w.fill(22, 11, 39, 45, 11, 39, lambda x, y, z: 'iron_bars' if x not in (23, 24, 25, 42, 43, 44) else None)
    w.fill(22, 11, 37, 22, 11, 39, 'iron_bars')
    w.fill(45, 11, 37, 45, 11, 39, 'iron_bars')
    w.ladder_col(25, 39, 2, 10, 'east')
    w.ladder_col(42, 39, 2, 10, 'west')
    w.fill(23, 18, 38, 44, 19, 39, 'yellow_concrete')
    w.fill(23, 18, 40, 24, 19, 45, 'yellow_concrete')
    w.fill(43, 18, 40, 44, 19, 45, 'yellow_concrete')
    w.fill(32, 17, 42, 35, 17, 45, 'gray_concrete')           # trolley
    w.fill(32, 14, 42, 34, 16, 43, lambda x, y, z: 'white_concrete' if y != 15 or x == 34 else 'glass_pane')
    w.set(33, 13, 43, HANG_LANTERN)
    for cx in (33, 35):
        w.fill(cx, 12, 45, cx, 16, 45, CHAIN_Y)
    w.fill(32, 11, 44, 36, 11, 45, 'yellow_concrete')          # spreader
    w.set(23, 20, 38, 'sea_lantern')
    w.set(44, 20, 38, 'sea_lantern')

    # ---------------- east: warehouse x 52..70, z 12..79 (roof y=7)
    WX0, WX1, WZ0 = 52, 70, 12
    w.fill(WX0, 2, WZ0, WX1, 6, 45, lambda x, y, z: 'stripped_dark_oak_log[axis=y]' if
           (x == WX0 and z % 4 == 0) or (z == WZ0 and x % 4 == 0) else
           ('dark_oak_planks' if y in (2, 6) else 'spruce_planks'))
    w.air(WX0 + 1, 2, WZ0 + 1, WX1, 6, 45)
    w.fill(WX0, 7, WZ0, WX1, 7, 45, 'dark_oak_planks')
    w.fill(WX0, 8, WZ0, WX1, 8, 45, lambda x, y, z: slab('dark_oak_slab') if x == WX0 or z == WZ0 else None)
    w.lights_grid(WX0 + 2, WZ0 + 2, WX1 - 1, 45, 7, 5, block='sea_lantern')
    for (z0, z1) in ((16, 17), (28, 29), (40, 41)):
        w.air(WX0, 2, z0, WX0, 4, z1)
    for zw in (21, 33):
        w.fill(WX0, 3, zw, WX0, 4, zw + 1, 'iron_bars')
    for xd in (55, 56, 67, 68):
        w.air(xd, 2, WZ0, xd, 4, WZ0)
    # office x 64..70, z 13..20
    w.fill(64, 2, 13, 64, 6, 20, 'spruce_planks')
    w.fill(64, 2, 20, 70, 6, 20, 'spruce_planks')
    w.fill(64, 3, 15, 64, 4, 16, 'glass_pane')
    w.air(66, 2, 20, 67, 4, 20)
    w.air(64, 2, 18, 64, 4, 18)
    w.crate(68, 2, 14, 2, 1, 2, 'stripped_dark_oak_log[axis=x]', 'dark_oak_planks')
    w.set(68, 3, 14, 'flower_pot')
    w.set(66, 6, 16, HANG_LANTERN)
    w.set(69, 7, 19, 'sea_lantern')
    w.set(62, 7, 15, 'sea_lantern')
    w.crate(1, 2, 28, 2, 2, 2, 'stripped_spruce_log[axis=y]', 'oak_planks')

    def rack(x0, z0, z1):
        """Two-wide shelving rack along z: posts every 4, shelf at y=4 (top slab), goods below/above."""
        for z in range(z0, z1 + 1):
            for x in (x0, x0 + 1):
                if (z - z0) % 4 == 0 or z == z1:
                    w.fill(x, 2, z, x, 5, z, 'stripped_spruce_log[axis=y]')
                    continue
                r = w.r(x, 2, z, 5)
                w.set(x, 2, z, choose(r, [('spruce_planks', 3), ('hay_block[axis=y]', 1), ('white_wool', 1),
                                          ('blue_terracotta', 1), ('oak_planks', 2)]))
                w.set(x, 3, z, choose(r >> 4, [('spruce_planks', 2), ('oak_planks', 2), ('air', 2),
                                               ('hay_block[axis=y]', 1)]))
                w.set(x, 4, z, slab('spruce_slab', 'top'))
                w.set(x, 5, z, choose(r >> 8, [('spruce_planks', 1), ('air', 2), ('white_wool', 1)]))
    rack(56, 23, 31)
    rack(61, 22, 30)
    rack(66, 25, 33)
    rack(56, 35, 42)
    rack(61, 37, 44)
    rack(66, 37, 42)
    w.crate(58, 2, 14, 3, 2, 3, 'stripped_spruce_log[axis=y]', 'spruce_planks')
    w.fill(58, 2, 43, 59, 3, 44, 'yellow_concrete')  # forklift
    w.set(58, 4, 43, 'black_concrete')
    w.fill(60, 2, 43, 60, 4, 44, 'iron_bars')
    w.set(55, 6, 30, HANG_LANTERN)
    w.set(65, 6, 35, HANG_LANTERN)
    # spawn-side yards
    container(w, 52, 3, 7, 'x', 'green')
    w.crate(63, 2, 6, 2, 2, 2, 'stripped_spruce_log[axis=y]', 'spruce_planks')
    w.crate(67, 2, 2, 3, 1, 2, 'stripped_spruce_log[axis=x]', 'oak_planks')
    w.crate(49, 2, 8, 2, 2, 1, 'stripped_spruce_log[axis=x]', 'spruce_planks')
    w.crate(19, 2, 3, 3, 2, 2, 'stripped_spruce_log[axis=x]', 'spruce_planks')
    w.crate(1, 2, 6, 2, 2, 2, 'stripped_spruce_log[axis=y]', 'oak_planks')
    w.crate(12, 2, 6, 2, 2, 2, 'stripped_spruce_log[axis=y]', 'oak_planks')
    w.crate(17, 2, 7, 2, 2, 2, 'blue_terracotta', 'blue_concrete')
    w.crate(51, 2, 4, 1, 2, 2, 'stripped_spruce_log[axis=y]', 'spruce_planks')
    w.crate(18, 2, 31, 2, 2, 2, 'stripped_spruce_log[axis=y]', 'oak_planks')
    w.crate(50, 2, 27, 2, 2, 2, 'stripped_spruce_log[axis=y]', 'spruce_planks')
    w.crate(25, 2, 20, 2, 2, 2, 'stripped_spruce_log[axis=y]', 'oak_planks')
    w.crate(38, 2, 26, 2, 2, 2, 'stripped_spruce_log[axis=y]', 'spruce_planks')
    w.crate(53, 2, 36, 3, 2, 2, 'stripped_spruce_log[axis=y]', 'spruce_planks')
    w.crate(63, 2, 34, 2, 2, 2, 'stripped_spruce_log[axis=y]', 'oak_planks')
    # more quay cover (off the centre line so the long sightline stays)
    w.crate(5, 2, 16, 3, 1, 2, 'stripped_spruce_log[axis=x]', 'spruce_planks')
    w.crate(12, 2, 32, 3, 2, 2, 'stripped_spruce_log[axis=y]', 'oak_planks')
    # dry-docked fishing boat x 3..7, z 37..54 (bow toward each team), deck y=4 (stand y=5)
    for z in range(37, 46):
        half = 0 if z == 37 else (1 if z == 38 else 2)
        for x in range(5 - half, 5 + half + 1):
            edge = x in (5 - half, 5 + half) or z == 37
            w.set(x, 2, z, 'dark_oak_planks')
            w.set(x, 3, z, 'white_concrete' if edge else 'spruce_planks')
            w.set(x, 4, z, 'stripped_spruce_wood[axis=y]' if not edge else 'dark_oak_planks')
            if edge:
                w.set(x, 5, z, 'spruce_fence')
    w.set(5, 5, 37, 'spruce_fence')
    w.set(5, 6, 37, STAND_LANTERN)
    w.set(7, 5, 41, 'air')
    w.ladder_col(8, 41, 2, 4, 'east')
    w.fill(3, 5, 43, 7, 7, 45, lambda x, y, z: 'stripped_dark_oak_log[axis=y]' if x in (3, 7) and z == 43 else
           ('white_concrete' if y < 7 else 'spruce_planks'))
    w.air(4, 5, 44, 6, 7, 45)
    w.air(5, 5, 43, 5, 6, 43)
    w.fill(4, 6, 43, 4, 6, 43, 'glass_pane')
    w.fill(6, 6, 43, 6, 6, 43, 'glass_pane')
    w.fill(3, 8, 43, 7, 8, 45, slab('dark_oak_slab'))
    w.set(5, 7, 45, 'glowstone')
    w.fill(4, 5, 45, 6, 5, 45, 'air')
    # props under the hull
    w.fill(2, 2, 40, 2, 2, 41, 'stripped_spruce_log[axis=z]')
    w.fill(8, 2, 43, 8, 2, 44, 'stripped_spruce_log[axis=z]')
    w.crate(31, 2, 13, 3, 2, 1, 'stripped_spruce_log[axis=x]', 'oak_planks')

    w.mirror_z()
    w.connect_pass()

    teamA = [[29, 2, 4, 0], [33, 2, 6, 0], [38, 2, 6, 0], [42, 2, 4, 0]]
    ffa = ffa_list(w, [(3, 2, 3), (2, 2, 25), (15, 11, 28), (5, 5, 41), (27, 2, 29), (68, 2, 17), (54, 2, 34),
                       (22, 2, 3), (67, 2, 5), (36, 2, 18), (64, 2, 27)])
    meta = {
        'id': 'harbor', 'name': 'Harbor',
        'description': 'Docks: quay long lane with harbour towers, container yard under a crane, warehouse racks.',
        'size': [SX, SY, SZ],
        'teamA': teamA, 'teamB': [mirror_spawn(s, SZ) for s in teamA], 'ffa': ffa,
        'spectator': [36, 21, 46, 0], 'killY': 0,
    }
    return w, meta

# =========================================================================== LOBBY


def build_lobby():
    SX, SY, SZ = 41, 16, 41
    w = World(SX, SY, SZ, seed=5)
    C = 20

    def floor(x, z):
        dx, dz = x - C, z - C
        d = math.hypot(dx, dz)
        if d < 2:
            return 'white_concrete'
        if (dx == 0 or dz == 0) and d < 9:
            return 'red_concrete' if d >= 3 else 'polished_blackstone'
        if 6.5 <= d < 7.5:
            return 'polished_blackstone'
        if d < 12:
            return 'polished_andesite' if (x + z) % 2 else 'smooth_stone'
        if abs(dx) <= 1 or abs(dz) <= 1:
            return 'polished_blackstone_bricks'
        return choose(w.r(x, 1, z), [('stone_bricks', 4), ('polished_andesite', 2), ('cracked_stone_bricks', 1)])

    def wall(x, y, z):
        along = z if x in (0, SX - 1) else x
        if y == 6:
            return 'polished_blackstone_bricks'
        if along % 5 == 0:
            return 'sea_lantern' if y == 4 else 'polished_blackstone_bricks'
        return 'deepslate_tiles' if y < 3 else 'polished_deepslate'

    w.base(floor, wall, wall_top=6, foundation='stone')
    for (cx, cz) in ((1, 1), (SX - 4, 1), (1, SZ - 4), (SX - 4, SZ - 4)):  # corner towers
        w.fill(cx, 2, cz, cx + 2, 6, cz + 2, 'polished_blackstone_bricks')
        w.fill(cx, 7, cz, cx + 2, 7, cz + 2, 'polished_blackstone_brick_wall')
        w.set(cx + 1, 7, cz + 1, 'sea_lantern')
        w.set(cx + 1, 8, cz + 1, STAND_LANTERN)

    booths = {
        'duel': ((C, 5), 'red', 'south'),
        'team2': ((SX - 6, C), 'blue', 'west'),
        'team4': ((C, SZ - 6), 'lime', 'north'),
        'ffa': ((5, C), 'yellow', 'east'),
    }
    npcs = {}
    for key, ((bx, bz), col, face) in booths.items():
        dx, dz = DIRS[face]
        # raised 7x7 pad (top block y=2, NPC stands at y=3)
        w.fill(bx - 3, 2, bz - 3, bx + 3, 2, bz + 3, lambda x, y, z: col + '_concrete'
               if max(abs(x - bx), abs(z - bz)) <= 1 else 'polished_blackstone_bricks')
        opp = {'south': 'north', 'north': 'south', 'east': 'west', 'west': 'east'}[face]
        for o in (-1, 0, 1):  # step up from the plaza side
            sx_, sz_ = bx + dx * 4 + (o if dx == 0 else 0), bz + dz * 4 + (o if dz == 0 else 0)
            w.set(sx_, 2, sz_, stair('polished_blackstone_brick_stairs', opp))
        for (px, pz) in ((bx - 3, bz - 3), (bx + 3, bz - 3), (bx - 3, bz + 3), (bx + 3, bz + 3)):
            w.fill(px, 3, pz, px, 6, pz, 'polished_blackstone_brick_wall')
        w.fill(bx - 3, 7, bz - 3, bx + 3, 7, bz + 3, lambda x, y, z: col + '_concrete'
               if max(abs(x - bx), abs(z - bz)) == 3 else 'polished_blackstone_bricks')
        w.set(bx, 7, bz, 'sea_lantern')
        w.fill(bx - 2, 8, bz - 2, bx + 2, 8, bz + 2, lambda x, y, z: slab('polished_blackstone_brick_slab')
               if max(abs(x - bx), abs(z - bz)) == 2 else None)
        # back wall with a coloured stripe (concrete, no banners)
        bxw, bzw = bx - dx * 3, bz - dz * 3
        stripe = lambda x, y, z: col + '_concrete' if y == 4 else 'polished_blackstone_bricks'
        if dx == 0:
            w.fill(bx - 2, 3, bzw, bx + 2, 6, bzw, stripe)
        else:
            w.fill(bxw, 3, bz - 2, bxw, 6, bz + 2, stripe)
        npcs[key] = [bx, 3, bz, yaw_towards(bx, bz, C, C)]
    for (lx, lz) in ((11, 11), (29, 11), (11, 29), (29, 29)):  # lamp posts
        w.fill(lx, 2, lz, lx, 5, lz, 'polished_blackstone_wall')
        w.set(lx, 6, lz, STAND_LANTERN)
    for (px, pz) in ((6, 6), (34, 6), (6, 34), (34, 34)):  # planters
        w.fill(px - 1, 2, pz - 1, px + 1, 2, pz + 1, 'polished_blackstone_bricks')
        w.set(px, 2, pz, 'moss_block')
        w.set(px, 3, pz, 'flowering_azalea')
    for (bx, bz, f) in ((14, 12, 'north'), (26, 12, 'north'), (14, 28, 'south'), (26, 28, 'south'),
                        (12, 14, 'west'), (12, 26, 'west'), (28, 14, 'east'), (28, 26, 'east')):  # benches
        dx, dz = DIRS[f]
        for o in (-1, 0, 1):
            x, z = (bx + o, bz) if dx == 0 else (bx, bz + o)
            w.set(x, 2, z, stair('dark_oak_stairs', f))
    for a in range(45, 360, 90):  # little lamp ring around the spawn emblem
        x = int(round(C + 5 * math.cos(math.radians(a))))
        z = int(round(C + 5 * math.sin(math.radians(a))))
        w.set(x, 2, z, 'polished_blackstone_wall')
        w.set(x, 3, z, STAND_LANTERN)
    w.connect_pass()
    meta = {'id': 'lobby', 'name': 'Lobby', 'size': [SX, SY, SZ], 'spawn': [C, 2, C, 180], 'npcs': npcs}
    return w, meta


MAPS = {
    'foundry': build_foundry,
    'oasis': build_oasis,
    'harbor': build_harbor,
    'lobby': build_lobby,
}

# =========================================================================== PNG preview

COLORS = {
    'air': (40, 44, 52), 'barrier': (25, 25, 30),
}


def _png(path, wpx, hpx, rows):
    raw = b''.join(b'\x00' + bytes(r) for r in rows)

    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', wpx, hpx, 8, 2, 0, 0, 0)) + \
        chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(data)


def block_color(name):
    n = name.replace('minecraft:', '')
    table = [
        ('yellow', (215, 180, 40)), ('black', (30, 28, 32)), ('orange', (200, 110, 40)),
        ('red_', (170, 50, 40)), ('blue', (50, 70, 160)), ('cyan', (40, 130, 140)), ('green', (70, 120, 40)),
        ('white', (215, 215, 210)), ('light_gray', (150, 150, 145)), ('gray', (85, 88, 92)),
        ('magma', (200, 80, 20)), ('shroomlight', (240, 150, 70)), ('sea_lantern', (180, 220, 215)),
        ('glowstone', (240, 210, 120)), ('lantern', (230, 180, 90)),
        ('oxidized', (80, 160, 130)), ('weathered', (100, 150, 110)), ('exposed', (160, 120, 90)),
        ('copper', (190, 110, 70)), ('iron_bars', (150, 150, 155)), ('iron', (200, 200, 200)),
        ('chain', (90, 90, 100)), ('anvil', (70, 70, 72)),
        ('blackstone', (52, 46, 54)), ('deepslate_tile', (60, 60, 64)), ('deepslate', (75, 75, 80)),
        ('basalt', (70, 70, 74)), ('tuff', (100, 104, 96)),
        ('dark_oak', (66, 44, 24)), ('spruce', (112, 80, 48)), ('acacia', (170, 92, 50)),
        ('jungle_leaves', (60, 130, 40)), ('jungle', (150, 120, 80)), ('oak', (160, 130, 80)),
        ('red_sand', (180, 100, 50)), ('red_sandstone', (170, 90, 45)),
        ('chiseled_sandstone', (200, 185, 130)), ('cut_sandstone', (222, 208, 150)),
        ('smooth_sandstone', (226, 214, 160)), ('sandstone', (214, 200, 140)), ('sand', (220, 205, 150)),
        ('terracotta', (152, 94, 68)), ('mud_brick', (140, 105, 80)), ('packed_mud', (142, 107, 80)),
        ('coarse_dirt', (110, 80, 56)), ('dead_bush', (130, 100, 60)), ('pot', (130, 70, 50)),
        ('wool', (220, 220, 220)), ('prismarine', (60, 110, 100)), ('andesite', (130, 132, 132)),
        ('smooth_stone', (160, 160, 160)), ('stone_brick', (122, 122, 122)), ('stone', (125, 125, 125)),
        ('gravel', (120, 115, 112)), ('hay', (190, 160, 40)), ('cobble', (110, 110, 110)),
        ('quartz', (230, 225, 220)), ('bricks', (150, 80, 60)), ('calcite', (225, 225, 220)),
        ('ladder', (150, 110, 60)),
    ]
    for key, c in table:
        if key in n:
            if 'concrete' in n or 'wool' in n:
                pass
            return c
    h = hsh(*[ord(ch) for ch in n])
    return (80 + h % 120, 80 + (h >> 8) % 120, 80 + (h >> 16) % 120)


def reach(w, starts):
    """Walkable reachability for a player: walk, jump up 1, drop any height, climb ladders, stairs count
    as steps. Nodes are feet cells. `w` needs .name(x,y,z), .sx/.sy/.sz. Returns a set of (x,y,z)."""
    sx, sy, sz = w.sx, w.sy, w.sz
    cache = {}

    def pas(x, y, z):
        k = (x, y, z)
        v = cache.get(k)
        if v is None:
            n = w.name(x, y, z) if (0 <= x < sx and 0 <= y < sy and 0 <= z < sz) else 'minecraft:stone'
            v = is_passable(n)
            cache[k] = v
        return v

    def lad(x, y, z):
        return 0 <= y < sy and 'ladder' in w.name(x, y, z)

    def node(x, y, z):
        if not (0 < x < sx - 1 and 0 < z < sz - 1 and 0 < y < sy - 1):
            return False
        if not (pas(x, y, z) and pas(x, y + 1, z)):
            return False
        below = w.name(x, y - 1, z)
        return (not pas(x, y - 1, z) and below != 'minecraft:barrier') or lad(x, y, z) or lad(x, y - 1, z)

    seen = set()
    q = deque()
    for s in starts:
        t = (s[0], s[1], s[2])
        if node(*t):
            seen.add(t)
            q.append(t)
    while q:
        x, y, z = q.popleft()
        cand = []
        if lad(x, y, z) or lad(x, y + 1, z):
            cand.append((x, y + 1, z))
        if lad(x, y - 1, z) or lad(x, y, z):
            cand.append((x, y - 1, z))
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, nz = x + dx, z + dz
            if node(nx, y, nz):
                cand.append((nx, y, nz))
            elif pas(x, y + 2, z) and node(nx, y + 1, nz) and pas(nx, y + 2, nz):
                cand.append((nx, y + 1, nz))
            elif pas(nx, y, nz) and pas(nx, y + 1, nz):
                yy = y - 1
                while yy > 0 and pas(nx, yy, nz) and not node(nx, yy, nz):
                    yy -= 1
                if node(nx, yy, nz):
                    cand.append((nx, yy, nz))
        for c in cand:
            if c not in seen and node(*c):
                seen.add(c)
                q.append(c)
    return seen


def _standable(w, x, y, z):
    return is_passable(w.name(x, y, z)) and is_passable(w.name(x, y + 1, z)) and \
        not is_passable(w.name(x, y - 1, z)) and w.name(x, y - 1, z) != 'minecraft:barrier'


def write_preview(w, meta, path, scale=6):
    """Two panels: left = top-down look, right = tactical plan at body height.
    Plan colours: dark = full cover (2+ high), orange = crouch cover (1 high), light = open floor,
    blue tint = walkable upper level (y >= 5), green dots = ladders/stairs."""
    sx, sz, sy = w.sx, w.sz, w.sy
    gap = 8
    W, H = sx * scale * 2 + gap, sz * scale
    img = [[(20, 20, 24)] * W for _ in range(H)]

    def put(x, z, c, off):
        for py in range(z * scale, z * scale + scale):
            row = img[py]
            for px in range(off + x * scale, off + x * scale + scale):
                row[px] = c

    off2 = sx * scale + gap
    starts = meta.get('teamA', []) + meta.get('teamB', []) + ([meta['spawn']] if 'spawn' in meta else [])
    R = reach(w, starts)
    upper = {}
    for (x, y, z) in R:
        if y >= 5:
            upper[(x, z)] = max(upper.get((x, z), 0), y)
    for z in range(sz):
        for x in range(sx):
            top, ty = 'minecraft:air', 0
            for y in range(sy - 1, -1, -1):
                n = w.name(x, y, z)
                if n not in ('minecraft:air', 'minecraft:barrier'):
                    top, ty = n, y
                    break
            c = block_color(top)
            f = 0.75 + 0.5 * min(1.0, (ty - 1) / 12.0) if ty >= 1 else 0.6
            if top.endswith(('_slab', '_stairs', '_wall', '_fence', '_bars', '_pane')):
                f *= 0.9
            c = tuple(max(0, min(255, int(v * f + 12))) for v in c)
            put(x, z, c, 0)
            if x % 10 == 0 or z % 10 == 0:
                for py in range(z * scale, z * scale + scale):
                    for px in range(x * scale, x * scale + scale):
                        if (x % 10 == 0 and px == x * scale) or (z % 10 == 0 and py == z * scale):
                            img[py][px] = tuple(min(255, v + 30) for v in c)
            # plan panel
            if x in (0, sx - 1) or z in (0, sz - 1):
                pc = (10, 10, 12)
            else:
                b2 = is_passable(w.name(x, 2, z))
                b3 = is_passable(w.name(x, 3, z))
                if not b2 and not b3:
                    pc = (55, 58, 66)
                elif not b2:
                    pc = (210, 120, 50)
                else:
                    pc = (200, 200, 190)
                    roofed = any(not is_passable(w.name(x, y, z)) for y in range(4, sy - 1))
                    if roofed:
                        pc = (165, 165, 160)
                if (x, z) in upper:
                    pc = (90, 140, 220) if not (not b2 and not b3) else (70, 110, 190)
                n2 = w.name(x, 2, z)
                if n2.endswith('_stairs') or 'ladder' in n2:
                    pc = (60, 170, 90)
            put(x, z, pc, off2)

    def mark(s, col):
        x, z = s[0], s[2]
        yaw = math.radians(s[3])
        dx, dz = -math.sin(yaw), math.cos(yaw)
        for off in (0, off2):
            cx, cz = off + int(x * scale + scale / 2), int(z * scale + scale / 2)
            r = scale // 2 + 1
            for py in range(cz - r, cz + r + 1):
                for px in range(cx - r, cx + r + 1):
                    if 0 <= px < W and 0 <= py < H:
                        edge = abs(py - cz) == r or abs(px - cx) == r
                        img[py][px] = (0, 0, 0) if edge else col
            for t in range(r, r + 6):
                px, py = int(cx + dx * t), int(cz + dz * t)
                if 0 <= px < W and 0 <= py < H:
                    img[py][px] = (0, 0, 0)
    for s in meta.get('ffa', []):
        mark(s, (250, 220, 40) if s[1] <= 3 else (255, 255, 140))
    for s in meta.get('teamA', []):
        mark(s, (230, 40, 40))
    for s in meta.get('teamB', []):
        mark(s, (40, 90, 240))
    if 'spawn' in meta:
        mark(meta['spawn'], (60, 220, 90))
    for k, s in meta.get('npcs', {}).items():
        mark(s, (220, 60, 220))
    rows = [bytes(v for p in r for v in p) for r in img]
    _png(path, W, H, rows)


# =========================================================================== checks


class Loaded:
    def __init__(self, nbt):
        self.sx, self.sy, self.sz = nbt['size']
        self.pal = []
        for p in nbt['palette']:
            self.pal.append(fmt_state(p['Name'], p.get('Properties', {})))
        self.g = [None] * (self.sx * self.sy * self.sz)
        self.dupes = 0
        for b in nbt['blocks']:
            x, y, z = b['pos']
            i = (y * self.sz + z) * self.sx + x
            if self.g[i] is not None:
                self.dupes += 1
            self.g[i] = b['state']

    def name(self, x, y, z):
        if not (0 <= x < self.sx and 0 <= y < self.sy and 0 <= z < self.sz):
            return 'minecraft:air'
        return self.pal[self.g[(y * self.sz + z) * self.sx + x]].split('[')[0]


def los_clear(L, a, b):
    ax, ay, az = a[0] + 0.5, a[1] + 1.62, a[2] + 0.5
    bx, by, bz = b[0] + 0.5, b[1] + 1.62, b[2] + 0.5
    d = math.dist((ax, ay, az), (bx, by, bz))
    n = int(d / 0.2) + 1
    for k in range(1, n):
        t = k / n
        x, y, z = ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t
        if not is_see_through(L.name(int(math.floor(x)), int(math.floor(y)), int(math.floor(z)))):
            return False
    return True


def light_report(L):
    sx, sy, sz = L.sx, L.sy, L.sz
    N = sx * sy * sz
    lvl = bytearray(N)
    opaque = bytearray(N)
    pal_opaque = []
    pal_emit = []
    for s in L.pal:
        n = s.split('[')[0]
        op = not is_nonfull(n) and 'leaves' not in n and 'glass' not in n and 'grate' not in n
        pal_opaque.append(1 if op else 0)
        pal_emit.append(LIGHT.get(n, 0))
    q = deque()
    for i in range(N):
        k = L.g[i]
        opaque[i] = pal_opaque[k]
        e = pal_emit[k]
        if e:
            lvl[i] = e
            q.append(i)
    for z in range(sz):
        for x in range(sx):
            for y in range(sy - 1, -1, -1):
                i = (y * sz + z) * sx + x
                if opaque[i]:
                    break
                lvl[i] = 15
                q.append(i)
    layer = sx * sz
    while q:
        i = q.popleft()
        v = lvl[i] - 1
        if v <= 0:
            continue
        x = i % sx
        z = (i // sx) % sz
        y = i // layer
        for j, ok in ((i - 1, x > 0), (i + 1, x < sx - 1), (i - sx, z > 0), (i + sx, z < sz - 1),
                      (i - layer, y > 0), (i + layer, y < sy - 1)):
            if ok and not opaque[j] and lvl[j] < v:
                lvl[j] = v
                q.append(j)
    dark = 0
    total = 0
    worst = 15
    sample = []
    for y in range(1, sy - 1):
        for z in range(1, sz - 1):
            for x in range(1, sx - 1):
                if is_passable(L.name(x, y, z)) and is_passable(L.name(x, y + 1, z)) and \
                        not is_nonfull(L.name(x, y - 1, z)):
                    total += 1
                    v = lvl[(y * sz + z) * sx + x]
                    worst = min(worst, v)
                    if v < 9:
                        dark += 1
                        if len(sample) < 6:
                            sample.append((x, y, z, v))
    if sample:
        print('    dark sample', sample)
    return total, dark, worst


def longest_lane(L, y=3):
    """Longest straight see-through runs along z at eye height, one per x column (top 4)."""
    res = []
    for x in range(1, L.sx - 1):
        run, best = 0, (0, 0, 0)
        for z in range(L.sz):
            if is_see_through(L.name(x, y, z)):
                run += 1
                if run > best[0]:
                    best = (run, z - run + 1, z)
            else:
                run = 0
        res.append((best[0], 'x=%d z%d..%d' % (x, best[1], best[2])))
    res.sort(reverse=True)
    return [r[1] + ' (%d)' % r[0] for r in res if r[0] >= 45][:40] or [res[0][1] + ' (%d)' % res[0][0]]


def blockstate_db():
    """Optional: validate block names/properties against the 1.21.11 client jar if it is around."""
    import glob
    import zipfile
    cands = glob.glob(os.path.expanduser(
        '~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/1.21.11/*.jar'))
    if not cands:
        return None
    db = {}
    with zipfile.ZipFile(cands[0]) as z:
        for n in z.namelist():
            if n.startswith('assets/minecraft/blockstates/') and n.endswith('.json'):
                j = json.loads(z.read(n))
                props = set()
                for key in j.get('variants', {}):
                    for kv in key.split(','):
                        if '=' in kv:
                            props.add(kv.split('=')[0])
                for part in j.get('multipart', []):
                    wh = part.get('when', {})
                    for cond in [wh] + wh.get('OR', []) + wh.get('AND', []):
                        for k in cond:
                            if k not in ('OR', 'AND'):
                                props.add(k)
                db['minecraft:' + n.rsplit('/', 1)[1][:-5]] = props
    return db


def check_one(mid, verbose=True):
    errs = []
    npath = os.path.join(OUT_DIR, mid + '.nbt')
    jpath = os.path.join(OUT_DIR, mid + '.json')
    nbt = nbt_read(npath)
    meta = json.load(open(jpath))
    for key in ('DataVersion', 'size', 'palette', 'blocks', 'entities'):
        if key not in nbt:
            errs.append('missing tag ' + key)
    if nbt.get('DataVersion') != DATA_VERSION:
        errs.append('DataVersion %r' % nbt.get('DataVersion'))
    sx, sy, sz = nbt['size']
    if list(meta['size']) != [sx, sy, sz]:
        errs.append('json size %r != nbt size %r' % (meta['size'], nbt['size']))
    npal = len(nbt['palette'])
    for b in nbt['blocks']:
        x, y, z = b['pos']
        if not (0 <= x < sx and 0 <= y < sy and 0 <= z < sz):
            errs.append('block out of bounds %r' % b['pos'])
            break
        if not (0 <= b['state'] < npal):
            errs.append('bad palette index %r' % b['state'])
            break
    L = Loaded(nbt)
    if len(nbt['blocks']) != sx * sy * sz:
        errs.append('block count %d != %d' % (len(nbt['blocks']), sx * sy * sz))
    if L.dupes:
        errs.append('%d duplicate positions' % L.dupes)
    if any(v is None for v in L.g):
        errs.append('positions without a block')
    if nbt['entities']:
        errs.append('entities not empty')
    for p in nbt['palette']:
        n = p['Name']
        short = n.replace('minecraft:', '')
        if short == 'structure_void' or any(f in short for f in FORBIDDEN):
            errs.append('forbidden block ' + n)
    db = blockstate_db()
    if db is not None:
        for p in nbt['palette']:
            n = p['Name']
            if n not in db and n not in ('minecraft:air', 'minecraft:barrier'):
                errs.append('unknown block ' + n)
            elif n in db:
                for k in p.get('Properties', {}):
                    if k not in db[n] and k not in ('waterlogged', 'persistent', 'distance', 'powered'):
                        errs.append('unknown property %s on %s' % (k, n))

    def inside(s, name):
        x, y, z = s[0], s[1], s[2]
        if not (0 <= x < sx and 0 <= y < sy and 0 <= z < sz):
            errs.append('%s %r out of bounds' % (name, s))
            return False
        return True

    def standable(s, name, need_floor=True):
        if not inside(s, name):
            return
        x, y, z = s[0], s[1], s[2]
        if not is_passable(L.name(x, y, z)) or not is_passable(L.name(x, y + 1, z)):
            errs.append('%s %r not air at feet/head (%s, %s)' % (name, s, L.name(x, y, z), L.name(x, y + 1, z)))
        if need_floor and is_nonfull(L.name(x, y - 1, z)):
            errs.append('%s %r has no solid block below (%s)' % (name, s, L.name(x, y - 1, z)))
        if need_floor and x in (0, sx - 1) or z in (0, sz - 1):
            errs.append('%s %r on the perimeter' % (name, s))

    info = {}
    if mid == 'lobby':
        standable(meta['spawn'], 'spawn')
        for k in ('duel', 'team2', 'team4', 'ffa'):
            if k not in meta['npcs']:
                errs.append('npc %s missing' % k)
            else:
                standable(meta['npcs'][k], 'npc ' + k)
    else:
        if len(meta['teamA']) != 4 or len(meta['teamB']) != 4:
            errs.append('team spawn lists must have exactly 4 entries')
        if len(meta['ffa']) < 14:
            errs.append('only %d ffa spawns' % len(meta['ffa']))
        for s in meta['teamA']:
            standable(s, 'teamA')
            if not s[2] < sz / 2:
                errs.append('teamA %r not on the low-z half' % s)
        for s in meta['teamB']:
            standable(s, 'teamB')
            if not s[2] > sz / 2:
                errs.append('teamB %r not on the high-z half' % s)
        for s in meta['ffa']:
            standable(s, 'ffa')
        inside(meta['spectator'], 'spectator')
        sxp, syp, szp = meta['spectator'][:3]
        if not (is_passable(L.name(sxp, syp, szp)) and is_passable(L.name(sxp, syp + 1, szp))):
            errs.append('spectator spot not air')
        for a in meta['teamA']:
            for b in meta['teamB']:
                if los_clear(L, a, b):
                    errs.append('sightline between team spawns %r <-> %r' % (a, b))
        md = 1e9
        for i, a in enumerate(meta['ffa']):
            for b in meta['ffa'][i + 1:]:
                md = min(md, math.dist(a[:3], b[:3]))
        info['ffa_min_dist'] = round(md, 1)
        if md < 7:
            errs.append('ffa spawns too close (%.1f)' % md)
        heights = sorted(set(s[1] for s in meta['ffa']))
        info['ffa_heights'] = heights
        info['longest_lane'] = longest_lane(L)
        # perimeter + barrier ring
        for x in range(sx):
            for z in range(sz):
                if x in (0, sx - 1) or z in (0, sz - 1):
                    for y in range(1, 7):
                        if is_passable(L.name(x, y, z)):
                            errs.append('perimeter gap at %r' % ((x, y, z),))
                    if L.name(x, sy - 1, z) != 'minecraft:barrier':
                        errs.append('no barrier on top of perimeter at %r' % ((x, z),))
    if mid == 'lobby':
        for x in range(sx):
            for z in range(sz):
                if x in (0, sx - 1) or z in (0, sz - 1):
                    if L.name(x, sy - 1, z) != 'minecraft:barrier':
                        errs.append('no barrier on top of lobby wall at %r' % ((x, z),))
    spawns = []
    if mid == 'lobby':
        spawns = [meta['spawn']] + list(meta['npcs'].values())
    else:
        spawns = meta['teamA'] + meta['teamB'] + meta['ffa']
    for start in (spawns[0], spawns[-1]):
        R = reach(L, [start])
        miss = [s for s in spawns if (s[0], s[1], s[2]) not in R]
        if miss:
            errs.append('from %r cannot reach %r' % (start, miss[:5]))
    info['reachable_cells'] = len(R)
    total, dark, worst = light_report(L)
    info['walkable_cells'] = total
    info['dark_cells(<9)'] = dark
    info['min_light'] = worst
    nonair = sum(1 for v in L.g if L.pal[v] != 'minecraft:air')
    info['blocks_total'] = len(nbt['blocks'])
    info['blocks_nonair'] = nonair
    info['palette'] = npal
    info['nbt_bytes'] = os.path.getsize(npath)
    if info['nbt_bytes'] > 1_000_000:
        errs.append('nbt larger than 1 MB')
    if verbose:
        print('[%s] %s' % (mid, 'OK' if not errs else 'FAIL'))
        for k, v in info.items():
            print('    %-16s %s' % (k, v))
        for e in errs[:40]:
            print('    ERROR', e)
        if len(errs) > 40:
            print('    ... %d more' % (len(errs) - 40))
    return errs

# =========================================================================== main


def generate(mid):
    w, meta = MAPS[mid]()
    os.makedirs(OUT_DIR, exist_ok=True)
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    nbt_write(os.path.join(OUT_DIR, mid + '.nbt'), w.to_nbt())
    with open(os.path.join(OUT_DIR, mid + '.json'), 'w') as f:
        f.write(json_dump(meta))
    write_preview(w, meta, os.path.join(PREVIEW_DIR, mid + '.png'))
    print('wrote', mid)


def json_dump(meta):
    """Pretty JSON with coordinate arrays kept on one line."""
    lines = ['{']
    items = list(meta.items())
    for n, (k, v) in enumerate(items):
        end = ',' if n < len(items) - 1 else ''
        if isinstance(v, list) and v and isinstance(v[0], list):
            lines.append('  %s: [' % json.dumps(k))
            for m, e in enumerate(v):
                lines.append('    %s%s' % (json.dumps(e), ',' if m < len(v) - 1 else ''))
            lines.append('  ]' + end)
        elif isinstance(v, dict):
            lines.append('  %s: {' % json.dumps(k))
            sub = list(v.items())
            for m, (kk, vv) in enumerate(sub):
                lines.append('    %s: %s%s' % (json.dumps(kk), json.dumps(vv), ',' if m < len(sub) - 1 else ''))
            lines.append('  }' + end)
        else:
            lines.append('  %s: %s%s' % (json.dumps(k), json.dumps(v), end))
    lines.append('}')
    return '\n'.join(lines) + '\n'


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('maps', nargs='*', help='map ids to generate (default: all)')
    ap.add_argument('--check', action='store_true', help='only verify the existing files')
    a = ap.parse_args()
    ids = a.maps or list(MAPS)
    if not a.check:
        for mid in ids:
            generate(mid)
    bad = 0
    for mid in ids:
        if os.path.exists(os.path.join(OUT_DIR, mid + '.nbt')):
            bad += bool(check_one(mid))
        else:
            print('[%s] missing' % mid)
            bad += 1
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()

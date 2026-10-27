# Ironsight arena maps

`genmaps.py` builds every arena (and the lobby) in code and writes them as vanilla structure
templates. Python 3 stdlib only: the gzip NBT writer/reader and the PNG writer are in the script.

## Regenerate

    python3 tools/maps/genmaps.py              # all maps + lobby, then the checks
    python3 tools/maps/genmaps.py harbor       # one map (plus its check)
    python3 tools/maps/genmaps.py --check      # only re-read and verify the written files

Output:

- `src/main/resources/data/ironsight/arenas/<id>.nbt`: structure template (gzipped NBT, DataVersion 4671 =
  1.21.11). It has `size`, `palette`, `blocks` (every position in the box, air too) and an empty `entities`.
- `src/main/resources/data/ironsight/arenas/<id>.json`: spawns and metadata, relative to the template origin.
- `tools/maps/preview/<id>.png`: on the left a top-down render; on the right a plan at body height
  (dark = full cover, orange = 1-high crouch cover, light = open floor, grey = roofed, blue = upper level a
  player can reach, green = stairs/ladders). Red = team A, blue = team B, yellow = FFA, magenta = lobby NPCs.

## Conventions

- x = width, y = up, z = the team axis. Team A spawns at low z (yaw 0, facing +Z), team B at high z (yaw 180).
- Arena floor blocks are at y=1 (foundation at y=0), so ground-level spawns have y=2. `killY` is 0.
- Yaw is Minecraft yaw: 0 = +Z, 90 = -X, 180 = -Z, -90 = +X.
- Every arena is mirror-symmetric across z = (sizeZ-1)/2: the A half is built, then copied over with
  `facing` north/south swapped. Fence/pane/wall connections are worked out after that.
- The perimeter wall runs from y=1 to y=8 and has barrier blocks above it up to the top of the box.

## What `--check` verifies

The block count equals the size product, there are no duplicate or out-of-bounds positions, and every palette
index is valid. Every block name and property must exist in the 1.21.11 client jar (when the Loom cache is
there), and there are no block entities, fluids or redstone. Every spawn is air at the feet and head with a full
block below. Team spawns are on opposite halves with no eye-level sightline between any A and B spawn. FFA
spawns are at least 7 blocks apart. Every spawn can be reached on foot from both team spawns (a walk, jump,
drop and ladder flood fill). The perimeter and barrier ring are complete. Lighting is checked by a sky and
block light flood fill. The check also prints the longest straight eye-level sightlines along z.

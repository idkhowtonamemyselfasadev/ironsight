#!/usr/bin/env bash
# Generate candidate art for Ironsight with the local image generator (ig2).
# Candidates land in tools/art/work/; pick the best per gun, then run
#   python3 tools/art/finalize.py <id>=<candidate.png>[:flip] ...
# Usage: tools/art/gen_art.sh [guns [id ...]|icon|glint|bg|all]  (default all)
set -euo pipefail
cd "$(dirname "$0")/work"
IG=~/claude/imagegen2/bin/ig2
STYLE="side view profile, muzzle pointing to the right, whole gun visible, dark gunmetal grey body, chunky readable shapes, bold dark outline, flat cel shading, clean game item icon"
SEED=4242

gun1() { # id accent description
  "$IG" sprite "$3, with $2 accent parts, $STYLE" --pixel 32 --colors 12 --count 4 --seed "$SEED" -o "gun_$1.png"
}

guns() {
  local only=" ${*:-} "
  gun() { [[ "$only" == "  " || "$only" == *" $1 "* ]] || return 0; gun1 "$@"; }
  gun pistol  "orange"      "a compact semi-automatic handgun pistol"
  gun revolver "brass gold" "a heavy six-shot revolver with a long barrel and wooden grip"
  gun smg     "cyan"        "a compact submachine gun with a short barrel and a straight magazine"
  gun vector  "lime green"  "a boxy angular futuristic submachine gun like a Kriss Vector, tiny magazine"
  gun rifle   "red"         "an assault rifle with a curved magazine and a solid stock"
  gun burst   "purple"      "a bullpup burst rifle like a FAMAS with a carry handle"
  gun shotgun "brown wood"  "a pump-action shotgun with a big wooden pump grip under the barrel"
  gun lmg     "yellow"      "a bulky light machine gun with a big box magazine and a folded bipod"
  gun dmr     "teal blue"   "a designated marksman rifle with a long barrel and a small scope"
  gun sniper  "dark green"  "a bolt-action sniper rifle with a big long scope and a very long barrel"
}

icon() {
  "$IG" image "game logo emblem, a bold metal iron sight crosshair ring over a stylised rifle silhouette, dark gunmetal and glowing orange accents, centered, strong silhouette, no text, flat vector game icon, dark background" --size 1024x1024 --count 4 --seed "$SEED" -o icon.png
}

glint() {
  "$IG" sprite "a bright lens flare star, four sharp light rays, white hot core, slight warm yellow glow, scope glint" --count 4 --seed "$SEED" -o glint.png
}

bg() {
  "$IG" image "dark subtle military background texture, dark gunmetal steel plate with faint brushed metal and very faint olive camo tint, low contrast, soft vignette, empty, no objects, no text" --size 1792x1008 --count 2 --seed "$SEED" -o menu_bg.png
}

case "${1:-all}" in
  guns) shift; guns "$@" ;; icon) icon ;; glint) glint ;; bg) bg ;;
  all) guns; icon; glint; bg ;;
esac
echo "done; run: $IG free"

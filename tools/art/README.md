# Ironsight art + sound assets

All pictures come from the local image generator (`~/claude/imagegen2/bin/ig2`, FLUX dev);
the scripts here only pick, mirror, pad and resize. Sounds are synthesised (numpy + ffmpeg libvorbis).

## Sounds
    python3 tools/sound/gen_sounds.py
Writes the 19 OGGs (mono, 44.1 kHz, peaks around -1 dBFS) into
`src/main/resources/assets/ironsight/sounds/` plus `sounds.json`. Deterministic (fixed seeds).

## Images
1. Generate candidates (GPU, check Tim is not gaming first):
       tools/art/gen_art.sh            # or: guns | icon | glint | bg
   Prompts and the seed (4242, 4 candidates each -> gun_<id>-1..4.png) are in `gen_art.sh`.
2. Look at each candidate enlarged (nearest-neighbour), then copy the chosen ones in:
       python3 tools/art/finalize.py pistol=work/gun_pistol-2.png rifle=work/gun_rifle-1.png:flip ... \
           icon=work/icon-1.png glint=work/glint-1.png bg=work/menu_bg-1.png
   (`:flip` mirrors so the muzzle points right.) This also rebuilds `tools/art/sheet.png`
   (each gun at 4x plus a 16x16 downscale).
3. `~/claude/imagegen2/bin/ig2 free`

Chosen candidates are listed under "Picks" below.

## Picks (seed 4242 for everything, flux-dev, 2026-10-27)
Run `tools/art/gen_art.sh`, then reproduce the exact set with:

    cd tools/art
    python3 -c "from PIL import Image; im=Image.open('work/icon-2.png').convert('RGB'); m=int(im.width*0.17); im.crop((m,m,im.width-m,im.width-m)).save('work/icon-2-crop.png')"
    python3 finalize.py pistol=work/gun_pistol-2.png revolver=work/gun_revolver-4.png \
        smg=work/gun_smg-1.png vector=work/gun_vector-1.png rifle=work/gun_rifle-4.png \
        burst=work/gun_burst-1.png shotgun=work/gun_shotgun-1.png lmg=work/gun_lmg-1.png \
        dmr=work/gun_dmr-1.png sniper=work/gun_sniper-4.png \
        icon=work/icon-2-crop.png glint=work/glint-3.png bg=work/menu_bg-2.png

| asset | candidate | notes |
|---|---|---|
| pistol | gun_pistol-2 | tan/orange slide |
| revolver | gun_revolver-4 | long barrel, brass, wood grip |
| smg | gun_smg-1 | cyan accents |
| vector | gun_vector-1 | boxy, lime stripe |
| rifle | gun_rifle-4 | AK-ish, red accents |
| burst | gun_burst-1 | carry handle, purple |
| shotgun | gun_shotgun-1 | wooden stock + pump |
| lmg | gun_lmg-1 | bipod legs, yellow muzzle |
| dmr | gun_dmr-1 | teal, small scope |
| sniper | gun_sniper-4 | dark green, big scope, long barrel |
| icon.png | icon-2 | centre crop (17% margin) of a rifle in a glowing iron-sight ring |
| glint.png | glint-3 | warm white star with long horizontal flares |
| menu_bg.png | menu_bg-2 | dark olive-gunmetal with a soft top spotlight |

All gun candidates came out with the muzzle already pointing right, so none needed `:flip`.
Generated with Minecraft running on the same GPU, so a run took about 3 min per gun.

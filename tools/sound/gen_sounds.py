#!/usr/bin/env python3
"""Synthesise all Ironsight sounds (numpy -> WAV -> ffmpeg libvorbis OGG, mono 44.1 kHz).

Run from anywhere:  python3 tools/sound/gen_sounds.py
Writes into src/main/resources/assets/ironsight/sounds/ and sounds.json next to it.
Deterministic (fixed RNG seeds), so re-running gives the same files.
"""
import json
import os
import subprocess
import tempfile
import wave

import numpy as np

SR = 44100
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(ROOT, "src/main/resources/assets/ironsight/sounds")
JSON = os.path.join(ROOT, "src/main/resources/assets/ironsight/sounds.json")


def t_axis(dur):
    return np.arange(int(SR * dur)) / SR


def noise(dur, seed):
    return np.random.default_rng(seed).standard_normal(int(SR * dur))


def onepole_lp(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc
        y[i] = acc
    return y


def lp(x, cutoff, order=2):
    for _ in range(order):
        x = onepole_lp(x, cutoff)
    return x


def hp(x, cutoff):
    return x - lp(x, cutoff, 1)


def bp(x, lo, hi):
    return hp(lp(x, hi, 2), lo)


def env_exp(dur, tau, attack=0.0008):
    t = t_axis(dur)
    e = np.exp(-t / tau)
    a = int(SR * attack)
    if a > 0:
        e[:a] *= np.linspace(0, 1, a)
    return e


def thump(dur, f0, f1, tau, seed=0):
    """Pitch-dropping sine body (the 'boom')."""
    t = t_axis(dur)
    f = f1 + (f0 - f1) * np.exp(-t / 0.02)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(ph) * env_exp(dur, tau, 0.0015)


def pad(x, dur):
    n = int(SR * dur)
    if len(x) >= n:
        return x[:n]
    return np.concatenate([x, np.zeros(n - len(x))])


def at(x, offset, total):
    out = np.zeros(int(SR * total))
    s = int(SR * offset)
    e = min(len(out), s + len(x))
    out[s:e] += x[: e - s]
    return out


def echo_tail(x, delays, gains, cutoff):
    y = x.copy()
    for d, g in zip(delays, gains):
        y += g * lp(at(x, d, len(x) / SR), cutoff, 1)
    return y


def finish(x, peak=0.89, fade=0.01):
    x = x - np.mean(x)
    f = int(SR * fade)
    x[-f:] *= np.linspace(1, 0, f)
    # gentle soft clip for punch, then normalise below 0 dBFS
    x = np.tanh(1.6 * x / (np.max(np.abs(x)) + 1e-9))
    return x / np.max(np.abs(x)) * peak


def gunshot(dur, seed, crack_tau, crack_lo, crack_hi, body_f0, body_f1, body_tau,
            body_gain, mech_gain=0.15, tail=None):
    n = noise(dur, seed)
    crack = bp(n, crack_lo, crack_hi) * env_exp(dur, crack_tau, 0.0003)
    crack /= np.max(np.abs(crack)) + 1e-9
    rumble = lp(noise(dur, seed + 1), 900, 2) * env_exp(dur, body_tau * 1.6, 0.002)
    rumble /= np.max(np.abs(rumble)) + 1e-9
    body = thump(dur, body_f0, body_f1, body_tau)
    mech = hp(noise(dur, seed + 2), 3000) * env_exp(dur, 0.004, 0.0002)
    mech = at(mech[: int(SR * 0.03)], 0.002, dur)
    x = crack + body_gain * body + 0.45 * rumble + mech_gain * mech
    if tail:
        x = echo_tail(x, *tail)
    return finish(x)


def click(dur, seed, freq_lo=1500, freq_hi=7000, tau=0.003, tone=None):
    x = bp(noise(dur, seed), freq_lo, freq_hi) * env_exp(dur, tau, 0.0002)
    if tone:
        t = t_axis(dur)
        x += 0.5 * np.sin(2 * np.pi * tone * t) * env_exp(dur, tau * 3, 0.0002)
    return x / (np.max(np.abs(x)) + 1e-9)


def slide(dur, seed, lo, hi, gain=0.25):
    t = t_axis(dur)
    e = np.sin(np.pi * t / dur) ** 2
    return gain * bp(noise(dur, seed), lo, hi) * e


def tone(freq, dur, tau, harmonics=(1.0, 0.35, 0.15), attack=0.002, detune=0.0):
    t = t_axis(dur)
    x = np.zeros_like(t)
    for k, g in enumerate(harmonics, 1):
        x += g * np.sin(2 * np.pi * freq * k * (1 + detune * k) * t)
    return x * env_exp(dur, tau, attack)


def mix(total, *parts):
    out = np.zeros(int(SR * total))
    for off, sig, g in parts:
        out += g * at(sig, off, total)
    return out


# ------------------------------------------------------------------ sounds
def build():
    s = {}
    s["shot_pistol"] = gunshot(0.35, 11, 0.012, 1200, 9000, 220, 90, 0.035, 0.7)
    s["shot_revolver"] = gunshot(0.55, 12, 0.02, 700, 7000, 160, 55, 0.07, 1.1,
                                 tail=([0.07, 0.14], [0.25, 0.12], 2500))
    s["shot_smg"] = gunshot(0.22, 13, 0.008, 1800, 11000, 260, 120, 0.022, 0.45, 0.25)
    s["shot_vector"] = gunshot(0.18, 14, 0.006, 2500, 12000, 300, 140, 0.018, 0.35, 0.3)
    s["shot_rifle"] = gunshot(0.42, 15, 0.016, 900, 9000, 180, 70, 0.05, 0.9,
                              tail=([0.06], [0.18], 3000))
    s["shot_burst"] = gunshot(0.32, 16, 0.011, 1400, 10000, 210, 85, 0.035, 0.7, 0.2)
    s["shot_shotgun"] = gunshot(0.58, 17, 0.03, 300, 5000, 120, 40, 0.11, 1.5,
                                tail=([0.08, 0.17], [0.3, 0.15], 1500))
    s["shot_lmg"] = gunshot(0.45, 18, 0.018, 600, 7500, 140, 50, 0.07, 1.25,
                            tail=([0.07], [0.2], 2200))
    s["shot_dmr"] = gunshot(0.5, 19, 0.01, 2000, 13000, 200, 80, 0.04, 0.8,
                            tail=([0.09, 0.19], [0.22, 0.1], 3500))
    s["shot_sniper"] = gunshot(1.0, 20, 0.018, 1500, 13000, 150, 45, 0.09, 1.3,
                               tail=([0.11, 0.24, 0.40, 0.58], [0.35, 0.22, 0.12, 0.06], 1800))

    # reload: mag release click, mag slide out, mag slide in, seat clack
    s["reload"] = finish(mix(0.55,
                             (0.00, click(0.05, 31, 2000, 8000, 0.003), 0.6),
                             (0.04, slide(0.12, 32, 800, 4000), 1.0),
                             (0.26, slide(0.10, 33, 900, 4500), 1.0),
                             (0.37, click(0.06, 34, 800, 6000, 0.006, tone=600), 1.0),
                             (0.45, click(0.04, 35, 2500, 9000, 0.002), 0.5)), 0.85)
    s["empty"] = finish(mix(0.12,
                            (0.0, click(0.05, 41, 2500, 9000, 0.0025, tone=1800), 1.0),
                            (0.012, click(0.04, 42, 1000, 4000, 0.004), 0.35)), 0.8)
    s["bolt"] = finish(mix(0.6,
                           (0.00, click(0.05, 51, 1200, 6000, 0.004, tone=500), 0.9),
                           (0.04, slide(0.14, 52, 700, 3500, 0.3), 1.0),
                           (0.19, click(0.05, 53, 1500, 7000, 0.004), 0.8),
                           (0.30, slide(0.14, 54, 800, 3800, 0.3), 1.0),
                           (0.45, click(0.07, 55, 900, 6000, 0.006, tone=420), 1.0)), 0.85)
    s["pump"] = finish(mix(0.5,
                           (0.00, slide(0.13, 61, 300, 2500, 0.45), 1.0),
                           (0.12, click(0.07, 62, 500, 4000, 0.008, tone=260), 1.0),
                           (0.20, slide(0.12, 63, 350, 2800, 0.45), 1.0),
                           (0.31, click(0.08, 64, 600, 5000, 0.009, tone=300), 1.0)), 0.88)

    # feedback
    s["hit"] = finish(tone(2600, 0.07, 0.012, (1.0, 0.2), 0.0005) +
                      0.5 * click(0.07, 71, 4000, 12000, 0.002), 0.8)
    s["headshot"] = finish(mix(0.4,
                               (0.0, tone(1760, 0.4, 0.09, (1.0, 0.0, 0.25, 0.0, 0.1), 0.0005), 1.0),
                               (0.0, tone(2637, 0.3, 0.06, (1.0, 0.2), 0.0005), 0.5),
                               (0.0, click(0.05, 72, 5000, 14000, 0.0015), 0.4)), 0.85)
    s["kill"] = finish(mix(0.45,
                           (0.00, tone(1046.5, 0.2, 0.05, (1.0, 0.3, 0.12)), 0.9),
                           (0.07, tone(1568.0, 0.38, 0.11, (1.0, 0.3, 0.12)), 1.0),
                           (0.00, thump(0.15, 180, 70, 0.04), 0.6)), 0.87)
    s["round"] = finish(mix(0.7,
                            (0.00, tone(659.3, 0.18, 0.06, (1.0, 0.5, 0.25, 0.12)), 0.8),
                            (0.20, tone(880.0, 0.5, 0.16, (1.0, 0.5, 0.25, 0.12)), 1.0)), 0.85)
    # fanfare: C E G (quick) then a held C chord
    notes = [(0.00, 523.25, 0.18), (0.15, 659.25, 0.18), (0.30, 783.99, 0.18)]
    parts = [(o, tone(f, 0.35, d, (1.0, 0.6, 0.35, 0.2, 0.1)), 0.8) for o, f, d in notes]
    for f in (523.25, 659.25, 783.99, 1046.5):
        parts.append((0.48, tone(f, 1.1, 0.45, (1.0, 0.6, 0.35, 0.2, 0.1), 0.01, 0.0008), 0.45))
    parts.append((0.48, thump(0.3, 140, 60, 0.08), 0.5))
    s["win"] = finish(mix(1.6, *parts), 0.85, fade=0.15)
    return s


EVENTS = ["shot.pistol", "shot.revolver", "shot.smg", "shot.vector", "shot.rifle",
          "shot.burst", "shot.shotgun", "shot.lmg", "shot.dmr", "shot.sniper",
          "reload", "empty", "bolt", "pump", "hit", "headshot", "kill", "round", "win"]


def write_ogg(name, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        tmp = f.name
    with wave.open(tmp, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", tmp, "-c:a", "libvorbis",
                    "-q:a", "6", "-ac", "1", "-ar", str(SR), os.path.join(OUT, name + ".ogg")],
                   check=True)
    os.unlink(tmp)


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, x in build().items():
        write_ogg(name, x)
        print(f"{name}.ogg  {len(x) / SR:.2f}s")
    data = {}
    for ev in EVENTS:
        f = ev.replace(".", "_")
        data[ev] = {"sounds": ["ironsight:" + f], "subtitle": "subtitles.ironsight." + ev}
    with open(JSON, "w") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")
    print("wrote", JSON)


if __name__ == "__main__":
    main()

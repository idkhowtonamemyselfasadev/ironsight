#!/usr/bin/env python3
"""Real Fabric 1.21.11 client for the FPS / real-client test.

Mods: Ironsight + Fabric API + Sodium + Lithium + FerriteCore (the set the brief names).
-Xmx3G, render distance 8, sound muted, perf probe on. Runs on the hidden weston display
`wl-mc` (never on Tim's screens) and joins the given server.

    python3 test/client/run-client.py 192.168.2.145:25681 [seconds]

Prints the PID, writes client.pid, and kills the client after <seconds> (default 300).
Results: game/logs/latest.log ("[Ironsight] PERF ..." lines)."""
import glob, json, os, shutil, subprocess, sys, time
sys.path.insert(0, os.path.expanduser("~/minecraft-launcher"))
import mcengine

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
game = os.path.join(HERE, "game")
mods = os.path.join(game, "mods")
shutil.rmtree(mods, ignore_errors=True)
os.makedirs(mods)
for pattern in ["build/libs/ironsight-*.jar", "testserver/mods/fabric-api-*.jar", "testmods/*.jar"]:
    for f in glob.glob(os.path.join(ROOT, pattern)):
        shutil.copy(f, mods)
with open(os.path.join(game, "options.txt"), "w") as f:
    f.write("overrideWidth:1920\noverrideHeight:1080\nrenderDistance:8\nsimulationDistance:8\nmaxFps:260\n"
            "enableVsync:false\nsoundCategory_master:0.0\nnarrator:0\nonboardAccessibility:false\n"
            "tutorialStep:none\nskipMultiplayerWarning:true\njoinedFirstServer:true\npauseOnLostFocus:false\n")

fid, _ = mcengine.install_fabric("1.21.11", "0.18.6", progress=False)
prof = {"name": "ironsight-fps", "launch_version": fid, "mc_version": "1.21.11", "dir": game, "ram": 3}
account = {"username": "FpsTester", "uuid": "1234567812345678123456781234abcd", "access_token": "0"}
cmd = mcengine.build_launch(prof, account, 3)
cmd.insert(1, "-Dironsight.perf=true")
cmd += ["--quickPlayMultiplayer", sys.argv[1]]

sock = os.path.join(os.environ["XDG_RUNTIME_DIR"], "wl-mc")
if not os.path.exists(sock):
    subprocess.Popen(["weston", "--backend=headless", "--xwayland", "--socket=wl-mc", "--width=1920",
                      "--height=1080", "--idle-time=0"], stdout=open(os.path.join(HERE, "weston.log"), "w"),
                     stderr=subprocess.STDOUT)
    time.sleep(4)
env = dict(os.environ, DISPLAY=":1", WAYLAND_DISPLAY="wl-mc", SDL_VIDEODRIVER="x11")
os.chdir(game)
p = subprocess.Popen(cmd, env=env, stdout=open(os.path.join(HERE, "client-stdout.log"), "w"), stderr=subprocess.STDOUT)
open(os.path.join(HERE, "client.pid"), "w").write(str(p.pid))
print(p.pid, flush=True)
try:
    p.wait(timeout=int(sys.argv[2]) if len(sys.argv) > 2 else 300)
except subprocess.TimeoutExpired:
    p.terminate()
    try:
        p.wait(timeout=20)
    except subprocess.TimeoutExpired:
        p.kill()
print("exit", p.returncode)

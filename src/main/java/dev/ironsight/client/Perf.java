package dev.ironsight.client;

import dev.ironsight.Ironsight;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

/**
 * Opt-in frame-cost probe: start the client with -Dironsight.perf=true and every 10 s the
 * log gets one line with the FPS, the mod's own HUD and world-effect cost per frame, and
 * the heap. Off by default; when off it is a single boolean check.
 */
@Environment(EnvType.CLIENT)
public final class Perf {
    public static final boolean ON = Boolean.getBoolean("ironsight.perf");
    public static long hudNanos, fxNanos, frames;
    private static long minFps = Long.MAX_VALUE, sumFps, samples;

    private Perf() {
    }

    public static void tick(Minecraft mc) {
        if (!ON) return;
        int fps = mc.getFps();
        if (ClientState.clientTick > 100 && fps > 0) {
            minFps = Math.min(minFps, fps);
            sumFps += fps;
            samples++;
        }
        if (ClientState.clientTick % 200 == 0 && frames > 0) {
            Runtime rt = Runtime.getRuntime();
            Ironsight.LOG.info("[Ironsight] PERF fps={} avgFps={} minFps={} hudUs={} fxUs={} heapMB={}/{} inMatch={} players={}",
                    fps, samples == 0 ? 0 : sumFps / samples, minFps == Long.MAX_VALUE ? 0 : minFps,
                    hudNanos / frames / 1000.0, fxNanos / frames / 1000.0,
                    (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20,
                    ClientState.inMatch(), mc.level == null ? 0 : mc.level.players().size());
            hudNanos = fxNanos = frames = 0;
        }
    }
}

package dev.ironsight.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ironsight.Ironsight;
import dev.ironsight.client.ClientState;
import dev.ironsight.config.GunConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The only world-space effects the mod has, all deliberately cheap:
 * <ul>
 * <li>tracers: one line per shot (max 4 for a shotgun) from a fixed pool of 64, fading
 *     out over 3 ticks - no entities, no particles, no allocation per frame;</li>
 * <li>muzzle flash: exactly two vanilla particles per shot (flash + smoke);</li>
 * <li>scope glint: one camera-facing quad on each enemy sniper that is scoped in and
 *     looking your way, drawn with depth testing so walls hide it.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class Effects {

    private static final int POOL = 64;
    private static final float LIFE_TICKS = 3f;
    private static final float[] X1 = new float[POOL], Y1 = new float[POOL], Z1 = new float[POOL];
    private static final float[] X2 = new float[POOL], Y2 = new float[POOL], Z2 = new float[POOL];
    private static final int[] COLOUR = new int[POOL];
    private static final long[] BORN = new long[POOL];
    private static int next;

    private static final Identifier GLINT = Ironsight.id("textures/gui/glint.png");
    private static final SoundEvent[] SHOTS = new SoundEvent[GunConfig.IDS.size()];
    private static final java.util.Map<String, SoundEvent> OTHER = new java.util.HashMap<>();

    static {
        for (int i = 0; i < SHOTS.length; i++) {
            SHOTS[i] = SoundEvent.createVariableRangeEvent(Ironsight.id("shot." + GunConfig.IDS.get(i)));
        }
        for (int i = 0; i < POOL; i++) BORN[i] = -100;
    }

    private Effects() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(Effects::render);
    }

    public static void addTracer(Vec3 from, Vec3 to, int rgb) {
        int i = next;
        next = (next + 1) % POOL;
        X1[i] = (float) from.x;
        Y1[i] = (float) from.y;
        Z1[i] = (float) from.z;
        X2[i] = (float) to.x;
        Y2[i] = (float) to.y;
        Z2[i] = (float) to.z;
        COLOUR[i] = rgb & 0xFFFFFF;
        BORN[i] = ClientState.clientTick;
    }

    public static void muzzleFlash(Level level, Vec3 at) {
        level.addParticle(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFD27A), at.x, at.y, at.z, 0, 0, 0);
        level.addParticle(ParticleTypes.SMOKE, at.x, at.y, at.z, 0, 0.01, 0);
    }

    public static void playShot(double x, double y, double z, int gun, boolean own) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || gun < 0 || gun >= SHOTS.length) return;
        float pitch = 0.95f + (float) Math.random() * 0.1f;
        mc.level.playLocalSound(x, y, z, SHOTS[gun], own ? SoundSource.PLAYERS : SoundSource.HOSTILE,
                own ? 0.9f : 2.5f, pitch, !own);
    }

    public static void playLocal(Player p, String name, float volume) {
        SoundEvent e = OTHER.computeIfAbsent(name, n -> SoundEvent.createVariableRangeEvent(Ironsight.id(n)));
        Minecraft.getInstance().getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(e, 1f, volume));
    }

    private static void render(WorldRenderContext ctx) {
        if (!dev.ironsight.client.Perf.ON) {
            draw(ctx);
            return;
        }
        long t = System.nanoTime();
        draw(ctx);
        dev.ironsight.client.Perf.fxNanos += System.nanoTime() - t;
    }

    private static void draw(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float now = ClientState.clientTick + partial;
        PoseStack pose = ctx.matrices();

        boolean anyTracer = false;
        for (int i = 0; i < POOL; i++) {
            if (now - BORN[i] < LIFE_TICKS) {
                anyTracer = true;
                break;
            }
        }
        if (anyTracer) {
            VertexConsumer lines = ctx.consumers().getBuffer(RenderTypes.lines());
            PoseStack.Pose last = pose.last();
            for (int i = 0; i < POOL; i++) {
                float age = now - BORN[i];
                if (age >= LIFE_TICKS || age < 0) continue;
                int alpha = (int) (255 * (1f - age / LIFE_TICKS));
                int argb = (alpha << 24) | COLOUR[i];
                float x1 = (float) (X1[i] - cam.x), y1 = (float) (Y1[i] - cam.y), z1 = (float) (Z1[i] - cam.z);
                float x2 = (float) (X2[i] - cam.x), y2 = (float) (Y2[i] - cam.y), z2 = (float) (Z2[i] - cam.z);
                float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len < 1e-4f) continue;
                nx /= len;
                ny /= len;
                nz /= len;
                lines.addVertex(last, x1, y1, z1).setColor(argb).setNormal(last, nx, ny, nz).setLineWidth(2.5f);
                lines.addVertex(last, x2, y2, z2).setColor(argb).setNormal(last, nx, ny, nz).setLineWidth(2.5f);
            }
        }

        if (!ClientState.glints.isEmpty()) {
            VertexConsumer quad = ctx.consumers().getBuffer(RenderTypes.eyes(GLINT));
            var it = ClientState.glints.iterator();
            while (it.hasNext()) {
                Entity e = mc.level.getEntity(it.nextInt());
                if (!(e instanceof Player sniper) || sniper.isSpectator()) continue;
                Vec3 eye = sniper.getEyePosition(partial);
                Vec3 look = sniper.getViewVector(partial);
                Vec3 toCam = cam.subtract(eye);
                double dist = toCam.length();
                if (dist < 1e-3) continue;
                // Only visible from in front of the scope, brightest when aimed straight at you.
                double facing = look.dot(toCam.scale(1 / dist));
                if (facing < 0.6) continue;
                float strength = (float) Math.min(1, (facing - 0.6) / 0.35);
                float size = (float) (0.25 + dist * 0.012) * (0.6f + strength * 0.6f);
                Vec3 at = eye.add(look.scale(0.45));
                pose.pushPose();
                pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
                pose.mulPose(camera.rotation());
                Matrix4f m = pose.last().pose();
                int col = ((int) (255 * strength) << 24) | 0xFFFFFF;
                vertex(quad, pose.last(), m, -size, -size, 0, 1, col);
                vertex(quad, pose.last(), m, size, -size, 1, 1, col);
                vertex(quad, pose.last(), m, size, size, 1, 0, col);
                vertex(quad, pose.last(), m, -size, size, 0, 0, col);
                pose.popPose();
            }
        }
    }

    private static void vertex(VertexConsumer c, PoseStack.Pose pose, Matrix4f m, float x, float y, float u, float v, int argb) {
        c.addVertex(m, x, y, 0).setColor(argb).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(0xF000F0).setNormal(pose, 0, 0, 1);
    }

    /** A remote shot: flash, sound and up to four tracers. */
    public static void remoteShot(ClientLevel level, int gun, float sx, float sy, float sz, float[] ends) {
        Vec3 from = new Vec3(sx, sy, sz);
        int colour = gun >= 0 && gun < GunConfig.IDS.size() ? GunConfig.get(GunConfig.IDS.get(gun)).tracerColor : 0xFFE7A0;
        for (int i = 0; i + 2 < ends.length && i < 12; i += 3) {
            addTracer(from, new Vec3(ends[i], ends[i + 1], ends[i + 2]), colour);
        }
        muzzleFlash(level, from);
        playShot(sx, sy, sz, gun, false);
    }
}

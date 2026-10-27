package dev.ironsight.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ironsight.Ironsight;
import dev.ironsight.client.render.Effects;
import dev.ironsight.config.GunConfig;
import dev.ironsight.config.GunStats;
import dev.ironsight.guns.Guns;
import dev.ironsight.net.C2S;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.Random;

/**
 * Turns mouse and keys into shots. Left click = fire, right click = aim (ADS), R = reload.
 * The client predicts its own shot - fire rate, ammo, recoil, sound, muzzle flash, tracer -
 * so shooting feels instant, and tells the server only "trigger pulled, aiming here". The
 * server's verdict comes back as ammo and hit packets.
 */
@Environment(EnvType.CLIENT)
public final class GunInput {

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Ironsight.id("ironsight"));

    public static KeyMapping reload;
    public static KeyMapping playMenu;
    public static KeyMapping damageNumbers;

    private static String heldGun;
    private static long drawEnd;
    private static long nextFire;
    private static boolean wasTrigger;
    private static long pendingUntil = -1;
    private static int burstLeft;
    private static long burstNext;
    private static boolean adsSent;
    private static boolean emptyClicked;
    /** Bolt/pump sound scheduled after a sniper or shotgun shot. */
    private static long cycleSoundAt = -1;
    private static String cycleSound;
    /** 0..1, smoothed per frame for the FOV and the scope overlay. */
    public static float adsProgress;
    private static long lastFrameNanos;

    private static final Random RANDOM = new Random();

    private GunInput() {
    }

    public static void register() {
        reload = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ironsight.reload", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
        playMenu = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ironsight.play", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, CATEGORY));
        damageNumbers = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ironsight.damage_numbers", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
    }

    /** The gun in the main hand while in a match and alive, or null. */
    public static String activeGun() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || p.isSpectator() || !ClientState.inMatch()) return null;
        return Guns.idOf(p.getMainHandItem());
    }

    public static boolean blockVanillaClicks() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return false;
        return Guns.isGun(p.getMainHandItem()) || (ClientState.inMatch() && p.isSpectator());
    }

    public static void tick(Minecraft mc) {
        ClientState.clientTick++;
        long now = ClientState.clientTick;
        LocalPlayer p = mc.player;
        if (p == null) return;

        while (playMenu.consumeClick()) {
            if (!ClientState.inMatch()) ClientPlayNetworking.send(new C2S.Action(C2S.Action.OPEN_PLAY));
        }
        while (damageNumbers.consumeClick()) {
            ClientConfig.get().damageNumbers = !ClientConfig.get().damageNumbers;
            ClientConfig.save();
            p.displayClientMessage(net.minecraft.network.chat.Component.literal("Damage numbers "
                    + (ClientConfig.get().damageNumbers ? "on" : "off")), true);
        }

        boolean trigger = mc.options.keyAttack.isDown() && mc.screen == null;

        // Dead in a match: left click cycles who you watch.
        if (ClientState.inMatch() && p.isSpectator()) {
            if (trigger && !wasTrigger) ClientPlayNetworking.send(new C2S.Action(C2S.Action.SPECTATE_NEXT));
            wasTrigger = trigger;
            setAds(false);
            return;
        }

        if (cycleSoundAt >= 0 && now >= cycleSoundAt) {
            cycleSoundAt = -1;
            Effects.playLocal(p, cycleSound, 0.7f);
        }

        String gun = activeGun();
        if (gun == null || !gun.equals(heldGun)) {
            heldGun = gun;
            burstLeft = 0;
            if (gun != null) drawEnd = now + GunConfig.get(gun).drawTicks;
        }
        if (gun == null) {
            setAds(false);
            wasTrigger = trigger;
            return;
        }
        GunStats s = GunConfig.get(gun);
        int idx = GunConfig.index(gun);

        while (reload.consumeClick()) {
            ClientPlayNetworking.send(new C2S.Action(C2S.Action.RELOAD));
        }

        boolean reloading = ClientState.reloading(idx);
        setAds(mc.options.keyUse.isDown() && mc.screen == null && !(reloading && !s.shellReload));

        // Burst continuation (the server fires these on its own; we mirror the effects).
        if (burstLeft > 0 && now >= burstNext) {
            if (ClientState.AMMO_MAG[idx] > 0) predictShot(mc, p, s, idx, false);
            burstLeft--;
            burstNext = now + s.burstTicks;
        }

        boolean live = ClientState.phase() == ClientState.LIVE;
        boolean pressed = trigger && !wasTrigger;
        if (pressed) {
            emptyClicked = false;
            pendingUntil = now + 4; // a click a little before the gun is ready still counts
        }
        boolean wants = s.auto ? trigger : (pendingUntil >= now);
        int mag = ClientState.AMMO_MAG[idx];
        boolean canFire = live && now >= drawEnd && now >= nextFire && burstLeft == 0
                && (!reloading || (s.shellReload && mag > 0));
        if (wants && canFire) {
            if (mag > 0) {
                predictShot(mc, p, s, idx, true);
                ClientPlayNetworking.send(new C2S.Shoot(p.getYRot(), p.getXRot()));
                pendingUntil = -1;
                if (s.burst > 1) {
                    burstLeft = Math.min(s.burst - 1, ClientState.AMMO_MAG[idx]);
                    burstNext = now + s.burstTicks;
                    nextFire = now + s.ticksToShot(s.burst);
                } else {
                    nextFire = now + s.fireTicks;
                }
            } else if (mag == 0 && !emptyClicked) {
                emptyClicked = true;
                pendingUntil = -1;
                Effects.playLocal(p, "empty", 0.8f);
                ClientPlayNetworking.send(new C2S.Action(C2S.Action.RELOAD));
            }
        }
        wasTrigger = trigger;
    }

    private static void setAds(boolean on) {
        if (on != adsSent) {
            adsSent = on;
            if (ClientState.inMatch()) {
                ClientPlayNetworking.send(new C2S.Action(on ? C2S.Action.ADS_ON : C2S.Action.ADS_OFF));
            }
        }
    }

    public static boolean ads() {
        return adsSent;
    }

    private static void predictShot(Minecraft mc, LocalPlayer p, GunStats s, int idx, boolean first) {
        ClientState.AMMO_MAG[idx] = Math.max(0, ClientState.AMMO_MAG[idx] - 1);
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1f);
        Vec3 end = clientRay(mc, p, eye, look, s.range);
        // From the gun, not the eye: a bit right and down of the crosshair in first person.
        Vec3 right = new Vec3(-look.z, 0, look.x).normalize();
        Vec3 muzzle = eye.add(look.scale(0.5)).add(right.scale(0.18)).add(0, -0.12, 0);
        Effects.addTracer(muzzle, end, s.tracerColor);
        Effects.muzzleFlash(p.level(), muzzle);
        Effects.playShot(p.getX(), p.getEyeY(), p.getZ(), GunConfig.index(heldGun), true);

        if (s.fireTicks >= 14 && s.burst <= 1 && !s.auto && (s.pellets > 1 || s.scope)) {
            cycleSound = s.pellets > 1 ? "pump" : "bolt";
            cycleSoundAt = ClientState.clientTick + Math.min(8, s.fireTicks / 2);
        }
        float adsMul = adsSent ? 0.65f : 1f;
        p.setXRot(Mth.clamp(p.getXRot() - s.recoilPitch * adsMul, -90f, 90f));
        p.setYRot(p.getYRot() + (RANDOM.nextFloat() * 2 - 1) * s.recoilYaw * adsMul);
    }

    /** Where our own tracer ends: first block or player along the aim. Purely visual. */
    private static Vec3 clientRay(Minecraft mc, LocalPlayer p, Vec3 eye, Vec3 dir, double range) {
        Vec3 to = eye.add(dir.scale(range));
        var hit = p.level().clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 end = hit.getType() == HitResult.Type.MISS ? to : hit.getLocation();
        double best = end.distanceToSqr(eye);
        for (Player other : p.level().players()) {
            if (other == p || other.isSpectator()) continue;
            AABB box = other.getBoundingBox().inflate(0.05);
            Optional<Vec3> h = box.clip(eye, to);
            if (h.isPresent() && h.get().distanceToSqr(eye) < best) {
                best = h.get().distanceToSqr(eye);
                end = h.get();
            }
        }
        return end;
    }

    /** Per frame: smooth the aim-in progress for FOV and the scope overlay. */
    public static void frame() {
        long nowNanos = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0f : Math.min(0.1f, (nowNanos - lastFrameNanos) / 1e9f);
        lastFrameNanos = nowNanos;
        String gun = activeGun();
        float target = adsSent && gun != null ? 1f : 0f;
        float speed = 20f / Math.max(1, gun != null ? GunConfig.get(gun).adsTicks : 4);
        if (adsProgress < target) adsProgress = Math.min(target, adsProgress + dt * speed);
        else adsProgress = Math.max(target, adsProgress - dt * speed * 1.5f);
    }

    public static float zoom() {
        String gun = activeGun();
        if (gun == null || adsProgress <= 0) return 1f;
        float z = GunConfig.get(gun).adsZoom;
        return 1f + (z - 1f) * adsProgress;
    }
}

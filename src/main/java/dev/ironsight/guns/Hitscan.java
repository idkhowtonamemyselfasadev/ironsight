package dev.ironsight.guns;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * One ray against the world and a short list of targets. A shot is: one block clip
 * (vanilla, stops at the first collider) plus two AABB tests per living opponent - at most
 * 11 opponents, so this is a few microseconds. Heads are tested first; the nearest hit wins.
 */
public final class Hitscan {

    public static final double HALF_WIDTH = 0.3;
    /** How much of the top of the hitbox counts as head. */
    public static final double HEAD = 0.5;

    public record Target(Object key, double x, double y, double z, double height) { }

    public record Result(Vec3 end, Object target, boolean head, double distance) { }

    private Hitscan() {
    }

    public static Result cast(Level level, Vec3 from, Vec3 dir, double range, List<Target> targets, Object shooterEntityHint) {
        Vec3 to = from.add(dir.x * range, dir.y * range, dir.z * range);
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        double best = block.getType() == HitResult.Type.MISS ? range : block.getLocation().distanceTo(from);
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        Object hit = null;
        boolean head = false;

        for (Target t : targets) {
            double top = t.y + t.height;
            AABB headBox = new AABB(t.x - HALF_WIDTH, top - HEAD, t.z - HALF_WIDTH, t.x + HALF_WIDTH, top, t.z + HALF_WIDTH);
            AABB bodyBox = new AABB(t.x - HALF_WIDTH, t.y, t.z - HALF_WIDTH, t.x + HALF_WIDTH, top - HEAD, t.z + HALF_WIDTH);
            Optional<Vec3> h = headBox.clip(from, to);
            if (h.isPresent()) {
                double d = h.get().distanceTo(from);
                if (d < best) {
                    best = d;
                    end = h.get();
                    hit = t.key;
                    head = true;
                }
            }
            Optional<Vec3> b = bodyBox.clip(from, to);
            if (b.isPresent()) {
                double d = b.get().distanceTo(from);
                if (d < best) {
                    best = d;
                    end = b.get();
                    hit = t.key;
                    head = false;
                }
            }
        }
        return new Result(end, hit, head, best);
    }

    /** A direction from yaw/pitch (Minecraft degrees) with a random offset inside a cone of half-angle spreadDeg. */
    public static Vec3 direction(float yaw, float pitch, double spreadDeg, net.minecraft.util.RandomSource random) {
        Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
        if (spreadDeg <= 0) return forward;
        // Uniform over the disc of the cone's cross-section.
        double r = Math.tan(Math.toRadians(spreadDeg)) * Math.sqrt(random.nextDouble());
        double a = random.nextDouble() * Math.PI * 2;
        Vec3 up = Math.abs(forward.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = forward.cross(up).normalize();
        Vec3 realUp = right.cross(forward).normalize();
        return forward.add(right.scale(Math.cos(a) * r)).add(realUp.scale(Math.sin(a) * r)).normalize();
    }
}

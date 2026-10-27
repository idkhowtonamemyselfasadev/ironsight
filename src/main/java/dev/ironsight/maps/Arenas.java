package dev.ironsight.maps;

import dev.ironsight.Ironsight;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

/**
 * Arena instances in the void dimension ironsight:arena. Each running match borrows a
 * slot - a 512-block-wide strip of the dimension - so any number of matches run side by
 * side and never see each other. The lobby (overworld) is never touched by a match.
 */
public final class Arenas {

    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Ironsight.id("arena"));
    public static final int SLOT_SPACING = 512;
    public static final int MAX_SLOTS = 32;
    public static final int BASE_Y = 64;

    private static final boolean[] USED = new boolean[MAX_SLOTS];

    private Arenas() {
    }

    public static ServerLevel level(MinecraftServer server) {
        return server.getLevel(DIMENSION);
    }

    public static int claimSlot() {
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (!USED[i]) {
                USED[i] = true;
                return i;
            }
        }
        return -1;
    }

    public static void releaseSlot(int slot) {
        if (slot >= 0 && slot < MAX_SLOTS) USED[slot] = false;
    }

    public static BlockPos origin(int slot) {
        return new BlockPos(slot * SLOT_SPACING, BASE_Y, 0);
    }

    /**
     * Place (and thereby reset) a map at a slot. The template stores every air block too,
     * so this overwrites anything left from the last match. Block updates are suppressed
     * (flag 2|16): stairs and fences keep the shapes they were saved with.
     */
    public static long place(ServerLevel level, ArenaMap map, BlockPos origin) {
        long start = System.nanoTime();
        forceChunks(level, map, origin, true);
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true).setKnownShape(true);
        map.template.placeInWorld(level, origin, origin, settings, RandomSource.create(1),
                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        return (System.nanoTime() - start) / 1_000_000L;
    }

    public static void forceChunks(ServerLevel level, ArenaMap map, BlockPos origin, boolean on) {
        int x0 = origin.getX() >> 4, z0 = origin.getZ() >> 4;
        int x1 = (origin.getX() + map.sizeX) >> 4, z1 = (origin.getZ() + map.sizeZ) >> 4;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    public static boolean inside(ArenaMap map, BlockPos origin, double x, double z) {
        return x >= origin.getX() - 2 && z >= origin.getZ() - 2
                && x <= origin.getX() + map.sizeX + 2 && z <= origin.getZ() + map.sizeZ + 2;
    }

    public static ChunkPos centerChunk(ArenaMap map, BlockPos origin) {
        return new ChunkPos((origin.getX() + map.sizeX / 2) >> 4, (origin.getZ() + map.sizeZ / 2) >> 4);
    }
}

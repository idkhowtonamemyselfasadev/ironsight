package dev.ironsight.modes;

import dev.ironsight.guns.GunState;
import dev.ironsight.guns.PositionHistory;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** One player's part in one match. Survives a disconnect so the scoreboard stays whole. */
public final class MatchPlayer {
    public final UUID id;
    public final String name;
    public ServerPlayer player;
    public boolean connected = true;

    /** 0 = A, 1 = B, -1 = none (FFA, or not picked yet). */
    public int team = -1;
    public boolean alive;
    public int lives;
    public int kills;
    public int deaths;
    public float damage;

    public String primary;
    public String secondary;
    /** [0] = primary (hotbar slot 1), [1] = secondary (hotbar slot 2). */
    public final GunState[] guns = new GunState[2];
    public int heldSlot = -1;

    public long protectedUntil;
    public UUID lastAttacker;
    public String lastAttackerGun;
    public long lastAttackTick;

    public boolean ads;
    public long adsSince;
    public boolean glintSent;
    /** FFA: tick to respawn at, or 0. */
    public long respawnAt;
    public UUID spectating;

    /** Cached so the speed modifier is only touched when it actually changes. */
    public double appliedSpeedMult = 1.0;
    public boolean frozenApplied;

    public final PositionHistory history = new PositionHistory();

    public MatchPlayer(ServerPlayer player) {
        this.id = player.getUUID();
        this.name = player.getGameProfile().name();
        this.player = player;
    }

    public GunState held() {
        return heldSlot >= 0 && heldSlot < 2 ? guns[heldSlot] : null;
    }
}

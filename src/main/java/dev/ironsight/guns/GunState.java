package dev.ironsight.guns;

import dev.ironsight.config.GunConfig;
import dev.ironsight.config.GunStats;

/** Server-side state of one gun in a player's loadout. */
public final class GunState {
    public final String gun;
    public int mag;
    public int reserve;
    /** Tick at which the running reload step finishes, or 0. */
    public long reloadDoneAt;
    public long nextFireAt;
    public long drawDoneAt;
    public float bloom;
    public int burstLeft;
    public long burstNextAt;
    public boolean dirty = true;

    public GunState(String gun) {
        this.gun = gun;
        refill();
    }

    public GunStats stats() {
        return GunConfig.get(gun);
    }

    public void refill() {
        GunStats s = stats();
        mag = s.mag;
        reserve = s.reserve;
        reloadDoneAt = 0;
        burstLeft = 0;
        bloom = 0;
        dirty = true;
    }

    public boolean reloading() {
        return reloadDoneAt > 0;
    }
}

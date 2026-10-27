package dev.ironsight.net;

import dev.ironsight.Ironsight;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client. Everything here is sent on change only: a match where nothing happens
 * sends nothing but vanilla movement.
 */
public final class S2C {

    private S2C() {
    }

    /** Configuration phase: proves the client has the mod, and carries the server's mod version. */
    public record Hello(String version) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(Ironsight.id("hello"));
        public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.version, 32),
                buf -> new Hello(buf.readUtf(32)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The server's guns.json, sent on join and after /gun reload. */
    public record GunTable(String json) implements CustomPacketPayload {
        public static final Type<GunTable> TYPE = new Type<>(Ironsight.id("guns"));
        public static final StreamCodec<FriendlyByteBuf, GunTable> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.json, 262_144),
                buf -> new GunTable(buf.readUtf(262_144)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Match state for the HUD. mode/phase are ordinals; team: 0 = A, 1 = B, -1 = none.
     * ticksLeft counts down locally on the client, so it is only resent on phase changes.
     */
    public record Hud(byte mode, byte phase, int ticksLeft, byte round, byte scoreA, byte scoreB, byte team,
                      byte aliveA, byte aliveB, byte lives, byte playersLeft, byte toWin, String map)
            implements CustomPacketPayload {
        public static final Type<Hud> TYPE = new Type<>(Ironsight.id("hud"));
        public static final StreamCodec<FriendlyByteBuf, Hud> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeByte(p.mode);
                    buf.writeByte(p.phase);
                    buf.writeVarInt(p.ticksLeft);
                    buf.writeByte(p.round);
                    buf.writeByte(p.scoreA);
                    buf.writeByte(p.scoreB);
                    buf.writeByte(p.team);
                    buf.writeByte(p.aliveA);
                    buf.writeByte(p.aliveB);
                    buf.writeByte(p.lives);
                    buf.writeByte(p.playersLeft);
                    buf.writeByte(p.toWin);
                    buf.writeUtf(p.map, 64);
                },
                buf -> new Hud(buf.readByte(), buf.readByte(), buf.readVarInt(), buf.readByte(), buf.readByte(),
                        buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte(),
                        buf.readByte(), buf.readUtf(64)));

        /** Sent when a player leaves a match: the HUD goes away. */
        public static Hud none() {
            return new Hud((byte) -1, (byte) 0, 0, (byte) 0, (byte) 0, (byte) 0, (byte) -1, (byte) 0, (byte) 0,
                    (byte) 0, (byte) 0, (byte) 0, "");
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Ammo of one loadout gun. reloadLeft/reloadTotal are 0 when not reloading. */
    public record Ammo(byte gun, short mag, short reserve, short reloadLeft, short reloadTotal)
            implements CustomPacketPayload {
        public static final Type<Ammo> TYPE = new Type<>(Ironsight.id("ammo"));
        public static final StreamCodec<FriendlyByteBuf, Ammo> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeByte(p.gun);
                    buf.writeShort(p.mag);
                    buf.writeShort(p.reserve);
                    buf.writeShort(p.reloadLeft);
                    buf.writeShort(p.reloadTotal);
                },
                buf -> new Ammo(buf.readByte(), buf.readShort(), buf.readShort(), buf.readShort(), buf.readShort()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Someone else fired: where from, and where each ray (max 4 drawn) ended. The shooter
     * never receives their own shot - their client already predicted it.
     */
    public record Shot(int entity, byte gun, float sx, float sy, float sz, float[] ends) implements CustomPacketPayload {
        public static final Type<Shot> TYPE = new Type<>(Ironsight.id("shot"));
        public static final StreamCodec<FriendlyByteBuf, Shot> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.entity);
                    buf.writeByte(p.gun);
                    buf.writeFloat(p.sx);
                    buf.writeFloat(p.sy);
                    buf.writeFloat(p.sz);
                    int n = p.ends.length / 3;
                    buf.writeByte(n);
                    for (int i = 0; i < n * 3; i++) buf.writeFloat(p.ends[i]);
                },
                buf -> {
                    int e = buf.readVarInt();
                    byte g = buf.readByte();
                    float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
                    int n = Math.min(buf.readByte(), 8);
                    float[] ends = new float[n * 3];
                    for (int i = 0; i < ends.length; i++) ends[i] = buf.readFloat();
                    return new Shot(e, g, x, y, z, ends);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Your shot hit. flags: 1 = headshot, 2 = kill. */
    public record Hit(float damage, byte flags) implements CustomPacketPayload {
        public static final Type<Hit> TYPE = new Type<>(Ironsight.id("hit"));
        public static final StreamCodec<FriendlyByteBuf, Hit> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeFloat(p.damage);
                    buf.writeByte(p.flags);
                },
                buf -> new Hit(buf.readFloat(), buf.readByte()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A kill feed line. Teams: 0 = A, 1 = B, -1 = FFA. gun -1 = environment (fall, void, left). */
    public record Kill(String killer, String victim, byte gun, boolean headshot, byte killerTeam, byte victimTeam)
            implements CustomPacketPayload {
        public static final Type<Kill> TYPE = new Type<>(Ironsight.id("kill"));
        public static final StreamCodec<FriendlyByteBuf, Kill> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.killer, 32);
                    buf.writeUtf(p.victim, 32);
                    buf.writeByte(p.gun);
                    buf.writeBoolean(p.headshot);
                    buf.writeByte(p.killerTeam);
                    buf.writeByte(p.victimTeam);
                },
                buf -> new Kill(buf.readUtf(32), buf.readUtf(32), buf.readByte(), buf.readBoolean(), buf.readByte(),
                        buf.readByte()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Open (or close) a menu. kind PLAY: data = "duel:1/2,team2:0/4,team4:0/8,ffa:3/12" plus
     * ";queued=ffa" when you are in one. TEAM: "a,b,max". LOADOUT: "primary,secondary" (your
     * current picks). VOTE: "id=Name;id=Name". seconds = how long the menu stays relevant.
     */
    public record Menu(byte kind, String data, int seconds) implements CustomPacketPayload {
        public static final byte CLOSE = 0;
        public static final byte PLAY = 1;
        public static final byte TEAM = 2;
        public static final byte LOADOUT = 3;
        public static final byte VOTE = 4;

        public static final Type<Menu> TYPE = new Type<>(Ironsight.id("open_menu"));
        public static final StreamCodec<FriendlyByteBuf, Menu> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeByte(p.kind);
                    buf.writeUtf(p.data, 4096);
                    buf.writeVarInt(p.seconds);
                },
                buf -> new Menu(buf.readByte(), buf.readUtf(4096), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Scoreboard rows: "name|team|kills|deaths|damage|alive|lives" joined by ';'. */
    public record Board(String rows) implements CustomPacketPayload {
        public static final Type<Board> TYPE = new Type<>(Ironsight.id("board"));
        public static final StreamCodec<FriendlyByteBuf, Board> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUtf(p.rows, 8192),
                buf -> new Board(buf.readUtf(8192)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** An enemy sniper scoped in (on) or out (off). */
    public record Glint(int entity, boolean on) implements CustomPacketPayload {
        public static final Type<Glint> TYPE = new Type<>(Ironsight.id("glint"));
        public static final StreamCodec<FriendlyByteBuf, Glint> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.entity);
                    buf.writeBoolean(p.on);
                },
                buf -> new Glint(buf.readVarInt(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}

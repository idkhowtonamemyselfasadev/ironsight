package dev.ironsight.net;

import dev.ironsight.Ironsight;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server. Three tiny messages: a shot (8 bytes), an action (1 byte) and a menu
 * choice. The client only ever states intent; the server checks everything.
 */
public final class C2S {

    private C2S() {
    }

    /** One trigger pull, with the aim the client had at that instant. */
    public record Shoot(float yaw, float pitch) implements CustomPacketPayload {
        public static final Type<Shoot> TYPE = new Type<>(Ironsight.id("shoot"));
        public static final StreamCodec<FriendlyByteBuf, Shoot> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeFloat(p.yaw);
                    buf.writeFloat(p.pitch);
                },
                buf -> new Shoot(buf.readFloat(), buf.readFloat()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Action(byte action) implements CustomPacketPayload {
        public static final byte RELOAD = 0;
        public static final byte ADS_ON = 1;
        public static final byte ADS_OFF = 2;
        public static final byte SPECTATE_NEXT = 3;
        public static final byte OPEN_PLAY = 4;
        public static final byte LEAVE = 5;

        public static final Type<Action> TYPE = new Type<>(Ironsight.id("action"));
        public static final StreamCodec<FriendlyByteBuf, Action> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeByte(p.action),
                buf -> new Action(buf.readByte()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A menu pick. kind QUEUE: value = mode id (duel/team2/team4/ffa). TEAM: "A" or "B".
     * LOADOUT: "primary,secondary". VOTE: map id.
     */
    public record Menu(byte kind, String value) implements CustomPacketPayload {
        public static final byte QUEUE = 0;
        public static final byte TEAM = 1;
        public static final byte LOADOUT = 2;
        public static final byte VOTE = 3;

        public static final Type<Menu> TYPE = new Type<>(Ironsight.id("menu"));
        public static final StreamCodec<FriendlyByteBuf, Menu> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeByte(p.kind);
                    buf.writeUtf(p.value, 64);
                },
                buf -> new Menu(buf.readByte(), buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}

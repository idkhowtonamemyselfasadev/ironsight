package dev.ironsight.mixin;

import dev.ironsight.modes.MatchManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Q does nothing in a match or the lobby: guns and the Play compass can't be thrown away. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
    private void ironsight$noDrop(boolean all, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (!self.isCreative() || MatchManager.matchOf(self) != null) {
            ci.cancel();
            // The client already removed the item from its slot; put it back on screen.
            self.inventoryMenu.sendAllDataToRemote();
        }
    }
}

package dev.ironsight.mixin.client;

import dev.ironsight.client.GunInput;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * With a gun in hand, left and right click belong to the gun: no swing, no block
 * breaking, no item use. Dead players in a match don't get vanilla spectator clicks either.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void ironsight$attack(CallbackInfoReturnable<Boolean> cir) {
        if (GunInput.blockVanillaClicks()) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void ironsight$continueAttack(boolean down, CallbackInfo ci) {
        if (GunInput.blockVanillaClicks()) ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void ironsight$use(CallbackInfo ci) {
        if (GunInput.blockVanillaClicks()) ci.cancel();
    }
}

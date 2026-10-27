package dev.ironsight.mixin.client;

import dev.ironsight.client.ClientState;
import dev.ironsight.client.GunInput;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FOV in a match: aim-down-sights zoom, and none of vanilla's speed-based FOV. Without
 * this the LMG's slow walk and the freeze would visibly squeeze the screen.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {

    @Inject(method = "getFieldOfViewModifier", at = @At("HEAD"), cancellable = true)
    private void ironsight$fov(boolean firstPerson, float effectScale, CallbackInfoReturnable<Float> cir) {
        if (!ClientState.inMatch()) return;
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
        float g = self.isSprinting() ? 1.08f : 1f;
        cir.setReturnValue(Mth.lerp(effectScale, 1f, g) * GunInput.zoom());
    }
}

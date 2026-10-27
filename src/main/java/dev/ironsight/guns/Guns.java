package dev.ironsight.guns;

import dev.ironsight.Ironsight;
import dev.ironsight.config.GunConfig;
import dev.ironsight.config.GunStats;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Guns are plain vanilla items (a stick) carrying an {@code item_model} component that
 * points at ironsight:&lt;gun&gt;. Nothing is added to the item registry, so registry sync
 * never gets involved, and the model id doubles as the gun's identity: reading it is a
 * component lookup with no allocation, cheap enough to do every tick on both sides.
 */
public final class Guns {

    private Guns() {
    }

    public static ItemStack create(String id) {
        GunStats s = GunConfig.get(id);
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(DataComponents.ITEM_MODEL, Ironsight.id(id));
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(s == null ? id : s.name)
                .withStyle(style -> style.withItalic(false).withColor(ChatFormatting.WHITE)));
        return stack;
    }

    /** The gun id of a stack, or null if it is not one of ours. */
    public static String idOf(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        if (model == null || !Ironsight.MOD_ID.equals(model.getNamespace())) return null;
        String path = model.getPath();
        return GunConfig.get(path) != null ? path : null;
    }

    public static boolean isGun(ItemStack stack) {
        return idOf(stack) != null;
    }
}

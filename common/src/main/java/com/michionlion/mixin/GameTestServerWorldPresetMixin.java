package com.michionlion.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameTestServer.class)
public abstract class GameTestServerWorldPresetMixin {
    private static final long KINGDOM_GAMETEST_SEED = Long.getLong("kingdom.gametest.seed", 1357913579L);

    @Shadow
    @Final
    @Mutable
    private static WorldOptions WORLD_OPTIONS;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/HolderLookup$RegistryLookup;getOrThrow(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;"
        )
    )
    private static Holder.Reference kingdom$useNormalPresetForGameTests(
        HolderLookup.RegistryLookup lookup,
        ResourceKey key
    ) {
        if (WorldPresets.FLAT.equals(key)) {
            HolderLookup.RegistryLookup<WorldPreset> presetLookup = (HolderLookup.RegistryLookup<WorldPreset>) lookup;
            return presetLookup.getOrThrow(WorldPresets.NORMAL);
        }
        return lookup.getOrThrow(key);
    }

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void kingdom$overrideGameTestWorldSeed(CallbackInfo callbackInfo) {
        WORLD_OPTIONS = new WorldOptions(KINGDOM_GAMETEST_SEED, false, false);
    }
}

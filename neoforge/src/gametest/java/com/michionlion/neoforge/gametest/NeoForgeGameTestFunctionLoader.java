package com.michionlion.neoforge.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunctionLoader;
import net.minecraft.resources.ResourceKey;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class NeoForgeGameTestFunctionLoader extends TestFunctionLoader {
    @Override
    public void load(BiConsumer<ResourceKey<Consumer<GameTestHelper>>, Consumer<GameTestHelper>> sink) {
        sink.accept(NeoForgeWorldBootAndTraversalTests.TEST_FUNCTION_KEY, NeoForgeWorldBootAndTraversalTests::worldBootAndHybridConcentricTraversal);
    }
}

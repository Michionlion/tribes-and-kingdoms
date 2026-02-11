package com.michionlion.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.state.CivWorldState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.Consumer;

public final class NeoForgeWorldBootAndTraversalTests {
    private static final int COMMAND_TEST_SETUP_TICKS = 20;
    private static final int COMMAND_TEST_MAX_TICKS = 200;

    private static final Identifier COMMAND_TEST_ID = Identifier.parse("kingdom:kcmd_export_snapshot");
    private static final Identifier TEST_STRUCTURE_ID = Identifier.parse("minecraft:empty");
    private static final Identifier TEST_ENVIRONMENT_ID = Identifier.parse("kingdom:open_world");
    private static final String COMMAND_SNAPSHOT_FILENAME = "kingdom-command-export-region-0-0.geojson";
    private static final String COMMAND_SNAPSHOT_RESOURCE = "kingdom/gametest/snapshots/" + COMMAND_SNAPSHOT_FILENAME;

    private NeoForgeWorldBootAndTraversalTests() {
    }

    public static void registerGameTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition> environment = event.registerEnvironment(
            TEST_ENVIRONMENT_ID,
            new TestEnvironmentDefinition.AllOf(List.of())
        );

        TestData<Holder<TestEnvironmentDefinition>> commandTestData = new TestData<>(
            environment,
            TEST_STRUCTURE_ID,
            COMMAND_TEST_MAX_TICKS,
            COMMAND_TEST_SETUP_TICKS,
            true,
            Rotation.NONE,
            false,
            1,
            1,
            true
        );

        event.registerTest(
            COMMAND_TEST_ID,
            new DirectGameTestInstance(commandTestData, NeoForgeWorldBootAndTraversalTests::kingdomCommandsGenerateAndExportSnapshot)
        );
    }

    private static void kingdomCommandsGenerateAndExportSnapshot(GameTestHelper helper) {
        if (helper.getLevel() == null) {
            helper.fail("Server level is null during command test startup.");
            return;
        }

        if (runKingdomCommandRegression(helper, helper.getLevel())) {
            helper.succeed();
        }
    }

    private static boolean runKingdomCommandRegression(GameTestHelper helper, ServerLevel level) {
        try {
            Path outputPath = Path.of("debug", "kingdom", COMMAND_SNAPSHOT_FILENAME);
            Files.createDirectories(outputPath.getParent());
            Files.deleteIfExists(outputPath);

            CommandSourceStack source = level.getServer()
                .createCommandSourceStack()
                .withLevel(level)
                .withPosition(new Vec3(0.5D, 90.0D, 0.5D))
                .withMaximumPermission(PermissionSet.ALL_PERMISSIONS)
                .withSuppressedOutput();

            runCommand(level, source, "kingdom config show");
            runCommand(level, source, "kingdom config reload");
            runCommand(level, source, "kingdom generate region 0 0 true");
            runCommand(level, source, "kingdom generate around 0 false");
            runCommand(level, source, "kingdom summary 0");
            runCommand(level, source, "kingdom visualize anchors 512");
            runCommand(level, source, "kingdom export geojson 0 true kingdom-command-export-region-0-0");

            if (!CivWorldState.get(level).isRegionPlanned(new RegionKey(0, 0))) {
                helper.fail("Expected planned region 0,0 after command execution.");
                return false;
            }

            if (!Files.isRegularFile(outputPath)) {
                helper.fail("Expected exported geojson at " + outputPath.toAbsolutePath());
                return false;
            }

            String expected = readResourceOrNull(COMMAND_SNAPSHOT_RESOURCE);
            if (expected == null) {
                helper.fail("Missing required snapshot resource: " + COMMAND_SNAPSHOT_RESOURCE);
                return false;
            }

            String actual = normalize(Files.readString(outputPath, StandardCharsets.UTF_8));
            String normalizedExpected = normalize(expected);
            if (!normalizedExpected.equals(actual)) {
                helper.fail(
                    "GeoJSON snapshot mismatch for /kingdom export."
                        + " expected_sha256=" + sha256(normalizedExpected)
                        + " actual_sha256=" + sha256(actual)
                        + " output=" + outputPath.toAbsolutePath()
                );
                return false;
            }

            return true;
        } catch (Exception error) {
            helper.fail("Command regression test failed: " + error.getMessage());
            return false;
        }
    }

    private static void runCommand(ServerLevel level, CommandSourceStack source, String command) {
        level.getServer().getCommands().performPrefixedCommand(source, command);
    }

    private static String normalize(String content) {
        return content.replace("\r\n", "\n").trim();
    }

    private static String readResourceOrNull(String resourcePath) throws Exception {
        try (InputStream stream = NeoForgeWorldBootAndTraversalTests.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            builder.append(String.format(java.util.Locale.ROOT, "%02x", b));
        }
        return builder.toString();
    }

    private static final class DirectGameTestInstance extends GameTestInstance {
        private final Consumer<GameTestHelper> runner;
        private final MapCodec<DirectGameTestInstance> codec;

        private DirectGameTestInstance(TestData<Holder<TestEnvironmentDefinition>> testData, Consumer<GameTestHelper> runner) {
            super(testData);
            this.runner = runner;
            this.codec = MapCodec.unit(this);
        }

        @Override
        public void run(GameTestHelper helper) {
            runner.accept(helper);
        }

        @Override
        public MapCodec<? extends GameTestInstance> codec() {
            return codec;
        }

        @Override
        protected MutableComponent typeDescription() {
            return Component.literal("direct");
        }
    }
}

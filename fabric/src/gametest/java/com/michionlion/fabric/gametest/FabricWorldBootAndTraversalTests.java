package com.michionlion.fabric.gametest;

import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.state.CivWorldState;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FabricWorldBootAndTraversalTests {
    private static final int SETUP_TICKS = 40;
    private static final int MAX_TICKS = 1400;

    private static final int MIN_RADIUS = 32;
    private static final int MAX_RADIUS = 1024;
    private static final int RADIUS_STEP = 64;

    private static final int INNER_RING_MAX_RADIUS = 256;
    private static final double INNER_STEP_BLOCKS = 8.0D;
    private static final double TARGET_ARC_LENGTH = 96.0D;
    private static final double START_ANGLE_RADIANS = Math.PI / 8.0D;

    private static final int GENERATED_CHUNK_SAMPLE_RADIUS = 2;
    private static final int MIN_UNIQUE_GENERATED_CHUNKS = 64;
    private static final String COMMAND_SNAPSHOT_FILENAME = "kingdom-command-export-region-0-0.geojson";
    private static final String COMMAND_SNAPSHOT_RESOURCE = "kingdom/gametest/snapshots/" + COMMAND_SNAPSHOT_FILENAME;

    @GameTest(
        structure = "kingdom:empty",
        setupTicks = SETUP_TICKS,
        maxTicks = MAX_TICKS,
        required = true,
        manualOnly = false,
        maxAttempts = 1,
        requiredSuccesses = 1,
        skyAccess = true
    )
    public void worldBootAndHybridConcentricTraversal(GameTestHelper helper) {
        if (helper.getLevel() == null) {
            helper.fail("Server level is null during game test startup.");
            return;
        }

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        if (player == null || !player.isAlive()) {
            helper.fail("Failed to create a live mock server player.");
            return;
        }

        BlockPos origin = player.blockPosition();
        double travelY = player.getY();

        List<Waypoint> waypoints = generateWaypoints(origin);
        if (waypoints.isEmpty()) {
            helper.fail("No traversal waypoints were generated.");
            return;
        }

        LongSet generatedChunks = new LongOpenHashSet();

        int[] waypointIndex = new int[] {0};
        boolean[] completed = new boolean[] {false};

        helper.onEachTick(() -> {
            if (completed[0]) {
                return;
            }

            if (!player.isAlive()) {
                helper.fail("Mock player died at tick " + helper.getTick() + ".");
                return;
            }

            int index = waypointIndex[0];
            if (index < waypoints.size()) {
                Waypoint waypoint = waypoints.get(index);
                boolean reached = movePlayerTowards(player, waypoint.pos(), waypoint.innerRing(), travelY);

                sampleGeneratedChunks(helper.getLevel().getChunkSource(), player.blockPosition(), generatedChunks);

                if (reached) {
                    waypointIndex[0] = index + 1;
                }
                return;
            }

            boolean valid = validateCompletion(helper, player, origin, generatedChunks, waypoints.size(), helper.getTick());
            completed[0] = true;
            if (valid) {
                helper.succeed();
            }
        });
    }

    @GameTest(
        structure = "kingdom:empty",
        setupTicks = 20,
        maxTicks = 320,
        required = true,
        manualOnly = false,
        maxAttempts = 1,
        requiredSuccesses = 1,
        skyAccess = true
    )
    public void kingdomCommandsGenerateAndExportSnapshot(GameTestHelper helper) {
        if (helper.getLevel() == null) {
            helper.fail("Server level is null during command test startup.");
            return;
        }

        helper.runAtTickTime(1L, () -> {
            try {
                ServerLevel level = helper.getLevel();
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
                    return;
                }

                if (!Files.isRegularFile(outputPath)) {
                    helper.fail("Expected exported geojson at " + outputPath.toAbsolutePath());
                    return;
                }

                String actual = normalize(Files.readString(outputPath, StandardCharsets.UTF_8));
                String expected = readResourceOrNull(COMMAND_SNAPSHOT_RESOURCE);
                if (expected == null) {
                    helper.fail("Missing required snapshot resource: " + COMMAND_SNAPSHOT_RESOURCE);
                    return;
                }

                String normalizedExpected = normalize(expected);
                if (!normalizedExpected.equals(actual)) {
                    helper.fail(
                        "GeoJSON snapshot mismatch for /kingdom export."
                            + " expected_sha256=" + sha256(normalizedExpected)
                            + " actual_sha256=" + sha256(actual)
                            + " output=" + outputPath.toAbsolutePath()
                    );
                    return;
                }

                helper.succeed();
            } catch (Exception error) {
                helper.fail("Command regression test failed: " + error.getMessage());
            }
        });
    }

    private static boolean validateCompletion(
        GameTestHelper helper,
        ServerPlayer player,
        BlockPos origin,
        LongSet generatedChunks,
        int totalWaypoints,
        long elapsedTicks
    ) {
        double dx = player.getX() - (origin.getX() + 0.5D);
        double dz = player.getZ() - (origin.getZ() + 0.5D);
        double reachedRadius = Math.hypot(dx, dz);

        if (reachedRadius < MAX_RADIUS) {
            helper.fail(
                "Traversal ended below target radius. reachedRadius=" + formatDouble(reachedRadius)
                    + ", required=" + MAX_RADIUS
                    + ", completedWaypoints=" + totalWaypoints
                    + ", elapsedTicks=" + elapsedTicks
            );
            return false;
        }

        if (generatedChunks.size() < MIN_UNIQUE_GENERATED_CHUNKS) {
            helper.fail(
                "Chunk generation progress below threshold. generatedChunks=" + generatedChunks.size()
                    + ", required=" + MIN_UNIQUE_GENERATED_CHUNKS
                    + ", completedWaypoints=" + totalWaypoints
                    + ", elapsedTicks=" + elapsedTicks
            );
            return false;
        }

        return true;
    }

    private static boolean movePlayerTowards(ServerPlayer player, BlockPos target, boolean innerRing, double travelY) {
        double targetX = target.getX() + 0.5D;
        double targetZ = target.getZ() + 0.5D;

        if (!innerRing) {
            player.teleportTo(targetX, travelY, targetZ);
            return true;
        }

        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double distance = Math.hypot(dx, dz);

        if (distance <= INNER_STEP_BLOCKS) {
            player.teleportTo(targetX, travelY, targetZ);
            return true;
        }

        double ratio = INNER_STEP_BLOCKS / distance;
        double stepX = player.getX() + (dx * ratio);
        double stepZ = player.getZ() + (dz * ratio);

        player.teleportTo(stepX, travelY, stepZ);
        return false;
    }

    private static void sampleGeneratedChunks(ServerChunkCache chunkCache, BlockPos playerPos, LongSet generatedChunks) {
        int centerChunkX = SectionPos.blockToSectionCoord(playerPos.getX());
        int centerChunkZ = SectionPos.blockToSectionCoord(playerPos.getZ());

        for (int dx = -GENERATED_CHUNK_SAMPLE_RADIUS; dx <= GENERATED_CHUNK_SAMPLE_RADIUS; dx++) {
            for (int dz = -GENERATED_CHUNK_SAMPLE_RADIUS; dz <= GENERATED_CHUNK_SAMPLE_RADIUS; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;

                if (chunkCache.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null) {
                    generatedChunks.add(ChunkPos.asLong(chunkX, chunkZ));
                }
            }
        }
    }

    private static List<Waypoint> generateWaypoints(BlockPos origin) {
        Map<Long, Waypoint> deduplicated = new LinkedHashMap<>();

        for (int radius = MIN_RADIUS; radius <= MAX_RADIUS; radius += RADIUS_STEP) {
            boolean innerRing = radius <= INNER_RING_MAX_RADIUS;
            int ringPointCount = Math.max(8, (int) Math.ceil((2.0D * Math.PI * radius) / TARGET_ARC_LENGTH));
            double angleStep = (2.0D * Math.PI) / ringPointCount;

            for (int i = 0; i < ringPointCount; i++) {
                double angle = START_ANGLE_RADIANS - (i * angleStep);
                int x = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);

                BlockPos point = new BlockPos(x, origin.getY(), z);
                deduplicated.putIfAbsent(point.asLong(), new Waypoint(point, innerRing));
            }
        }

        BlockPos finalPoint = new BlockPos(origin.getX() + MAX_RADIUS, origin.getY(), origin.getZ());
        deduplicated.put(finalPoint.asLong(), new Waypoint(finalPoint, false));

        return new ArrayList<>(deduplicated.values());
    }

    private static String formatDouble(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static void runCommand(ServerLevel level, CommandSourceStack source, String command) {
        level.getServer().getCommands().performPrefixedCommand(source, command);
    }

    private static String normalize(String content) {
        return content.replace("\r\n", "\n").trim();
    }

    private static String readResourceOrNull(String resourcePath) throws Exception {
        try (InputStream stream = FabricWorldBootAndTraversalTests.class.getClassLoader().getResourceAsStream(resourcePath)) {
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

    private record Waypoint(BlockPos pos, boolean innerRing) {
    }
}

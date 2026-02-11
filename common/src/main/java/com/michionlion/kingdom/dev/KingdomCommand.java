package com.michionlion.kingdom.dev;

import com.michionlion.kingdom.civ.config.KingdomPlacementConfigManager;
import com.michionlion.kingdom.civ.model.RegionKey;
import com.michionlion.kingdom.civ.placement.CivPlacementConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class KingdomCommand {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static final KingdomDebugService SERVICE = new KingdomDebugService();

    private KingdomCommand() {
    }

    public static void bootstrap() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }

        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, selection) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("kingdom")
                .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                .then(Commands.literal("generate")
                    .then(Commands.literal("region")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(context -> executeGenerateRegion(context, false))
                                .then(Commands.argument("force", BoolArgumentType.bool())
                                    .executes(context -> executeGenerateRegion(context, BoolArgumentType.getBool(context, "force")))))))
                    .then(Commands.literal("around")
                        .executes(context -> executeGenerateAround(context, SERVICE.config().defaultAroundRegionRadius(), false))
                        .then(Commands.argument("radiusRegions", IntegerArgumentType.integer(0, 64))
                            .executes(context -> executeGenerateAround(
                                context,
                                IntegerArgumentType.getInteger(context, "radiusRegions"),
                                false
                            ))
                            .then(Commands.argument("force", BoolArgumentType.bool())
                                .executes(context -> executeGenerateAround(
                                    context,
                                    IntegerArgumentType.getInteger(context, "radiusRegions"),
                                    BoolArgumentType.getBool(context, "force")
                                ))))))
                .then(Commands.literal("summary")
                    .executes(context -> executeSummary(context, SERVICE.config().defaultSummaryRegionRadius()))
                    .then(Commands.argument("radiusRegions", IntegerArgumentType.integer(0, 128))
                        .executes(context -> executeSummary(context, IntegerArgumentType.getInteger(context, "radiusRegions")))))
                .then(Commands.literal("visualize")
                    .then(Commands.literal("anchors")
                        .executes(context -> executeVisualize(context, SERVICE.config().defaultVisualizationRadiusBlocks()))
                        .then(Commands.argument("radiusBlocks", IntegerArgumentType.integer(64, 16384))
                            .executes(context -> executeVisualize(context, IntegerArgumentType.getInteger(context, "radiusBlocks"))))))
                .then(Commands.literal("export")
                    .then(Commands.literal("geojson")
                        .then(Commands.argument("radiusRegions", IntegerArgumentType.integer(0, 128))
                            .executes(context -> executeExport(context, IntegerArgumentType.getInteger(context, "radiusRegions"), false, ""))
                            .then(Commands.argument("includeRejected", BoolArgumentType.bool())
                                .executes(context -> executeExport(
                                    context,
                                    IntegerArgumentType.getInteger(context, "radiusRegions"),
                                    BoolArgumentType.getBool(context, "includeRejected"),
                                    ""
                                ))
                                .then(Commands.argument("filename", StringArgumentType.word())
                                    .executes(context -> executeExport(
                                        context,
                                        IntegerArgumentType.getInteger(context, "radiusRegions"),
                                        BoolArgumentType.getBool(context, "includeRejected"),
                                        StringArgumentType.getString(context, "filename")
                                    )))))))
                .then(Commands.literal("config")
                    .then(Commands.literal("show").executes(KingdomCommand::executeConfigShow))
                    .then(Commands.literal("reload").executes(KingdomCommand::executeConfigReload))
                    .then(Commands.literal("set")
                        .then(Commands.argument("path", StringArgumentType.word())
                            .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(KingdomCommand::executeConfigSet))))));
    }

    private static int executeGenerateRegion(CommandContext<CommandSourceStack> context, boolean force) {
        CommandSourceStack source = context.getSource();
        int x = IntegerArgumentType.getInteger(context, "x");
        int z = IntegerArgumentType.getInteger(context, "z");

        KingdomDebugService.RegionOperationResult result = SERVICE.generateRegion(source.getLevel(), new RegionKey(x, z), force);
        if (result.skipped()) {
            source.sendSuccess(() -> Component.literal("[kingdom] region " + x + "," + z + " already planned (use force=true to regenerate)."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
            "[kingdom] generated region " + x + "," + z
                + " anchors=" + result.generatedAnchors()
                + " accepted_candidates=" + result.acceptedCandidates()
        ), false);
        return result.generatedAnchors();
    }

    private static int executeGenerateAround(CommandContext<CommandSourceStack> context, int radiusRegions, boolean force) {
        CommandSourceStack source = context.getSource();
        KingdomDebugService.AroundOperationResult result = SERVICE.generateAround(source.getLevel(), blockPos(source), radiusRegions, force);

        source.sendSuccess(() -> Component.literal(
            "[kingdom] generate around center_region=" + result.centerRegion().x() + "," + result.centerRegion().z()
                + " radius=" + radiusRegions
                + " regions=" + result.totalRegions()
                + " skipped=" + result.skippedRegions()
                + " anchors=" + result.generatedAnchors()
        ), false);
        return result.generatedAnchors();
    }

    private static int executeSummary(CommandContext<CommandSourceStack> context, int radiusRegions) {
        CommandSourceStack source = context.getSource();
        KingdomDebugService.Summary summary = SERVICE.summary(source.getLevel(), blockPos(source), radiusRegions);

        source.sendSuccess(() -> Component.literal(
            "[kingdom] summary center_region=" + summary.centerRegion().x() + "," + summary.centerRegion().z()
                + " radius=" + radiusRegions
                + " planned_regions=" + summary.plannedRegions()
                + " anchors=" + summary.totalAnchors()
        ), false);

        source.sendSuccess(() -> Component.literal("[kingdom] by_tier=" + mapToCompactString(summary.anchorsByTier())), false);
        source.sendSuccess(() -> Component.literal("[kingdom] by_type=" + mapToCompactString(summary.anchorsByType())), false);
        return summary.totalAnchors();
    }

    private static int executeVisualize(CommandContext<CommandSourceStack> context, int radiusBlocks) {
        CommandSourceStack source = context.getSource();
        int visualized = SERVICE.visualizeAnchors(source.getLevel(), blockPos(source), radiusBlocks);

        source.sendSuccess(() -> Component.literal("[kingdom] visualized anchors=" + visualized + " radius_blocks=" + radiusBlocks), false);
        return visualized;
    }

    private static int executeExport(CommandContext<CommandSourceStack> context, int radiusRegions, boolean includeRejected, String filename) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();

        try {
            Path file = SERVICE.exportGeoJson(level, blockPos(source), radiusRegions, includeRejected, filename);
            source.sendSuccess(() -> Component.literal("[kingdom] wrote geojson=" + file.toAbsolutePath()), false);
            return 1;
        } catch (IOException error) {
            source.sendFailure(Component.literal("[kingdom] failed to export geojson: " + error.getMessage()));
            return 0;
        }
    }

    private static int executeConfigShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        CivPlacementConfig config = SERVICE.config();

        source.sendSuccess(() -> Component.literal("[kingdom] config file=" + KingdomPlacementConfigManager.configPath().toAbsolutePath()), false);
        source.sendSuccess(() -> Component.literal(
            "[kingdom] region.size=" + config.regionSizeBlocks()
                + " min_spacing=" + config.minAnchorSpacingBlocks()
                + " candidate.max=" + config.maxCandidatesPerRegion()
        ), false);
        source.sendSuccess(() -> Component.literal(
            "[kingdom] weights biome=" + config.biomeWeight()
                + " height=" + config.heightWeight()
                + " slope=" + config.slopeWeight()
                + " water=" + config.waterWeight()
        ), false);
        source.sendSuccess(() -> Component.literal(
            "[kingdom] thresholds wood=" + config.woodThreshold()
                + " stone=" + config.stoneThreshold()
                + " iron=" + config.ironThreshold()
                + " diamond=" + config.diamondThreshold()
                + " netherite=" + config.netheriteThreshold()
        ), false);
        source.sendSuccess(() -> Component.literal(
            "[kingdom] cluster dist min=" + config.satelliteMinDistanceBlocks()
                + " max=" + config.satelliteMaxDistanceBlocks()
                + " visualize.radius=" + config.defaultVisualizationRadiusBlocks()
        ), false);

        return 1;
    }

    private static int executeConfigReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean loaded = KingdomPlacementConfigManager.reload();
        source.sendSuccess(() -> Component.literal("[kingdom] config reload=" + loaded), false);
        return loaded ? 1 : 0;
    }

    private static int executeConfigSet(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String path = StringArgumentType.getString(context, "path");
        String value = StringArgumentType.getString(context, "value").trim();

        boolean updated = KingdomPlacementConfigManager.setValue(path, value);
        if (!updated) {
            source.sendFailure(Component.literal(
                "[kingdom] invalid config path/value. supported paths: "
                    + "region.size, region.min_spacing, candidate.cell_size, candidate.max_per_region, candidate.water_radius, "
                    + "weights.biome, weights.height, weights.slope, weights.water, "
                    + "thresholds.wood, thresholds.stone, thresholds.iron, thresholds.diamond, thresholds.netherite, "
                    + "cluster.min_satellite_distance, cluster.max_satellite_distance"
            ));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[kingdom] updated config " + path + "=" + value), false);
        return 1;
    }

    private static net.minecraft.core.BlockPos blockPos(CommandSourceStack source) {
        return net.minecraft.core.BlockPos.containing(source.getPosition());
    }

    private static String mapToCompactString(Map<?, ?> map) {
        if (map.isEmpty()) {
            return "{}";
        }

        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        builder.append('}');
        return builder.toString();
    }
}

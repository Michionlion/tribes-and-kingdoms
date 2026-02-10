package com.michionlion.kingdom.dev;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DevCommandBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ENABLED_PROPERTY = "kingdom.dev.commandBridge";
    private static final String FILE_PROPERTY = "kingdom.dev.commandBridgeFile";
    private static final long POLL_INTERVAL_MS = 250L;

    private static final boolean ENABLED = resolveEnabled();
    private static long nextPollAtMs;

    private DevCommandBridge() {
    }

    public static void tick(Minecraft minecraft) {
        if (!ENABLED || minecraft == null || minecraft.player == null || minecraft.level == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextPollAtMs) {
            return;
        }
        nextPollAtMs = now + POLL_INTERVAL_MS;

        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || !server.isRunning()) {
            return;
        }

        Path commandFile = getCommandFile();
        if (!Files.isRegularFile(commandFile)) {
            return;
        }

        List<String> commands;
        try {
            commands = consumeCommands(commandFile);
        } catch (IOException error) {
            LOGGER.warn("Failed to read dev command file at {}.", commandFile.toAbsolutePath(), error);
            return;
        }

        if (commands.isEmpty()) {
            return;
        }

        UUID playerId = minecraft.player.getUUID();
        server.execute(() -> executeCommands(server, playerId, commands));
    }

    private static void executeCommands(IntegratedServer server, UUID playerId, List<String> commands) {
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(playerId);
        if (serverPlayer == null) {
            return;
        }

        for (String command : commands) {
            try {
                server.getCommands().performPrefixedCommand(serverPlayer.createCommandSourceStack(), command);
            } catch (Exception error) {
                LOGGER.warn("Failed to execute dev command '{}'.", command, error);
            }
        }
    }

    private static List<String> consumeCommands(Path commandFile) throws IOException {
        List<String> lines = Files.readAllLines(commandFile, StandardCharsets.UTF_8);
        Files.writeString(
            commandFile,
            "",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );

        List<String> commands = new ArrayList<>(lines.size());
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            String command = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
            if (!command.isBlank()) {
                commands.add(command);
            }
        }

        return commands;
    }

    private static Path getCommandFile() {
        String configuredPath = System.getProperty(FILE_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = "run/kingdom-dev-commands.txt";
        }
        return Path.of(configuredPath);
    }

    private static boolean resolveEnabled() {
        String explicit = System.getProperty(ENABLED_PROPERTY);
        if (explicit != null) {
            return Boolean.parseBoolean(explicit);
        }

        return Boolean.parseBoolean(System.getProperty("fabric.development", "false"));
    }
}

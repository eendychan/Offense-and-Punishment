package ru.endychan.offenseandpunishment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class OffenseAndPunishment implements ModInitializer {
    public static final String MOD_ID = "offense_and_punishment";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final double MINIMUM_MAX_HEALTH = 2.0;
    private static final int DEFAULT_PUNISHMENT_HP = 2;
    private static final int DEFAULT_PUNISHMENT_TIME_SECONDS = 30 * 60;
    private static int punishmentHp = DEFAULT_PUNISHMENT_HP;
    private static int punishmentTimeSeconds = DEFAULT_PUNISHMENT_TIME_SECONDS;
    private static final long TICK_INTERVAL = 20L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<Map<String, PlayerData>>() {}.getType();

    private static final Map<UUID, PlayerData> PLAYERS = new HashMap<>();
    private static MinecraftServer currentServer;
    private static Path dataFile;
    private static Path configFile;
    private static long tickCounter;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            currentServer = server;
            dataFile = server.getWorldPath(LevelResource.ROOT).resolve("offense_and_punishment.json");
            configFile = server.getWorldPath(LevelResource.ROOT).resolve("offense_and_punishment_config.json");
            loadConfig();
            loadData();
            LOGGER.info("Offense and Punishment data loaded.");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { saveData(); saveConfig(); });

        registerCommands();

        // A non-lethal hit on a player resets the attacker's recovery timer.
        // AFTER_DAMAGE also fires when a shield blocks the hit.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
            if (!(entity instanceof ServerPlayer)) return;
            if (!(source.getEntity() instanceof ServerPlayer attacker)) return;

            PlayerData data = getOrCreate(attacker);
            data.lastPlayerHitAt = System.currentTimeMillis();
            saveData();
        });

        // Fatal hits do not trigger AFTER_DAMAGE, so kills are handled here.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayer victim)) return;
            if (!(source.getEntity() instanceof ServerPlayer killer)) return;
            if (killer.getUUID().equals(victim.getUUID())) return;

            PlayerData data = getOrCreate(killer);
            data.lastPlayerHitAt = System.currentTimeMillis();

            double availablePenalty = Math.max(0.0, data.originalMaxHealth - MINIMUM_MAX_HEALTH);
            if (data.missingHealth + 0.0001 < availablePenalty) {
                data.missingHealth = Math.min(availablePenalty, data.missingHealth + punishmentHp);
                applyMaxHealth(killer, data);
                killer.sendSystemMessage(
                    Component.literal("§cУбийство игрока: максимальное здоровье −" + punishmentHp + " HP."),
                    true
                );
            } else {
                killer.sendSystemMessage(
                    Component.literal("§7Дальнейшие убийства не уменьшают максимальное здоровье."),
                    true
                );
            }
            saveData();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter % TICK_INTERVAL != 0) return;

            long now = System.currentTimeMillis();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PlayerData data = getOrCreate(player);
                // Re-apply the stored maximum after reconnects and other attribute resets.
                applyMaxHealth(player, data);
                updateRecovery(player, data, now);
                updateActionbar(player, data, now);
            }
        });
    }

    private static PlayerData getOrCreate(ServerPlayer player) {
        return PLAYERS.computeIfAbsent(player.getUUID(), uuid -> {
            PlayerData data = new PlayerData();
            AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
            data.originalMaxHealth = maxHealth == null ? 20.0 : maxHealth.getBaseValue();
            data.lastPlayerHitAt = 0L;
            return data;
        });
    }

    private static void applyMaxHealth(ServerPlayer player, PlayerData data) {
        AttributeInstance attribute = player.getAttribute(Attributes.MAX_HEALTH);
        if (attribute == null) return;

        double newMaximum = Math.max(
            MINIMUM_MAX_HEALTH,
            data.originalMaxHealth - data.missingHealth
        );
        attribute.setBaseValue(newMaximum);

        if (player.getHealth() > newMaximum) {
            player.setHealth((float) newMaximum);
        }
    }

    private static void updateRecovery(ServerPlayer player, PlayerData data, long now) {
        if (data.missingHealth <= 0.0001 || data.lastPlayerHitAt <= 0L) return;

        long elapsed = now - data.lastPlayerHitAt;
        long recoveryIntervalMs = punishmentTimeSeconds * 1000L;
        if (elapsed < recoveryIntervalMs) return;

        long completedIntervals = elapsed / recoveryIntervalMs;
        int intervalsToApply = (int) Math.min(
            Math.ceil(data.missingHealth / punishmentHp), completedIntervals);
        if (intervalsToApply <= 0) return;

        double recoveredHealth = Math.min(data.missingHealth, (double) intervalsToApply * punishmentHp);
        data.missingHealth = Math.max(0.0, data.missingHealth - recoveredHealth);
        data.lastPlayerHitAt += intervalsToApply * recoveryIntervalMs;

        applyMaxHealth(player, data);

        if (data.missingHealth <= 0.0001) {
            data.missingHealth = 0.0;
            data.lastPlayerHitAt = 0L;
            player.sendSystemMessage(
                Component.literal("§aМаксимальное здоровье полностью восстановлено."),
                true
            );
        } else {
            player.sendSystemMessage(
                Component.literal("§aВосстановлено максимальное здоровье: +" + recoveredHealth + " HP."),
                true
            );
        }
        saveData();
    }

    private static void updateActionbar(ServerPlayer player, PlayerData data, long now) {
        if (data.missingHealth <= 0.0001 || data.lastPlayerHitAt <= 0L) return;

        long elapsed = Math.max(0L, now - data.lastPlayerHitAt);
        long recoveryIntervalMs = punishmentTimeSeconds * 1000L;
        long remaining = recoveryIntervalMs - (elapsed % recoveryIntervalMs);
        long totalSeconds = (remaining + 999L) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        String timer = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        player.sendSystemMessage(
            Component.literal("§7До восстановления +" + punishmentHp + " HP: §f" + timer),
            true
        );
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("punishment_time")
                .requires(source -> source.hasPermission(2))
                .then(argument("seconds", IntegerArgumentType.integer(1, 2_147_483))
                    .executes(context -> {
                        int seconds = IntegerArgumentType.getInteger(context, "seconds");
                        punishmentTimeSeconds = seconds;
                        saveConfig();
                        context.getSource().sendSuccess(
                            () -> Component.literal("Время восстановления установлено: " + seconds + " сек."), true);
                        return 1;
                    })));

            dispatcher.register(literal("punishment_hp")
                .requires(source -> source.hasPermission(2))
                .then(argument("amount", IntegerArgumentType.integer(1, 1024))
                    .executes(context -> {
                        int amount = IntegerArgumentType.getInteger(context, "amount");
                        punishmentHp = amount;
                        if (currentServer != null) {
                            for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
                                PlayerData data = getOrCreate(player);
                                applyMaxHealth(player, data);
                            }
                        }
                        saveConfig();
                        saveData();
                        context.getSource().sendSuccess(
                            () -> Component.literal("Размер наказания и восстановления установлен: " + amount + " HP."), true);
                        return 1;
                    })));
        });
    }

    private static void loadConfig() {
        if (configFile == null || !Files.exists(configFile)) return;
        try (Reader reader = Files.newBufferedReader(configFile)) {
            ModConfig config = GSON.fromJson(reader, ModConfig.class);
            if (config == null) return;
            if (config.punishmentHp >= 1 && config.punishmentHp <= 1024) {
                punishmentHp = config.punishmentHp;
            }
            if (config.punishmentTimeSeconds >= 1) {
                punishmentTimeSeconds = config.punishmentTimeSeconds;
            }
        } catch (Exception exception) {
            LOGGER.error("Could not load Offense and Punishment config; using defaults.", exception);
        }
    }

    private static void saveConfig() {
        if (configFile == null) return;
        try {
            Files.createDirectories(configFile.getParent());
            ModConfig config = new ModConfig();
            config.punishmentHp = punishmentHp;
            config.punishmentTimeSeconds = punishmentTimeSeconds;
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                GSON.toJson(config, writer);
            }
        } catch (Exception exception) {
            LOGGER.error("Could not save Offense and Punishment config.", exception);
        }
    }

    private static void loadData() {
        PLAYERS.clear();
        if (dataFile == null || !Files.exists(dataFile)) return;

        try (Reader reader = Files.newBufferedReader(dataFile)) {
            Map<String, PlayerData> loaded = GSON.fromJson(reader, DATA_TYPE);
            if (loaded == null) return;

            for (Map.Entry<String, PlayerData> entry : loaded.entrySet()) {
                try {
                    PlayerData data = entry.getValue();
                    // Migrate player data saved by the first version, which stored a count of 2-HP penalties.
                    if (data.missingHealth <= 0.0001 && data.penalties > 0) {
                        data.missingHealth = data.penalties * 2.0;
                        data.penalties = 0;
                    }
                    PLAYERS.put(UUID.fromString(entry.getKey()), data);
                } catch (IllegalArgumentException ignored) {
                    LOGGER.warn("Skipping invalid player UUID in saved data: {}", entry.getKey());
                }
            }
        } catch (Exception exception) {
            LOGGER.error("Could not load Offense and Punishment data.", exception);
        }
    }

    private static void saveData() {
        if (dataFile == null) return;

        try {
            Files.createDirectories(dataFile.getParent());
            Map<String, PlayerData> serialized = new HashMap<>();
            PLAYERS.forEach((uuid, data) -> serialized.put(uuid.toString(), data));

            try (Writer writer = Files.newBufferedWriter(dataFile)) {
                GSON.toJson(serialized, DATA_TYPE, writer);
            }
        } catch (Exception exception) {
            LOGGER.error("Could not save Offense and Punishment data.", exception);
        }
    }

    private static final class ModConfig {
        int punishmentHp = DEFAULT_PUNISHMENT_HP;
        int punishmentTimeSeconds = DEFAULT_PUNISHMENT_TIME_SECONDS;
    }

    private static final class PlayerData {
        double originalMaxHealth = 20.0;
        // Actual missing maximum-health points; this keeps old penalties stable if the admin changes punishment_hp.
        double missingHealth = 0.0;
        // Legacy field retained only to migrate data created by the earlier build.
        int penalties = 0;
        long lastPlayerHitAt = 0L;
    }
}

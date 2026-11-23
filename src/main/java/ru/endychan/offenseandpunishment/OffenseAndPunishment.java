package ru.endychan.offenseandpunishment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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

    private static final double PENALTY_PER_KILL = 2.0;
    private static final double MINIMUM_MAX_HEALTH = 2.0;
    private static final long RECOVERY_INTERVAL_MS = 3L * 60L * 60L * 1000L;
    private static final long TICK_INTERVAL = 20L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<Map<String, PlayerData>>() {}.getType();

    private static final Map<UUID, PlayerData> PLAYERS = new HashMap<>();
    private static MinecraftServer currentServer;
    private static Path dataFile;
    private static long tickCounter;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            currentServer = server;
            dataFile = server.getWorldPath(LevelResource.ROOT).resolve("offense_and_punishment.json");
            loadData();
            LOGGER.info("Offense and Punishment data loaded.");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveData());

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
            int maxPenalties = (int) Math.floor((availablePenalty + 0.0001) / PENALTY_PER_KILL);
            if (data.penalties < maxPenalties) {
                data.penalties++;
                applyMaxHealth(killer, data);
                killer.displayClientMessage(
                    Component.literal("§cУбийство игрока: максимальное здоровье −2 HP."),
                    true
                );
            } else {
                killer.displayClientMessage(
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
            data.originalMaxHealth - data.penalties * PENALTY_PER_KILL
        );
        attribute.setBaseValue(newMaximum);

        if (player.getHealth() > newMaximum) {
            player.setHealth((float) newMaximum);
        }
    }

    private static void updateRecovery(ServerPlayer player, PlayerData data, long now) {
        if (data.penalties <= 0 || data.lastPlayerHitAt <= 0L) return;

        long elapsed = now - data.lastPlayerHitAt;
        if (elapsed < RECOVERY_INTERVAL_MS) return;

        long completedIntervals = elapsed / RECOVERY_INTERVAL_MS;
        int recovered = (int) Math.min(data.penalties, completedIntervals);
        if (recovered <= 0) return;

        data.penalties -= recovered;
        data.lastPlayerHitAt += recovered * RECOVERY_INTERVAL_MS;

        applyMaxHealth(player, data);

        if (data.penalties == 0) {
            data.lastPlayerHitAt = 0L;
            player.displayClientMessage(
                Component.literal("§aМаксимальное здоровье полностью восстановлено."),
                true
            );
        } else {
            player.displayClientMessage(
                Component.literal("§aВосстановлено максимальное здоровье: +" + (recovered * 2) + " HP."),
                true
            );
        }
        saveData();
    }

    private static void updateActionbar(ServerPlayer player, PlayerData data, long now) {
        if (data.penalties <= 0 || data.lastPlayerHitAt <= 0L) return;

        long elapsed = Math.max(0L, now - data.lastPlayerHitAt);
        long remaining = RECOVERY_INTERVAL_MS - (elapsed % RECOVERY_INTERVAL_MS);
        long totalSeconds = (remaining + 999L) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        String timer = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        player.displayClientMessage(
            Component.literal("§7До восстановления +2 HP: §f" + timer),
            true
        );
    }

    private static void loadData() {
        PLAYERS.clear();
        if (dataFile == null || !Files.exists(dataFile)) return;

        try (Reader reader = Files.newBufferedReader(dataFile)) {
            Map<String, PlayerData> loaded = GSON.fromJson(reader, DATA_TYPE);
            if (loaded == null) return;

            for (Map.Entry<String, PlayerData> entry : loaded.entrySet()) {
                try {
                    PLAYERS.put(UUID.fromString(entry.getKey()), entry.getValue());
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

    private static final class PlayerData {
        double originalMaxHealth = 20.0;
        int penalties = 0;
        long lastPlayerHitAt = 0L;
    }
}

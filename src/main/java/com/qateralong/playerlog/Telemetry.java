package com.qateralong.playerlog;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;

public final class Telemetry {
    private static final long LOOT_MILLIS = 15 * 60 * 1000L;
    private static final Map<UUID, String> DEATH_IDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Loot> LOOT = new ConcurrentHashMap<>();

    private record Loot(String owner, UUID ownerId, long expires) {
    }

    private Telemetry() {
    }

    public static void beforeDeath(ServerPlayer player) {
        DEATH_IDS.remove(player.getUUID());
        if (!PlayerLog.config().saveDeathItems || player.isSpectator()
                || player.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            return;
        }
        String id = DeathStore.save(player);
        if (!id.isEmpty()) {
            DEATH_IDS.put(player.getUUID(), id);
        }
    }

    public static void afterDeath(ServerPlayer player) {
        if (!PlayerLog.config().logPickups) {
            return;
        }
        long now = System.currentTimeMillis();
        LOOT.values().removeIf(loot -> loot.expires() < now);
        for (ItemEntity item : player.serverLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(4.0), item -> item.tickCount == 0)) {
            LOOT.put(item.getUUID(), new Loot(player.getGameProfile().getName(), player.getUUID(), now + LOOT_MILLIS));
        }
    }

    public static String takeDeathId(UUID player) {
        String id = DEATH_IDS.remove(player);
        return id == null ? "" : id;
    }

    public static boolean tracked(ItemEntity item, UUID thrower, Player picker) {
        if (!PlayerLog.config().logPickups) {
            return false;
        }
        return LOOT.containsKey(item.getUUID()) || thrower != null && !thrower.equals(picker.getUUID());
    }

    public static void pickup(ServerPlayer picker, ItemEntity item, UUID thrower, ItemStack stack, int count) {
        Loot loot = item.isRemoved() ? LOOT.remove(item.getUUID()) : LOOT.get(item.getUUID());
        String kind;
        String owner;
        if (loot != null) {
            kind = loot.ownerId().equals(picker.getUUID()) ? "own_death" : "death";
            owner = loot.owner();
        } else {
            kind = "thrown";
            owner = PlayerLog.profileName(picker.server, thrower);
        }
        LogStore.write("PICKUP", picker.getGameProfile().getName(), picker.getUUID(), picker.level().dimension().location().toString(),
                item.blockPosition(), String.valueOf(count), ContainerTracker.itemId(stack), ContainerTracker.customName(stack), kind, owner);
    }

    public static void command(CommandSourceStack source, String command) {
        PlayerLog.Config config = PlayerLog.config();
        if (!config.logCommands || !(source.getEntity() instanceof ServerPlayer player) || !source.hasPermission(config.commandPermission)) {
            return;
        }
        String text = command.startsWith("/") ? command.substring(1) : command;
        String root = text.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0) {
            root = root.substring(colon + 1);
        }
        if (config.commandSkip.contains(root)) {
            return;
        }
        LogStore.write("COMMAND", player.getGameProfile().getName(), player.getUUID(), player.level().dimension().location().toString(),
                player.blockPosition(), text.length() > 300 ? text.substring(0, 300) + "…" : text);
    }
}

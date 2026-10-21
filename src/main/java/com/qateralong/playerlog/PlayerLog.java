package com.qateralong.playerlog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayerLog implements ModInitializer {
    public static final String MOD_ID = "playerlog";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Map<UUID, String> BRANDS = new ConcurrentHashMap<>();
    public static final Set<UUID> INSPECTORS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> JOINED = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("playerlog.json");
    private static Config config = new Config();

    public static class Config {
        public String timeZone = "";
        public int retentionDays = 120;
        public int searchDays = 30;
        public boolean logContainers = true;
        public boolean logContainerBreaks = true;
        public boolean logJoins = true;
        public boolean logDeaths = true;
        public boolean logKills = true;
        public List<String> logExplosions = new ArrayList<>(List.of("minecraft:tnt", "minecraft:tnt_minecart", "minecraft:end_crystal", "block",
                "minecraft:wither"));
        public List<String> logSpawns = new ArrayList<>(List.of("minecraft:wither", "minecraft:ender_dragon", "minecraft:warden"));
        public List<String> killTypes = new ArrayList<>(List.of("minecraft:villager", "minecraft:iron_golem", "minecraft:snow_golem",
                "minecraft:allay", "minecraft:wandering_trader", "minecraft:horse", "minecraft:donkey", "minecraft:mule", "minecraft:llama",
                "minecraft:camel", "minecraft:sniffer", "minecraft:axolotl", "minecraft:fox", "minecraft:frog", "minecraft:turtle"));
    }

    public static Config config() {
        return config;
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            load();
            LogStore.cleanup(config.retentionDays);
        });
        ServerTickEvents.END_SERVER_TICK.register(EventTracker::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ContainerTracker.flush(player);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LogStore.shutdown());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> join(handler.player));
        ServerLivingEntityEvents.AFTER_DEATH.register(PlayerLog::death);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            breakContainer(level, player, pos, state, blockEntity);
            return true;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !INSPECTORS.contains(player.getUUID()) || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            if (hand == InteractionHand.MAIN_HAND) {
                LogCommand.showContainer(serverPlayer.createCommandSourceStack(), level.dimension().location().toString(),
                        containerPositions(level, hit.getBlockPos()), 1);
            }
            return InteractionResult.SUCCESS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> LogCommand.register(dispatcher));
    }

    private static void load() {
        try {
            if (Files.exists(FILE)) {
                try (Reader reader = Files.newBufferedReader(FILE)) {
                    Config loaded = GSON.fromJson(reader, Config.class);
                    if (loaded != null) {
                        config = loaded;
                    }
                }
            }
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(config, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load playerlog.json", e);
        }
    }

    public static void reload() {
        load();
    }

    public static List<BlockPos> containerPositions(Level level, BlockPos pos) {
        List<BlockPos> positions = new ArrayList<>(List.of(pos.immutable()));
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            positions.add(pos.relative(ChestBlock.getConnectedDirection(state)).immutable());
        }
        return positions;
    }

    private static String dimension(Entity entity) {
        return entity.level().dimension().location().toString();
    }

    private static void join(ServerPlayer player) {
        JOINED.put(player.getUUID(), System.currentTimeMillis());
        if (!config.logJoins) {
            return;
        }
        String brand = BRANDS.getOrDefault(player.getUUID(), "?");
        List<String> mods = new ArrayList<>();
        List<String> packs = new ArrayList<>();
        clientReport(player.getUUID(), mods, packs);
        LogStore.write("JOIN", player.getGameProfile().getName(), player.getUUID(), dimension(player), player.blockPosition(),
                brand, mods.isEmpty() && packs.isEmpty() ? "-" : String.valueOf(mods.size()), String.join(", ", mods), String.join(", ", packs));
    }

    public static void leave(ServerPlayer player, String reason) {
        if (player == null) {
            return;
        }
        ContainerTracker.flush(player);
        Long since = JOINED.remove(player.getUUID());
        BRANDS.remove(player.getUUID());
        INSPECTORS.remove(player.getUUID());
        if (!config.logJoins) {
            return;
        }
        long seconds = since == null ? -1L : (System.currentTimeMillis() - since) / 1000L;
        LogStore.write("LEAVE", player.getGameProfile().getName(), player.getUUID(), dimension(player), player.blockPosition(),
                String.valueOf(seconds), reason);
    }

    private static void clientReport(UUID uuid, List<String> mods, List<String> packs) {
        if (!FabricLoader.getInstance().isModLoaded("mod_guard")) {
            return;
        }
        try {
            Class<?> log = Class.forName("com.qateralong.modguard.ReportLog");
            Object record = log.getMethod("latest", UUID.class).invoke(null, uuid);
            if (record == null) {
                return;
            }
            Object report = record.getClass().getMethod("report").invoke(record);
            for (Object mod : (List<?>) report.getClass().getMethod("mods").invoke(report)) {
                Method parent = mod.getClass().getMethod("parent");
                if (String.valueOf(parent.invoke(mod)).isEmpty()) {
                    mods.add(mod.getClass().getMethod("id").invoke(mod) + "@" + mod.getClass().getMethod("version").invoke(mod));
                }
            }
            for (Object pack : (List<?>) report.getClass().getMethod("packs").invoke(report)) {
                packs.add(String.valueOf(pack.getClass().getMethod("name").invoke(pack)));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("No mod_guard report for {}", uuid, e);
        }
    }

    private static String weapon(Entity killer) {
        return killer instanceof LivingEntity living && !living.getMainHandItem().isEmpty()
                ? ContainerTracker.itemId(living.getMainHandItem()) : "";
    }

    private static String weaponName(Entity killer) {
        return killer instanceof LivingEntity living ? ContainerTracker.customName(living.getMainHandItem()) : "";
    }

    private static String name(Entity entity) {
        if (entity instanceof Player player) {
            return player.getGameProfile().getName();
        }
        return entity.hasCustomName() ? entity.getCustomName().getString() : "";
    }

    private static void death(LivingEntity entity, DamageSource source) {
        if (entity.level().isClientSide()) {
            return;
        }
        Entity killer = source.getEntity();
        if (entity instanceof ServerPlayer victim && config.logDeaths) {
            String killerName = killer == null ? "" : killer instanceof Player ? name(killer)
                    : BuiltInRegistries.ENTITY_TYPE.getKey(killer.getType()) + (killer.hasCustomName() ? " \"" + name(killer) + "\"" : "");
            LogStore.write("DEATH", victim.getGameProfile().getName(), victim.getUUID(), dimension(victim), victim.blockPosition(),
                    source.getLocalizedDeathMessage(victim).getString(), killerName, weapon(killer), weaponName(killer),
                    String.valueOf(victim.experienceLevel));
        }
        if (killer instanceof ServerPlayer player && killer != entity && config.logKills && notable(entity)) {
            String owner = "";
            if (entity instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null) {
                owner = profileName(player.server, ownable.getOwnerUUID());
            }
            LogStore.write("KILL", player.getGameProfile().getName(), player.getUUID(), dimension(entity), entity.blockPosition(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), name(entity), weapon(player), weaponName(player), owner);
        }
    }

    private static String profileName(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        GameProfileCache cache = server.getProfileCache();
        return cache == null ? id.toString() : cache.get(id).map(GameProfile::getName).orElse(id.toString());
    }

    private static boolean notable(LivingEntity entity) {
        return entity instanceof Player || entity.hasCustomName()
                || entity instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null
                || config.killTypes.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }

    private static void breakContainer(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (level.isClientSide() || !config.logContainerBreaks || !(blockEntity instanceof Container container) || container.isEmpty()) {
            return;
        }
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                items.merge(ContainerTracker.itemId(stack) + "\t" + ContainerTracker.customName(stack), stack.getCount(), Integer::sum);
            }
        }
        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            String[] item = entry.getKey().split("\t", -1);
            LogStore.write("BREAK", player.getGameProfile().getName(), player.getUUID(), level.dimension().location().toString(), pos,
                    block, String.valueOf(entry.getValue()), item[0], item[1]);
        }
    }
}

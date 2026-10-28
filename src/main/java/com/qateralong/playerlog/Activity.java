package com.qateralong.playerlog;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

public final class Activity {
    private static final Map<String, Hits> HITS = new LinkedHashMap<>();

    private static final class Hits {
        final ServerPlayer attacker;
        final ServerPlayer victim;
        final String weapon;
        final String weaponName;
        float damage;
        int count;
        long last;

        Hits(ServerPlayer attacker, ServerPlayer victim, String weapon, String weaponName) {
            this.attacker = attacker;
            this.victim = victim;
            this.weapon = weapon;
            this.weaponName = weaponName;
        }
    }

    private Activity() {
    }

    private static String dimension(Player player) {
        return player.level().dimension().location().toString();
    }

    private static String name(Player player) {
        return player.getGameProfile().getName();
    }

    public static void damage(LivingEntity entity, DamageSource source, float taken) {
        if (!PlayerLog.config().logPvp || !(entity instanceof ServerPlayer victim) || !(source.getEntity() instanceof ServerPlayer attacker)
                || attacker == victim || taken <= 0) {
            return;
        }
        ItemStack weapon = source.getDirectEntity() == attacker ? attacker.getMainHandItem() : ItemStack.EMPTY;
        String weaponId = source.getDirectEntity() != null && source.getDirectEntity() != attacker
                ? BuiltInRegistries.ENTITY_TYPE.getKey(source.getDirectEntity().getType()).toString()
                : weapon.isEmpty() ? "" : ContainerTracker.itemId(weapon);
        String key = attacker.getUUID() + "|" + victim.getUUID() + "|" + weaponId;
        Hits hits = HITS.computeIfAbsent(key, k -> new Hits(attacker, victim, weaponId, ContainerTracker.customName(weapon)));
        hits.damage += taken;
        hits.count++;
        hits.last = System.currentTimeMillis();
    }

    public static void death(LivingEntity entity) {
        if (entity instanceof ServerPlayer && !HITS.isEmpty()) {
            HITS.values().removeIf(hits -> {
                if (hits.victim == entity) {
                    flush(hits);
                    return true;
                }
                return false;
            });
        }
    }

    public static void tick(MinecraftServer server) {
        if (HITS.isEmpty()) {
            return;
        }
        long limit = System.currentTimeMillis() - Math.max(1, PlayerLog.config().pvpMergeSeconds) * 1000L;
        Iterator<Hits> iterator = HITS.values().iterator();
        while (iterator.hasNext()) {
            Hits hits = iterator.next();
            if (hits.last < limit || hits.attacker.isRemoved() && hits.victim.isRemoved()) {
                iterator.remove();
                flush(hits);
            }
        }
    }

    private static void flush(Hits hits) {
        LogStore.write("PVP", name(hits.attacker), hits.attacker.getUUID(), dimension(hits.victim), hits.victim.blockPosition(),
                name(hits.victim), String.format(Locale.ROOT, "%.1f", hits.damage), String.valueOf(hits.count), hits.weapon, hits.weaponName,
                String.format(Locale.ROOT, "%.1f", Math.max(0, hits.victim.getHealth())));
    }

    public static InteractionResult useBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide() || !PlayerLog.config().logFire || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            return InteractionResult.PASS;
        }
        String item = ContainerTracker.itemId(stack);
        if (!PlayerLog.config().fireItems.contains(item)) {
            return InteractionResult.PASS;
        }
        BlockPos clicked = hit.getBlockPos();
        String target = BuiltInRegistries.BLOCK.getKey(level.getBlockState(clicked).getBlock()).toString();
        BlockPos pos = target.equals("minecraft:tnt") ? clicked : clicked.relative(hit.getDirection());
        LogStore.write("FIRE", name(serverPlayer), serverPlayer.getUUID(), dimension(serverPlayer), pos, item, target);
        return InteractionResult.PASS;
    }

    public static void chat(ServerPlayer player, String text) {
        if (PlayerLog.config().logChat) {
            LogStore.write("CHAT", name(player), player.getUUID(), dimension(player), player.blockPosition(), shorten(text, 500));
        }
    }

    public static void sign(Player player, BlockPos pos, boolean front, List<FilteredText> lines) {
        if (!PlayerLog.config().logSigns || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        String text = String.join(" | ", lines.stream().map(FilteredText::raw).map(String::strip).toList());
        if (text.replace("|", "").isBlank()) {
            text = "";
        }
        String block = BuiltInRegistries.BLOCK.getKey(serverPlayer.level().getBlockState(pos).getBlock()).toString();
        LogStore.write("SIGN", name(serverPlayer), serverPlayer.getUUID(), dimension(serverPlayer), pos, front ? "front" : "back", text, block);
    }

    public static void book(ServerPlayer player, String title, List<FilteredText> pages) {
        if (!PlayerLog.config().logBooks) {
            return;
        }
        String text = String.join(" ¶ ", pages.stream().map(FilteredText::raw).map(String::strip).filter(page -> !page.isEmpty()).toList());
        LogStore.write("BOOK", name(player), player.getUUID(), dimension(player), player.blockPosition(),
                title == null ? "edit" : "sign", title == null ? "" : title, String.valueOf(pages.size()), shorten(text, 600));
    }

    public static void rename(Player player, ItemStack input, ItemStack result) {
        if (!PlayerLog.config().logRenames || !(player instanceof ServerPlayer serverPlayer) || result.isEmpty()) {
            return;
        }
        Component before = input.get(DataComponents.CUSTOM_NAME);
        Component after = result.get(DataComponents.CUSTOM_NAME);
        String from = before == null ? "" : before.getString();
        String to = after == null ? "" : after.getString();
        if (from.equals(to)) {
            return;
        }
        LogStore.write("RENAME", name(serverPlayer), serverPlayer.getUUID(), dimension(serverPlayer), serverPlayer.blockPosition(),
                ContainerTracker.itemId(result), from, to, String.valueOf(result.getCount()));
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}

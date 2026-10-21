package com.qateralong.playerlog;

import com.qateralong.playerlog.mixin.CompoundContainerAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ContainerTracker {
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    public record Location(String dimension, BlockPos pos, String block) {
    }

    private record Key(Location location, String item, String name) {
    }

    public record Snapshot(int[] slots, Location[] locations, ItemStack[] stacks) {
    }

    private static final class Session {
        AbstractContainerMenu menu;
        final Map<Key, Integer> delta = new LinkedHashMap<>();
    }

    private ContainerTracker() {
    }

    public static Location locate(Container container, int index) {
        while (container instanceof CompoundContainer compound) {
            Container first = ((CompoundContainerAccessor) compound).playerlog$first();
            if (index < first.getContainerSize()) {
                container = first;
            } else {
                index -= first.getContainerSize();
                container = ((CompoundContainerAccessor) compound).playerlog$second();
            }
        }
        if (container instanceof BlockEntity blockEntity && blockEntity.getLevel() != null) {
            return new Location(blockEntity.getLevel().dimension().location().toString(), blockEntity.getBlockPos().immutable(),
                    BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()).toString());
        }
        if (container instanceof Entity entity) {
            return new Location(entity.level().dimension().location().toString(), entity.blockPosition(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        }
        return null;
    }

    public static Snapshot snapshot(AbstractContainerMenu menu) {
        if (!PlayerLog.config().logContainers) {
            return null;
        }
        List<Integer> slots = new ArrayList<>();
        List<Location> locations = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            Location location = locate(slot.container, slot.getContainerSlot());
            if (location != null) {
                slots.add(i);
                locations.add(location);
            }
        }
        if (slots.isEmpty()) {
            return null;
        }
        ItemStack[] stacks = new ItemStack[slots.size()];
        for (int i = 0; i < stacks.length; i++) {
            stacks[i] = menu.slots.get(slots.get(i)).getItem().copy();
        }
        return new Snapshot(slots.stream().mapToInt(Integer::intValue).toArray(), locations.toArray(Location[]::new), stacks);
    }

    public static void compare(AbstractContainerMenu menu, ServerPlayer player, Snapshot snapshot) {
        Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());
        if (session.menu != menu) {
            flush(player);
            session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());
            session.menu = menu;
        }
        for (int i = 0; i < snapshot.slots().length; i++) {
            ItemStack before = snapshot.stacks()[i];
            ItemStack after = menu.slots.get(snapshot.slots()[i]).getItem();
            if (ItemStack.matches(before, after)) {
                continue;
            }
            Location location = snapshot.locations()[i];
            if (ItemStack.isSameItemSameComponents(before, after)) {
                add(session, location, after, after.getCount() - before.getCount());
            } else {
                if (!before.isEmpty()) {
                    add(session, location, before, -before.getCount());
                }
                if (!after.isEmpty()) {
                    add(session, location, after, after.getCount());
                }
            }
        }
    }

    private static void add(Session session, Location location, ItemStack stack, int count) {
        if (count != 0) {
            session.delta.merge(new Key(location, itemId(stack), customName(stack)), count, Integer::sum);
        }
    }

    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static String customName(ItemStack stack) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name == null ? "" : name.getString();
    }

    public static void flush(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) {
            return;
        }
        for (Map.Entry<Key, Integer> entry : session.delta.entrySet()) {
            if (entry.getValue() == 0) {
                continue;
            }
            Key key = entry.getKey();
            LogStore.write("CONTAINER", player.getGameProfile().getName(), player.getUUID(), key.location().dimension(), key.location().pos(),
                    key.location().block(), String.valueOf(entry.getValue()), key.item(), key.name());
        }
    }
}

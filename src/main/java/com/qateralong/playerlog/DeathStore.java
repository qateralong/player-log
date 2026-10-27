package com.qateralong.playerlog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

public final class DeathStore {
    public static final int SIZE = 54;
    private static final Path DIR = LogStore.DIR.resolve("deaths");
    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern VALID = Pattern.compile("[0-9]{8}-[0-9]{6}-[A-Za-z0-9_]{1,16}(-[0-9]+)?");
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Set<String> OPEN = ConcurrentHashMap.newKeySet();

    public record Snapshot(String id, String player, UUID uuid, NonNullList<ItemStack> items, int level, float progress, boolean xpReturned) {
        public int stacks() {
            return (int) this.items.stream().filter(stack -> !stack.isEmpty()).count();
        }
    }

    private DeathStore() {
    }

    public static boolean valid(String id) {
        return VALID.matcher(id).matches();
    }

    private static Path file(String id) {
        return DIR.resolve(id + ".dat");
    }

    public static String save(ServerPlayer player) {
        NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 27; i++) {
            items.set(i, inventory.getItem(9 + i).copy());
        }
        for (int i = 0; i < 9; i++) {
            items.set(27 + i, inventory.getItem(i).copy());
        }
        for (int i = 0; i < ARMOR.length; i++) {
            items.set(36 + i, player.getItemBySlot(ARMOR[i]).copy());
        }
        items.set(40, player.getOffhandItem().copy());
        if (items.stream().allMatch(ItemStack::isEmpty) && player.experienceLevel == 0) {
            return "";
        }
        String base = ZonedDateTime.now(LogStore.zone()).format(ID) + "-" + player.getGameProfile().getName();
        String id = base;
        for (int n = 2; Files.exists(file(id)); n++) {
            id = base + "-" + n;
        }
        Snapshot snapshot = new Snapshot(id, player.getGameProfile().getName(), player.getUUID(), items, player.experienceLevel,
                player.experienceProgress, false);
        return write(snapshot, player.registryAccess()) ? id : "";
    }

    private static boolean write(Snapshot snapshot, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Player", snapshot.player());
        tag.putUUID("Uuid", snapshot.uuid());
        tag.putInt("XpLevel", snapshot.level());
        tag.putFloat("XpP", snapshot.progress());
        tag.putBoolean("XpReturned", snapshot.xpReturned());
        ContainerHelper.saveAllItems(tag, snapshot.items(), true, registries);
        try {
            Files.createDirectories(DIR);
            Path tmp = DIR.resolve(snapshot.id() + ".tmp");
            NbtIo.writeCompressed(tag, tmp);
            Files.move(tmp, file(snapshot.id()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            PlayerLog.LOGGER.error("Failed to save death snapshot {}", snapshot.id(), e);
            return false;
        }
    }

    public static Snapshot load(String id, HolderLookup.Provider registries) {
        if (!valid(id) || !Files.exists(file(id))) {
            return null;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(file(id), NbtAccounter.unlimitedHeap());
            NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag, items, registries);
            return new Snapshot(id, tag.getString("Player"), tag.getUUID("Uuid"), items, tag.getInt("XpLevel"), tag.getFloat("XpP"),
                    tag.getBoolean("XpReturned"));
        } catch (Exception e) {
            PlayerLog.LOGGER.error("Failed to read death snapshot {}", id, e);
            return null;
        }
    }

    public static boolean open(ServerPlayer viewer, String id) {
        Snapshot snapshot = load(id, viewer.registryAccess());
        if (snapshot == null || !OPEN.add(id)) {
            return false;
        }
        SimpleContainer container = new SimpleContainer(SIZE) {
            @Override
            public void stopOpen(Player player) {
                super.stopOpen(player);
                NonNullList<ItemStack> left = NonNullList.withSize(SIZE, ItemStack.EMPTY);
                for (int i = 0; i < SIZE; i++) {
                    left.set(i, this.getItem(i).copy());
                }
                Snapshot current = load(id, viewer.registryAccess());
                write(new Snapshot(id, snapshot.player(), snapshot.uuid(), left, snapshot.level(), snapshot.progress(),
                        current != null && current.xpReturned()), viewer.registryAccess());
                OPEN.remove(id);
                int taken = snapshot.stacks() - (int) left.stream().filter(stack -> !stack.isEmpty()).count();
                if (taken != 0) {
                    LogStore.write("RESTORE", viewer.getGameProfile().getName(), viewer.getUUID(), viewer.level().dimension().location().toString(),
                            viewer.blockPosition(), "take", snapshot.player(), id, String.valueOf(taken));
                }
            }
        };
        for (int i = 0; i < SIZE; i++) {
            container.setItem(i, snapshot.items().get(i).copy());
        }
        viewer.openMenu(new SimpleMenuProvider((syncId, inventory, player) -> ChestMenu.sixRows(syncId, inventory, container),
                Component.literal("Вещи " + snapshot.player() + " · " + id.substring(9, 11) + ":" + id.substring(11, 13) + ":" + id.substring(13, 15))));
        return true;
    }

    public static boolean busy(String id) {
        return OPEN.contains(id);
    }

    public static int giveBack(ServerPlayer target, String id) {
        Snapshot snapshot = load(id, target.registryAccess());
        if (snapshot == null) {
            return -1;
        }
        Inventory inventory = target.getInventory();
        int given = 0;
        for (int i = 0; i < SIZE; i++) {
            ItemStack stack = snapshot.items().get(i);
            if (stack.isEmpty()) {
                continue;
            }
            given++;
            int slot = i < 27 ? 9 + i : i < 36 ? i - 27 : -1;
            if (slot >= 0 && inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, stack);
            } else if (i >= 36 && i < 40 && target.getItemBySlot(ARMOR[i - 36]).isEmpty()) {
                target.setItemSlot(ARMOR[i - 36], stack);
            } else if (i == 40 && target.getOffhandItem().isEmpty()) {
                target.setItemSlot(EquipmentSlot.OFFHAND, stack);
            } else if (!inventory.add(stack) || !stack.isEmpty()) {
                target.drop(stack, false);
            }
            snapshot.items().set(i, ItemStack.EMPTY);
        }
        boolean xp = !snapshot.xpReturned() && snapshot.level() > target.experienceLevel;
        if (xp) {
            target.setExperienceLevels(snapshot.level());
            target.experienceProgress = snapshot.progress();
        }
        write(new Snapshot(id, snapshot.player(), snapshot.uuid(), snapshot.items(), snapshot.level(), snapshot.progress(), true),
                target.registryAccess());
        target.inventoryMenu.broadcastChanges();
        return given;
    }

    public static Map<String, Integer> summary(Snapshot snapshot) {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (ItemStack stack : snapshot.items()) {
            if (!stack.isEmpty()) {
                result.merge(ContainerTracker.itemId(stack), stack.getCount(), Integer::sum);
            }
        }
        return result;
    }

    public static void cleanup(int retentionDays) {
        if (retentionDays <= 0 || !Files.isDirectory(DIR)) {
            return;
        }
        String oldest = LocalDate.now(LogStore.zone()).minusDays(retentionDays).format(DateTimeFormatter.BASIC_ISO_DATE);
        try (Stream<Path> stream = Files.list(DIR)) {
            for (Path path : stream.toList()) {
                if (path.getFileName().toString().compareTo(oldest) < 0) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ignored) {
        }
    }
}

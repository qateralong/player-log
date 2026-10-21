package com.qateralong.playerlog;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

public final class EventTracker {
    private static final long MERGE_MILLIS = 3000L;
    private static final double MERGE_DISTANCE = 64.0;
    private static final List<Pending> PENDING = new ArrayList<>();

    private static final class Pending {
        final ServerLevel level;
        final String source;
        final Vec3 pos;
        final Set<String> causes = new LinkedHashSet<>();
        UUID causeId;
        int count;
        long last;
        String players;

        Pending(ServerLevel level, String source, Vec3 pos) {
            this.level = level;
            this.source = source;
            this.pos = pos;
        }
    }

    private EventTracker() {
    }

    private static String typeId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static Player cause(Entity source, DamageSource damage) {
        if (damage != null && damage.getEntity() instanceof Player player) {
            return player;
        }
        if (source instanceof PrimedTnt tnt && tnt.getOwner() instanceof Player player) {
            return player;
        }
        if (source instanceof Projectile projectile && projectile.getOwner() instanceof Player player) {
            return player;
        }
        return null;
    }

    public static void explosion(ServerLevel level, Entity source, DamageSource damage, double x, double y, double z) {
        String kind = source == null ? "block" : typeId(source);
        if (!PlayerLog.config().logExplosions.contains(kind)) {
            return;
        }
        Vec3 pos = new Vec3(x, y, z);
        Player cause = cause(source, damage);
        long now = System.currentTimeMillis();
        Pending pending = null;
        for (Pending candidate : PENDING) {
            if (candidate.level == level && candidate.source.equals(kind) && candidate.pos.distanceTo(pos) <= MERGE_DISTANCE) {
                pending = candidate;
                break;
            }
        }
        if (pending == null) {
            pending = new Pending(level, kind, pos);
            pending.players = players(level.getServer(), level, pos);
            PENDING.add(pending);
        }
        pending.count++;
        pending.last = now;
        if (cause != null) {
            pending.causes.add(cause.getGameProfile().getName());
            if (pending.causeId == null) {
                pending.causeId = cause.getUUID();
            }
        }
    }

    public static void spawn(ServerLevel level, Entity entity) {
        String kind = typeId(entity);
        if (!PlayerLog.config().logSpawns.contains(kind)) {
            return;
        }
        Player nearest = level.getNearestPlayer(entity, 16.0);
        String name = nearest instanceof ServerPlayer player ? player.getGameProfile().getName() : "-";
        LogStore.write("EVENT", name, nearest == null ? null : nearest.getUUID(), level.dimension().location().toString(), entity.blockPosition(),
                "spawn", kind, "1", nearest == null ? "" : "рядом " + name, players(level.getServer(), level, entity.position()));
    }

    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Pending> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            if (now - pending.last < MERGE_MILLIS) {
                continue;
            }
            iterator.remove();
            String causes = String.join(", ", pending.causes);
            String name = pending.causes.isEmpty() ? "-" : pending.causes.iterator().next();
            LogStore.write("EVENT", name, pending.causeId, pending.level.dimension().location().toString(), BlockPos.containing(pending.pos),
                    "explosion", pending.source, String.valueOf(pending.count), causes, pending.players);
        }
    }

    public static String players(MinecraftServer server, ServerLevel level, Vec3 pos) {
        Map<ServerPlayer, Double> distances = new LinkedHashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            distances.put(player, player.level() == level ? player.position().distanceTo(pos) : Double.MAX_VALUE);
        }
        List<String> parts = new ArrayList<>();
        distances.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(entry -> {
            ServerPlayer player = entry.getKey();
            BlockPos at = player.blockPosition();
            String dim = player.level().dimension().location().getPath();
            parts.add(player.getGameProfile().getName() + " " + (dim.equals("overworld") ? "" : dim + " ") + at.getX() + " " + at.getY() + " " + at.getZ()
                    + (entry.getValue() == Double.MAX_VALUE ? "" : String.format(Locale.ROOT, " [%.0f бл.]", entry.getValue())));
        });
        return String.join("; ", parts);
    }
}

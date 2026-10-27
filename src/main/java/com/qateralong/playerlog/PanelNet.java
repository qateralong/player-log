package com.qateralong.playerlog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class PanelNet {
    private static final int PAGE = 40;

    private PanelNet() {
    }

    public static void init() {
        PayloadTypeRegistry.playC2S().register(PanelPayload.TYPE, PanelPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PanelPayload.TYPE, PanelPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PanelPayload.TYPE, (payload, context) -> handle(context.player(), payload.json()));
    }

    private static void handle(ServerPlayer player, String json) {
        if (!player.hasPermissions(2)) {
            if (ServerPlayNetworking.canSend(player, PanelPayload.TYPE)) {
                JsonObject denied = new JsonObject();
                denied.addProperty("denied", true);
                try {
                    denied.addProperty("rid", JsonParser.parseString(json).getAsJsonObject().get("rid").getAsInt());
                } catch (RuntimeException ignored) {
                }
                ServerPlayNetworking.send(player, new PanelPayload(denied.toString()));
            }
            return;
        }
        JsonObject request;
        try {
            request = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String op = string(request, "op");
        switch (op) {
            case "search" -> search(player, request);
            case "deaths" -> deaths(player, request);
            case "death" -> death(player, request);
            default -> {
            }
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static JsonObject reply(JsonObject request) {
        JsonObject reply = new JsonObject();
        reply.addProperty("op", string(request, "op"));
        reply.addProperty("rid", integer(request, "rid", 0));
        return reply;
    }

    private static void send(ServerPlayer player, JsonObject reply) {
        if (!player.isRemoved() && ServerPlayNetworking.canSend(player, PanelPayload.TYPE)) {
            ServerPlayNetworking.send(player, new PanelPayload(reply.toString()));
        }
    }

    private static void search(ServerPlayer player, JsonObject request) {
        String name = string(request, "player").trim();
        String kind = string(request, "type").toLowerCase(Locale.ROOT);
        int page = Math.max(1, integer(request, "page", 1));
        int days = Math.max(1, integer(request, "days", PlayerLog.config().searchDays));
        int radius = integer(request, "radius", 0);
        Predicate<LogStore.Entry> filter = LogCommand.filter(name, kind.isEmpty() ? "all" : kind);
        if (request.has("at")) {
            JsonArray at = request.getAsJsonArray("at");
            BlockPos pos = new BlockPos(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt());
            List<BlockPos> positions = PlayerLog.containerPositions(player.level(), pos);
            String dimension = player.level().dimension().location().toString();
            filter = filter.and(entry -> entry.dimension().equals(dimension)
                    && positions.stream().anyMatch(p -> p.getX() == entry.x() && p.getY() == entry.y() && p.getZ() == entry.z()));
        } else if (radius > 0) {
            String dimension = player.level().dimension().location().toString();
            double x = player.getX();
            double z = player.getZ();
            long limit = (long) radius * radius;
            filter = filter.and(entry -> entry.dimension().equals(dimension)
                    && (entry.x() - x) * (entry.x() - x) + (entry.z() - z) * (entry.z() - z) <= limit);
        }
        JsonObject reply = reply(request);
        LogStore.search(filter, days, (page - 1) * PAGE, PAGE + 1).thenAcceptAsync(list -> {
            JsonArray rows = new JsonArray();
            for (LogStore.Entry entry : list.subList(0, Math.min(PAGE, list.size()))) {
                JsonObject row = new JsonObject();
                row.addProperty("time", entry.time());
                row.addProperty("type", entry.type());
                row.addProperty("player", entry.player());
                row.addProperty("dim", entry.dimension());
                row.addProperty("x", entry.x());
                row.addProperty("y", entry.y());
                row.addProperty("z", entry.z());
                row.addProperty("text", Component.Serializer.toJson(LogCommand.line(entry, true), player.registryAccess()));
                if (entry.type().equals("DEATH") && DeathStore.valid(entry.data(5))) {
                    row.addProperty("death", entry.data(5));
                }
                rows.add(row);
            }
            reply.add("rows", rows);
            reply.addProperty("page", page);
            reply.addProperty("more", list.size() > PAGE);
            send(player, reply);
        }, player.server);
    }

    private static void deaths(ServerPlayer player, JsonObject request) {
        String name = string(request, "player").trim();
        int page = Math.max(1, integer(request, "page", 1));
        Predicate<LogStore.Entry> filter = entry -> entry.type().equals("DEATH") && DeathStore.valid(entry.data(5))
                && (name.isEmpty() || entry.player().equalsIgnoreCase(name));
        JsonObject reply = reply(request);
        LogStore.search(filter, Math.max(1, PlayerLog.config().retentionDays), (page - 1) * PAGE, PAGE + 1).thenAcceptAsync(list -> {
            JsonArray rows = new JsonArray();
            for (LogStore.Entry entry : list.subList(0, Math.min(PAGE, list.size()))) {
                DeathStore.Snapshot snapshot = DeathStore.load(entry.data(5), player.registryAccess());
                JsonObject row = new JsonObject();
                row.addProperty("id", entry.data(5));
                row.addProperty("time", entry.time());
                row.addProperty("player", entry.player());
                row.addProperty("cause", entry.data(0));
                row.addProperty("dim", entry.dimension());
                row.addProperty("x", entry.x());
                row.addProperty("y", entry.y());
                row.addProperty("z", entry.z());
                row.addProperty("stacks", snapshot == null ? 0 : snapshot.stacks());
                row.addProperty("level", snapshot == null ? 0 : snapshot.level());
                row.addProperty("xpReturned", snapshot != null && snapshot.xpReturned());
                rows.add(row);
            }
            reply.add("rows", rows);
            reply.addProperty("page", page);
            reply.addProperty("more", list.size() > PAGE);
            send(player, reply);
        }, player.server);
    }

    private static void death(ServerPlayer player, JsonObject request) {
        String id = string(request, "id");
        JsonObject reply = reply(request);
        DeathStore.Snapshot snapshot = DeathStore.load(id, player.registryAccess());
        reply.addProperty("id", id);
        if (snapshot == null) {
            reply.addProperty("missing", true);
            send(player, reply);
            return;
        }
        reply.addProperty("player", snapshot.player());
        reply.addProperty("level", snapshot.level());
        reply.addProperty("xpReturned", snapshot.xpReturned());
        reply.addProperty("busy", DeathStore.busy(id));
        JsonArray items = new JsonArray();
        for (int i = 0; i < snapshot.items().size(); i++) {
            ItemStack stack = snapshot.items().get(i);
            if (!stack.isEmpty()) {
                JsonObject item = new JsonObject();
                item.addProperty("slot", i);
                item.addProperty("nbt", stack.save(player.registryAccess()).toString());
                items.add(item);
            }
        }
        reply.add("items", items);
        send(player, reply);
    }
}

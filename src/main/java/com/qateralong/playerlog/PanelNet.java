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
            case "track" -> track(player, request);
            case "alts" -> alts(player, request);
            case "mods" -> mods(player, request);
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

    private static void track(ServerPlayer player, JsonObject request) {
        String name = string(request, "player").trim();
        long now = System.currentTimeMillis() / 1000L;
        long to = request.has("to") ? request.get("to").getAsLong() : now;
        long from = request.has("from") ? request.get("from").getAsLong() : to - 3600L;
        int max = Math.max(100, Math.min(20000, integer(request, "max", 6000)));
        JsonObject reply = reply(request);
        reply.addProperty("player", name);
        reply.addProperty("from", from);
        reply.addProperty("to", to);
        TrackStore.query(name, from, to, max).thenAcceptAsync(points -> {
            JsonArray dims = new JsonArray();
            List<String> known = new java.util.ArrayList<>();
            JsonArray rows = new JsonArray();
            for (TrackStore.Point point : points) {
                int dim = known.indexOf(point.dimension());
                if (dim < 0) {
                    dim = known.size();
                    known.add(point.dimension());
                    dims.add(point.dimension());
                }
                JsonArray row = new JsonArray();
                row.add(point.time());
                row.add(point.x());
                row.add(point.y());
                row.add(point.z());
                row.add(point.flag());
                row.add(dim);
                rows.add(row);
            }
            reply.add("dims", dims);
            reply.add("points", rows);
            send(player, reply);
        }, player.server);
    }

    private static void alts(ServerPlayer player, JsonObject request) {
        String name = string(request, "player").trim();
        JsonObject reply = reply(request);
        reply.addProperty("addresses", Addresses.addressCount(name));
        JsonArray rows = new JsonArray();
        for (Addresses.Account account : Addresses.alts(name)) {
            JsonObject row = new JsonObject();
            row.addProperty("name", account.name());
            row.addProperty("uuid", account.uuid());
            row.addProperty("last", account.last());
            row.addProperty("shared", account.shared());
            row.addProperty("crowded", account.crowded());
            rows.add(row);
        }
        reply.add("rows", rows);
        send(player, reply);
    }

    private static void mods(ServerPlayer player, JsonObject request) {
        String name = string(request, "player").trim();
        JsonObject reply = reply(request);
        reply.addProperty("player", name);
        LogStore.search(entry -> entry.type().equals("JOIN") && entry.player().equalsIgnoreCase(name) && !entry.data(2).equals("="), 3650, 0, 1)
                .thenAcceptAsync(list -> {
                    if (list.isEmpty() || list.get(0).data(2).isEmpty()) {
                        reply.addProperty("missing", "report");
                        send(player, reply);
                        return;
                    }
                    LogStore.Entry entry = list.get(0);
                    reply.addProperty("time", entry.time());
                    reply.addProperty("brand", entry.data(0));
                    JsonArray mods = new JsonArray();
                    for (String mod : entry.data(2).split(", ")) {
                        int at = mod.lastIndexOf('@');
                        JsonObject row = new JsonObject();
                        row.addProperty("id", at > 0 ? mod.substring(0, at) : mod);
                        row.addProperty("name", "");
                        row.addProperty("version", at > 0 ? mod.substring(at + 1) : "");
                        row.addProperty("parent", "");
                        row.addProperty("hash", "");
                        mods.add(row);
                    }
                    JsonArray packs = new JsonArray();
                    if (!entry.data(3).isEmpty() && !entry.data(3).equals("=")) {
                        for (String pack : entry.data(3).split(", ")) {
                            JsonObject row = new JsonObject();
                            row.addProperty("id", pack);
                            row.addProperty("name", pack);
                            row.addProperty("version", "");
                            row.addProperty("parent", "");
                            row.addProperty("hash", "");
                            packs.add(row);
                        }
                    }
                    reply.add("mods", mods);
                    reply.add("packs", packs);
                    reply.add("violations", new JsonArray());
                    reply.addProperty("fromLog", true);
                    send(player, reply);
                }, player.server);
    }
}

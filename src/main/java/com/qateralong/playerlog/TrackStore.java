package com.qateralong.playerlog;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class TrackStore {
    public static final Path DIR = LogStore.DIR.resolve("track");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Map<UUID, long[]> LAST = new ConcurrentHashMap<>();
    private static BufferedWriter writer;
    private static String writerDay = "";

    public record Point(long time, String dimension, int x, int y, int z, int flag) {
    }

    private TrackStore() {
    }

    public static void tick(MinecraftServer server) {
        PlayerLog.Config config = PlayerLog.config();
        if (!config.logTrack || server.getTickCount() % (Math.max(1, config.trackSeconds) * 20) != 0) {
            return;
        }
        long now = System.currentTimeMillis() / 1000L;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            long[] last = LAST.get(player.getUUID());
            int dim = player.level().dimension().location().toString().hashCode();
            int x = player.getBlockX();
            int y = player.getBlockY();
            int z = player.getBlockZ();
            if (last != null && last[4] == dim && Math.abs(last[1] - x) + Math.abs(last[2] - y) + Math.abs(last[3] - z) < 2 && now - last[0] < 60) {
                continue;
            }
            write(player, now, 0);
        }
    }

    public static void join(ServerPlayer player) {
        if (PlayerLog.config().logTrack) {
            write(player, System.currentTimeMillis() / 1000L, 1);
        }
    }

    public static void leave(ServerPlayer player) {
        if (PlayerLog.config().logTrack && LAST.containsKey(player.getUUID())) {
            write(player, System.currentTimeMillis() / 1000L, 2);
            LAST.remove(player.getUUID());
        }
    }

    private static void write(ServerPlayer player, long now, int flag) {
        int x = player.getBlockX();
        int y = player.getBlockY();
        int z = player.getBlockZ();
        String dimension = player.level().dimension().location().toString();
        LAST.put(player.getUUID(), new long[]{now, x, y, z, dimension.hashCode()});
        String line = now + "\t" + player.getUUID() + "\t" + player.getGameProfile().getName() + "\t" + dimension + "\t" + x + "\t" + y + "\t" + z + "\t" + flag
                + (player.isSpectator() ? "\ts" : "");
        String day = LocalDate.ofInstant(Instant.ofEpochSecond(now), LogStore.zone()).format(DAY);
        LogStore.submit(() -> append(day, line));
    }

    private static void append(String day, String line) {
        try {
            if (writer == null || !day.equals(writerDay)) {
                if (writer != null) {
                    writer.close();
                }
                Files.createDirectories(DIR);
                writer = Files.newBufferedWriter(DIR.resolve(day + ".tsv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                boolean changed = !writerDay.isEmpty();
                writerDay = day;
                if (changed) {
                    LogStore.compressOld(DIR, writerDay);
                }
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            PlayerLog.LOGGER.error("Failed to write player track", e);
        }
    }

    static void close() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
        }
        writer = null;
        writerDay = "";
    }

    public static void cleanup(int retentionDays) {
        LogStore.cleanup(DIR, retentionDays, () -> writerDay);
    }

    public static CompletableFuture<List<Point>> query(String player, long from, long to, int max) {
        CompletableFuture<List<Point>> result = new CompletableFuture<>();
        LogStore.submit(() -> {
            try {
                result.complete(read(player, from, to, max));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private static List<Point> read(String player, long from, long to, int max) {
        List<Point> points = new ArrayList<>();
        LocalDate first = LocalDate.ofInstant(Instant.ofEpochSecond(from), LogStore.zone()).minusDays(1);
        LocalDate last = LocalDate.ofInstant(Instant.ofEpochSecond(to), LogStore.zone()).plusDays(1);
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            String name = day.format(DAY);
            for (Path file : List.of(DIR.resolve(name + ".tsv.gz"), DIR.resolve(name + ".tsv"))) {
                if (!Files.exists(file)) {
                    continue;
                }
                List<String> lines;
                try {
                    lines = LogStore.read(file);
                } catch (IOException e) {
                    continue;
                }
                for (String line : lines) {
                    String[] parts = line.split("\t", -1);
                    if (parts.length < 8 || !(parts[1].equalsIgnoreCase(player) || parts[2].equalsIgnoreCase(player))) {
                        continue;
                    }
                    try {
                        long time = Long.parseLong(parts[0]);
                        if (time < from || time > to) {
                            continue;
                        }
                        points.add(new Point(time, parts[3], Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), Integer.parseInt(parts[6]),
                                Integer.parseInt(parts[7])));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        points.sort(java.util.Comparator.comparingLong(Point::time));
        if (points.size() <= max) {
            return points;
        }
        List<Point> reduced = new ArrayList<>(max + 16);
        double step = points.size() / (double) max;
        double next = 0;
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (point.flag() != 0 || i >= next || i == points.size() - 1) {
                reduced.add(point);
                if (i >= next) {
                    next += step;
                }
            }
        }
        return reduced;
    }
}

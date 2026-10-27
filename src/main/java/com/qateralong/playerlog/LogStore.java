package com.qateralong.playerlog;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

public final class LogStore {
    public static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("playerlog");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static ExecutorService worker = newWorker();
    private static BufferedWriter writer;
    private static String writerDay = "";

    public record Entry(String time, String type, String player, String uuid, String dimension, int x, int y, int z, String[] data) {
        public String data(int index) {
            return index < this.data.length ? this.data[index] : "";
        }

        static Entry parse(String line) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 8) {
                return null;
            }
            try {
                return new Entry(parts[0], parts[1], parts[2], parts[3], parts[4], Integer.parseInt(parts[5]), Integer.parseInt(parts[6]),
                        Integer.parseInt(parts[7]), Arrays.copyOfRange(parts, 8, parts.length));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private LogStore() {
    }

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PlayerLog");
            thread.setDaemon(true);
            return thread;
        });
    }

    static ZoneId zone() {
        String id = PlayerLog.config().timeZone;
        try {
            return id == null || id.isBlank() ? ZoneId.systemDefault() : ZoneId.of(id);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    public static void write(String type, String player, UUID uuid, String dimension, BlockPos pos, String... data) {
        ZonedDateTime now = ZonedDateTime.now(zone());
        StringBuilder line = new StringBuilder();
        line.append(now.format(TIME)).append('\t').append(type).append('\t').append(clean(player)).append('\t')
                .append(uuid == null ? "" : uuid.toString()).append('\t').append(clean(dimension)).append('\t')
                .append(pos.getX()).append('\t').append(pos.getY()).append('\t').append(pos.getZ());
        for (String value : data) {
            line.append('\t').append(clean(value));
        }
        String day = now.format(DAY);
        String text = line.toString();
        worker.execute(() -> append(day, text));
    }

    private static void append(String day, String line) {
        try {
            if (writer == null || !day.equals(writerDay)) {
                if (writer != null) {
                    writer.close();
                }
                Files.createDirectories(DIR);
                writer = Files.newBufferedWriter(DIR.resolve(day + ".tsv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writerDay = day;
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            PlayerLog.LOGGER.error("Failed to write player log", e);
        }
    }

    public static CompletableFuture<List<Entry>> search(Predicate<Entry> filter, int days, int skip, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<Entry> result = new ArrayList<>();
            int skipped = 0;
            for (Path file : files(days)) {
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                Collections.reverse(lines);
                for (String line : lines) {
                    Entry entry = Entry.parse(line);
                    if (entry == null || !filter.test(entry)) {
                        continue;
                    }
                    if (skipped < skip) {
                        skipped++;
                        continue;
                    }
                    result.add(entry);
                    if (result.size() >= limit) {
                        return result;
                    }
                }
            }
            return result;
        }, worker);
    }

    private static List<Path> files(int days) {
        String oldest = LocalDate.now(zone()).minusDays(Math.max(0, days - 1)).format(DAY);
        try (Stream<Path> stream = Files.list(DIR)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".tsv"))
                    .filter(path -> path.getFileName().toString().compareTo(oldest) >= 0)
                    .sorted(Collections.reverseOrder())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static void cleanup(int retentionDays) {
        if (retentionDays <= 0) {
            return;
        }
        worker.execute(() -> {
            String oldest = LocalDate.now(zone()).minusDays(retentionDays).format(DAY);
            try (Stream<Path> stream = Files.list(DIR)) {
                for (Path path : stream.toList()) {
                    String name = path.getFileName().toString();
                    if (name.endsWith(".tsv") && name.compareTo(oldest) < 0) {
                        Files.deleteIfExists(path);
                    }
                }
            } catch (IOException ignored) {
            }
        });
    }

    public static void shutdown() {
        worker.execute(() -> {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException ignored) {
            }
            writer = null;
            writerDay = "";
        });
        worker.shutdown();
        try {
            worker.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        worker = newWorker();
    }
}

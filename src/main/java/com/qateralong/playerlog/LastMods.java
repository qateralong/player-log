package com.qateralong.playerlog;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class LastMods {
    private static final Path FILE = LogStore.DIR.resolve("lastmods.json");
    private static final Gson GSON = new Gson();
    private static Map<String, String> hashes;

    private LastMods() {
    }

    private static synchronized Map<String, String> map() {
        if (hashes == null) {
            hashes = new ConcurrentHashMap<>();
            if (Files.exists(FILE)) {
                try (Reader reader = Files.newBufferedReader(FILE)) {
                    Map<String, String> loaded = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {
                    }.getType());
                    if (loaded != null) {
                        hashes.putAll(loaded);
                    }
                } catch (Exception e) {
                    PlayerLog.LOGGER.warn("Failed to read lastmods.json", e);
                }
            }
        }
        return hashes;
    }

    static String get(UUID player) {
        return map().get(player.toString());
    }

    static void put(UUID player, String hash) {
        map().put(player.toString(), hash);
        String json = GSON.toJson(map());
        LogStore.submit(() -> {
            try {
                Files.createDirectories(FILE.getParent());
                try (Writer writer = Files.newBufferedWriter(FILE)) {
                    writer.write(json);
                }
            } catch (Exception e) {
                PlayerLog.LOGGER.warn("Failed to write lastmods.json", e);
            }
        });
    }
}

package com.qateralong.playerlog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

public final class Addresses {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = LogStore.DIR.resolve("addresses.json");
    private static final Path SALT = FabricLoader.getInstance().getConfigDir().resolve("playerlog_salt.txt");
    private static Map<String, Map<String, Seen>> index;
    private static String salt;

    public record Seen(String name, long first, long last, int joins) {
    }

    public record Account(String name, String uuid, long last, int shared, boolean crowded) {
    }

    private Addresses() {
    }

    private static synchronized Map<String, Map<String, Seen>> index() {
        if (index == null) {
            index = new LinkedHashMap<>();
            if (Files.exists(FILE)) {
                try (Reader reader = Files.newBufferedReader(FILE)) {
                    Map<String, Map<String, Seen>> loaded = GSON.fromJson(reader, new TypeToken<Map<String, Map<String, Seen>>>() {
                    }.getType());
                    if (loaded != null) {
                        index.putAll(loaded);
                    }
                } catch (Exception e) {
                    PlayerLog.LOGGER.warn("Failed to read {}", FILE, e);
                }
            }
        }
        return index;
    }

    private static synchronized String salt() {
        if (salt == null) {
            try {
                if (Files.exists(SALT)) {
                    salt = Files.readString(SALT, StandardCharsets.UTF_8).trim();
                }
                if (salt == null || salt.isEmpty()) {
                    byte[] bytes = new byte[24];
                    new SecureRandom().nextBytes(bytes);
                    salt = HexFormat.of().formatHex(bytes);
                    Files.writeString(SALT, salt, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                salt = "playerlog";
            }
        }
        return salt;
    }

    static String host(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            return inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString();
        }
        String text = String.valueOf(address);
        int slash = text.indexOf('/');
        int colon = text.lastIndexOf(':');
        return text.substring(slash + 1, colon > slash ? colon : text.length());
    }

    static String hash(String host) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((salt() + "|" + host).getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (Exception e) {
            return "";
        }
    }

    public static String join(ServerPlayer player) {
        if (!PlayerLog.config().logAddresses) {
            return "";
        }
        String hash = hash(host(player.connection.getRemoteAddress()));
        if (hash.isEmpty()) {
            return "";
        }
        String uuid = player.getUUID().toString();
        String name = player.getGameProfile().getName();
        long now = System.currentTimeMillis() / 1000L;
        synchronized (Addresses.class) {
            Map<String, Seen> accounts = index().computeIfAbsent(hash, key -> new LinkedHashMap<>());
            Seen old = accounts.get(uuid);
            accounts.put(uuid, new Seen(name, old == null ? now : old.first(), now, old == null ? 1 : old.joins() + 1));
            String json = GSON.toJson(index);
            LogStore.submit(() -> {
                try {
                    Files.createDirectories(FILE.getParent());
                    Path tmp = FILE.resolveSibling("addresses.json.tmp");
                    Files.writeString(tmp, json, StandardCharsets.UTF_8);
                    Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    PlayerLog.LOGGER.warn("Failed to write {}", FILE, e);
                }
            });
        }
        return hash;
    }

    public static synchronized List<Account> alts(String player) {
        Map<String, Account> found = new LinkedHashMap<>();
        int limit = Math.max(2, PlayerLog.config().sharedAddressLimit);
        for (Map<String, Seen> accounts : index().values()) {
            boolean mine = accounts.entrySet().stream().anyMatch(entry -> entry.getKey().equalsIgnoreCase(player) || entry.getValue().name().equalsIgnoreCase(player));
            if (!mine) {
                continue;
            }
            boolean crowded = accounts.size() > limit;
            for (Map.Entry<String, Seen> entry : accounts.entrySet()) {
                Seen seen = entry.getValue();
                if (entry.getKey().equalsIgnoreCase(player) || seen.name().equalsIgnoreCase(player)) {
                    continue;
                }
                Account previous = found.get(entry.getKey());
                if (previous == null || !crowded && previous.crowded()) {
                    found.put(entry.getKey(), new Account(seen.name(), entry.getKey(), seen.last(), accounts.size(), crowded));
                } else {
                    found.put(entry.getKey(), new Account(seen.name(), entry.getKey(), Math.max(seen.last(), previous.last()), previous.shared(), previous.crowded() && crowded));
                }
            }
        }
        List<Account> result = new ArrayList<>(found.values());
        result.sort(java.util.Comparator.comparing(Account::crowded).thenComparing(account -> -account.last()));
        return result;
    }

    public static synchronized int addressCount(String player) {
        int count = 0;
        for (Map<String, Seen> accounts : index().values()) {
            if (accounts.entrySet().stream().anyMatch(entry -> entry.getKey().equalsIgnoreCase(player) || entry.getValue().name().equalsIgnoreCase(player))) {
                count++;
            }
        }
        return count;
    }
}

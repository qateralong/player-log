package com.qateralong.playerlog;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class LogCommand {
    private static final int PAGE = 10;
    private static final List<String> TYPES = List.of("all", "containers", "joins", "deaths", "kills", "pvp", "fire", "chat", "texts", "events", "pickups",
            "commands", "restores");
    private static final List<String> TEXTS = List.of("SIGN", "BOOK", "RENAME");
    private static final SuggestionProvider<CommandSourceStack> PLAYERS =
            (context, builder) -> SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder);

    private LogCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : List.of("playerlog", "plog")) {
            dispatcher.register(build(root));
        }
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> name() {
        return Commands.argument("name", StringArgumentType.word()).suggests(PLAYERS);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> page() {
        return Commands.argument("page", IntegerArgumentType.integer(1, 10000));
    }

    private static int page(CommandContext<CommandSourceStack> context) {
        try {
            return IntegerArgumentType.getInteger(context, "page");
        } catch (IllegalArgumentException e) {
            return 1;
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String root) {
        return Commands.literal(root)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("inspect").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    boolean on = PlayerLog.INSPECTORS.add(player.getUUID());
                    if (!on) {
                        PlayerLog.INSPECTORS.remove(player.getUUID());
                    }
                    context.getSource().sendSuccess(() -> Component.literal(on
                            ? "Режим проверки включён: ПКМ по сундуку покажет его историю. Выключить: /" + root + " inspect"
                            : "Режим проверки выключен").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
                    return 1;
                }))
                .then(Commands.literal("chest")
                        .executes(context -> chestLooked(context, 1))
                        .then(page().executes(context -> chestLooked(context, page(context))))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> chestAt(context, 1))
                                .then(page().executes(context -> chestAt(context, page(context))))))
                .then(Commands.literal("player").then(name()
                        .executes(context -> player(context, "all", 1))
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(TYPES, builder))
                                .executes(context -> player(context, StringArgumentType.getString(context, "type"), 1))
                                .then(page().executes(context -> player(context, StringArgumentType.getString(context, "type"), page(context)))))))
                .then(Commands.literal("joins").then(name()
                        .executes(context -> player(context, "joins", 1))
                        .then(page().executes(context -> player(context, "joins", page(context))))))
                .then(Commands.literal("deaths").then(name()
                        .executes(context -> player(context, "deaths", 1))
                        .then(page().executes(context -> player(context, "deaths", page(context))))))
                .then(Commands.literal("mods").then(name().executes(LogCommand::mods)))
                .then(Commands.literal("alts").then(name().executes(LogCommand::alts)))
                .then(Commands.literal("items").then(Commands.argument("id", StringArgumentType.string()).executes(LogCommand::items)))
                .then(Commands.literal("return").then(Commands.argument("id", StringArgumentType.string()).executes(LogCommand::giveBack)))
                .then(Commands.literal("events")
                        .executes(context -> events(context, 1))
                        .then(page().executes(context -> events(context, page(context)))))
                .then(Commands.literal("reload").executes(context -> {
                    PlayerLog.reload();
                    context.getSource().sendSuccess(() -> Component.literal("Конфиг Player Log перезагружен"), true);
                    return 1;
                }));
    }

    private static int chestLooked(CommandContext<CommandSourceStack> context, int page) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        HitResult hit = player.pick(player.blockInteractionRange() + 1.0, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            context.getSource().sendFailure(Component.literal("Посмотрите на сундук или укажите координаты"));
            return 0;
        }
        showContainer(context.getSource(), player.level().dimension().location().toString(),
                PlayerLog.containerPositions(player.level(), blockHit.getBlockPos()), page);
        return 1;
    }

    private static int chestAt(CommandContext<CommandSourceStack> context, int page) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        showContainer(context.getSource(), context.getSource().getLevel().dimension().location().toString(),
                PlayerLog.containerPositions(context.getSource().getLevel(), pos), page);
        return 1;
    }

    public static void showContainer(CommandSourceStack source, String dimension, List<BlockPos> positions, int page) {
        BlockPos first = positions.get(0);
        String title = "История " + first.getX() + " " + first.getY() + " " + first.getZ() + (positions.size() > 1 ? " (двойной сундук)" : "");
        String command = "/plog chest " + first.getX() + " " + first.getY() + " " + first.getZ();
        Predicate<LogStore.Entry> filter = entry -> (entry.type().equals("CONTAINER") || entry.type().equals("BREAK"))
                && entry.dimension().equals(dimension) && positions.stream().anyMatch(pos -> pos.getX() == entry.x() && pos.getY() == entry.y() && pos.getZ() == entry.z());
        show(source, title, command, filter, page, false);
    }

    private static int player(CommandContext<CommandSourceStack> context, String type, int page) {
        String name = StringArgumentType.getString(context, "name");
        String kind = type.toLowerCase(Locale.ROOT);
        if (!TYPES.contains(kind)) {
            context.getSource().sendFailure(Component.literal("Тип: " + String.join(", ", TYPES)));
            return 0;
        }
        Predicate<LogStore.Entry> filter = filter(name, kind);
        String title = switch (kind) {
            case "containers" -> "Сундуки: " + name;
            case "joins" -> "Входы и выходы: " + name;
            case "deaths" -> "Смерти и убийства: " + name;
            case "kills" -> "Убийства: " + name;
            case "pickups" -> "Подобранные чужие вещи: " + name;
            case "commands" -> "Команды: " + name;
            case "restores" -> "Выдача вещей из снимков: " + name;
            case "events" -> "События рядом с " + name;
            case "pvp" -> "PvP: " + name;
            case "fire" -> "Огонь, лава и взрывчатка: " + name;
            case "chat" -> "Чат: " + name;
            case "texts" -> "Таблички, книги, переименования: " + name;
            default -> "Журнал: " + name;
        };
        show(context.getSource(), title, "/plog player " + name + " " + kind, filter, page, true);
        return 1;
    }

    static Predicate<LogStore.Entry> filter(String name, String kind) {
        if (!name.isEmpty()) {
            return entry -> switch (kind) {
                case "containers" -> entry.player().equalsIgnoreCase(name) && (entry.type().equals("CONTAINER") || entry.type().equals("BREAK"));
                case "joins" -> entry.player().equalsIgnoreCase(name) && (entry.type().equals("JOIN") || entry.type().equals("LEAVE"));
                case "deaths" -> (entry.type().equals("DEATH") || entry.type().equals("KILL")) && (entry.player().equalsIgnoreCase(name) || entry.data(1).equalsIgnoreCase(name));
                case "kills" -> entry.type().equals("KILL") && entry.player().equalsIgnoreCase(name);
                case "events" -> entry.type().equals("EVENT") && (entry.player().equalsIgnoreCase(name) || mentions(entry.data(4), name));
                case "pickups" -> entry.type().equals("PICKUP") && (entry.player().equalsIgnoreCase(name) || entry.data(4).equalsIgnoreCase(name));
                case "commands" -> entry.type().equals("COMMAND") && entry.player().equalsIgnoreCase(name);
                case "restores" -> entry.type().equals("RESTORE") && (entry.player().equalsIgnoreCase(name) || entry.data(1).equalsIgnoreCase(name));
                case "pvp" -> entry.type().equals("PVP") && (entry.player().equalsIgnoreCase(name) || entry.data(0).equalsIgnoreCase(name));
                case "fire" -> entry.type().equals("FIRE") && entry.player().equalsIgnoreCase(name);
                case "chat" -> entry.type().equals("CHAT") && entry.player().equalsIgnoreCase(name);
                case "texts" -> TEXTS.contains(entry.type()) && entry.player().equalsIgnoreCase(name);
                default -> entry.player().equalsIgnoreCase(name) || entry.type().equals("DEATH") && entry.data(1).equalsIgnoreCase(name)
                        || entry.type().equals("PVP") && entry.data(0).equalsIgnoreCase(name)
                        || entry.type().equals("PICKUP") && entry.data(4).equalsIgnoreCase(name)
                        || entry.type().equals("RESTORE") && entry.data(1).equalsIgnoreCase(name)
                        || entry.type().equals("KILL") && entry.data(1).equalsIgnoreCase(name)
                        || entry.type().equals("EVENT") && mentions(entry.data(4), name);
            };
        }
        return entry -> switch (kind) {
            case "containers" -> entry.type().equals("CONTAINER") || entry.type().equals("BREAK");
            case "joins" -> entry.type().equals("JOIN") || entry.type().equals("LEAVE");
            case "deaths" -> entry.type().equals("DEATH") || entry.type().equals("KILL");
            case "kills" -> entry.type().equals("KILL");
            case "events" -> entry.type().equals("EVENT");
            case "pickups" -> entry.type().equals("PICKUP");
            case "commands" -> entry.type().equals("COMMAND");
            case "restores" -> entry.type().equals("RESTORE");
            case "pvp" -> entry.type().equals("PVP");
            case "fire" -> entry.type().equals("FIRE");
            case "chat" -> entry.type().equals("CHAT");
            case "texts" -> TEXTS.contains(entry.type());
            default -> true;
        };
    }

    private static boolean mentions(String players, String name) {
        for (String part : players.split("; ")) {
            if (part.regionMatches(true, 0, name + " ", 0, name.length() + 1)) {
                return true;
            }
        }
        return false;
    }

    private static int events(CommandContext<CommandSourceStack> context, int page) {
        show(context.getSource(), "События: взрывы и призывы", "/plog events", entry -> entry.type().equals("EVENT"), page, true);
        return 1;
    }

    private static int mods(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        LogStore.search(entry -> entry.type().equals("JOIN") && entry.player().equalsIgnoreCase(name) && !entry.data(2).equals("="), 3650, 0, 1)
                .thenAcceptAsync(list -> {
                    if (list.isEmpty()) {
                        source.sendFailure(Component.literal("Входов " + name + " в журнале нет"));
                        return;
                    }
                    LogStore.Entry entry = list.get(0);
                    source.sendSuccess(() -> Component.literal("Последний вход " + entry.player() + ": " + entry.time() + ", клиент " + entry.data(0))
                            .withStyle(ChatFormatting.GOLD), false);
                    String mods = entry.data(2);
                    String packs = entry.data(3);
                    source.sendSuccess(() -> Component.literal("Моды (" + (mods.isEmpty() ? 0 : mods.split(", ").length) + "): ").withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(mods.isEmpty() ? "нет данных (нужен Mod Guard)" : mods).withStyle(ChatFormatting.WHITE)), false);
                    source.sendSuccess(() -> Component.literal("Ресурспаки: ").withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(packs.isEmpty() ? "—" : packs).withStyle(ChatFormatting.WHITE)), false);
                }, source.getServer());
        return 1;
    }

    private static int alts(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        int addresses = Addresses.addressCount(name);
        if (addresses == 0) {
            source.sendFailure(Component.literal("Адресов " + name + " ещё нет: они записываются при входе"));
            return 0;
        }
        List<Addresses.Account> alts = Addresses.alts(name);
        source.sendSuccess(() -> Component.literal(name + ": адресов " + addresses + ", других аккаунтов с тех же адресов: " + alts.size())
                .withStyle(ChatFormatting.GOLD), false);
        if (alts.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Совпадений нет").withStyle(ChatFormatting.GRAY), false);
        }
        java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        for (Addresses.Account account : alts) {
            String last = java.time.Instant.ofEpochSecond(account.last()).atZone(LogStore.zone()).format(format);
            source.sendSuccess(() -> Component.empty().append(player(account.name()))
                    .append(Component.literal(" · был " + last + (account.crowded() ? " · общий адрес (" + account.shared() + " акк.)" : ""))
                            .withStyle(account.crowded() ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY)), false);
        }
        return alts.size();
    }

    private static void show(CommandSourceStack source, String title, String command, Predicate<LogStore.Entry> filter, int page, boolean withPlace) {
        LogStore.search(filter, PlayerLog.config().searchDays, (page - 1) * PAGE, PAGE + 1).thenAcceptAsync(list -> {
            source.sendSuccess(() -> Component.literal("— " + title + " · стр. " + page + " —").withStyle(ChatFormatting.GOLD), false);
            if (list.isEmpty()) {
                source.sendSuccess(() -> Component.literal(page == 1 ? "Записей нет (за " + PlayerLog.config().searchDays + " дн.)" : "Больше записей нет")
                        .withStyle(ChatFormatting.GRAY), false);
                return;
            }
            for (LogStore.Entry entry : list.subList(0, Math.min(PAGE, list.size()))) {
                source.sendSuccess(() -> line(entry, withPlace), false);
            }
            MutableComponent nav = Component.empty();
            if (page > 1) {
                nav.append(button("[← назад]", command + " " + (page - 1)));
            }
            if (list.size() > PAGE) {
                nav.append(Component.literal("  ")).append(button("[вперёд →]", command + " " + (page + 1)));
            }
            if (!nav.getSiblings().isEmpty()) {
                source.sendSuccess(() -> nav, false);
            }
        }, source.getServer());
    }

    private static MutableComponent button(String text, String command) {
        return Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    private static MutableComponent time(LogStore.Entry entry) {
        String text = entry.time().length() >= 19 ? entry.time().substring(8, 10) + "." + entry.time().substring(5, 7) + " " + entry.time().substring(11, 19) : entry.time();
        return Component.literal(text + " ").withStyle(style -> style.withColor(ChatFormatting.GRAY)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(entry.time()))));
    }

    private static MutableComponent place(LogStore.Entry entry) {
        String coords = entry.x() + " " + entry.y() + " " + entry.z();
        String dim = entry.dimension().replace("minecraft:", "");
        return Component.literal(" (" + coords + (dim.equals("overworld") ? "" : ", " + dim) + ")").withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Нажмите, чтобы телепортироваться")))
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                        "/execute in " + entry.dimension() + " run tp @s " + entry.x() + " " + (entry.y() + 1) + " " + entry.z())));
    }

    private static MutableComponent item(String id, String name) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        MutableComponent text = location != null && BuiltInRegistries.ITEM.containsKey(location)
                ? Component.translatable(BuiltInRegistries.ITEM.get(location).getDescriptionId()) : Component.literal(id);
        if (!name.isEmpty()) {
            text.append(Component.literal(" «" + name + "»").withStyle(ChatFormatting.ITALIC));
        }
        return text.withStyle(style -> style.withColor(ChatFormatting.WHITE).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(id))));
    }

    private static MutableComponent thing(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location != null && BuiltInRegistries.BLOCK.containsKey(location)) {
            return Component.translatable(BuiltInRegistries.BLOCK.get(location).getDescriptionId());
        }
        if (location != null && BuiltInRegistries.ENTITY_TYPE.containsKey(location)) {
            return Component.translatable(BuiltInRegistries.ENTITY_TYPE.get(location).getDescriptionId());
        }
        return Component.literal(id);
    }

    private static MutableComponent player(String name) {
        return Component.literal(name).withStyle(style -> style.withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/plog player " + name)));
    }

    private static String duration(String seconds) {
        long value;
        try {
            value = Long.parseLong(seconds);
        } catch (NumberFormatException e) {
            return "?";
        }
        if (value < 0) {
            return "?";
        }
        return value >= 3600 ? value / 3600 + " ч " + value % 3600 / 60 + " мин" : value / 60 + " мин " + value % 60 + " с";
    }

    static Component line(LogStore.Entry entry, boolean withPlace) {
        MutableComponent line = time(entry);
        switch (entry.type()) {
            case "CONTAINER" -> {
                int count = parse(entry.data(1));
                line.append(player(entry.player())).append(Component.literal(count < 0 ? " взял " : " положил ")
                                .withStyle(count < 0 ? ChatFormatting.RED : ChatFormatting.GREEN))
                        .append(Component.literal(Math.abs(count) + "× ").withStyle(ChatFormatting.WHITE)).append(item(entry.data(2), entry.data(3)));
                if (withPlace) {
                    line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY)).append(thing(entry.data(0)).withStyle(ChatFormatting.GRAY));
                }
            }
            case "BREAK" -> line.append(player(entry.player())).append(Component.literal(" сломал ").withStyle(ChatFormatting.DARK_RED))
                    .append(thing(entry.data(0)).withStyle(ChatFormatting.GRAY)).append(Component.literal(" с " + entry.data(1) + "× ").withStyle(ChatFormatting.GRAY))
                    .append(item(entry.data(2), entry.data(3)));
            case "JOIN" -> {
                String mods = entry.data(2);
                MutableComponent hover = Component.literal("Клиент: " + entry.data(0) + "\n");
                hover.append(Component.literal(mods.equals("=") ? "Моды и ресурспаки те же, что при прошлом входе"
                        : mods.isEmpty() ? "Список модов неизвестен (нужен Mod Guard)" : "Моды: " + shorten(mods, 1500)));
                if (!entry.data(3).isEmpty() && !entry.data(3).equals("=")) {
                    hover.append(Component.literal("\nРесурспаки: " + shorten(entry.data(3), 500)));
                }
                hover.append(Component.literal("\n\nНажмите — полный список").withStyle(ChatFormatting.GRAY));
                line.append(player(entry.player())).append(Component.literal(" зашёл").withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(" · " + entry.data(0) + (entry.data(1).equals("-") ? "" : " · модов: " + entry.data(1)))
                                .withStyle(style -> style.withColor(ChatFormatting.AQUA).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/plog mods " + entry.player()))));
            }
            case "LEAVE" -> line.append(player(entry.player())).append(Component.literal(" вышел").withStyle(ChatFormatting.RED))
                    .append(Component.literal(" · в игре " + duration(entry.data(0)) + (entry.data(1).isEmpty() ? "" : " · " + entry.data(1))).withStyle(ChatFormatting.GRAY));
            case "DEATH" -> {
                line.append(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED)).append(Component.literal(entry.data(0)).withStyle(ChatFormatting.WHITE));
                if (!entry.data(2).isEmpty()) {
                    line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY)).append(item(entry.data(2), entry.data(3)));
                }
                line.append(Component.literal(" · ур. " + entry.data(4)).withStyle(ChatFormatting.GRAY));
                if (DeathStore.valid(entry.data(5))) {
                    line.append(deathButtons(entry.data(5)));
                }
            }
            case "PICKUP" -> {
                String kind = switch (entry.data(3)) {
                    case "own_death" -> " (свои вещи после смерти)";
                    case "death" -> " из вещей погибшего " + entry.data(4);
                    default -> " выброшенное " + entry.data(4);
                };
                line.append(player(entry.player())).append(Component.literal(" подобрал ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(entry.data(0) + "× ").withStyle(ChatFormatting.WHITE)).append(item(entry.data(1), entry.data(2)))
                        .append(Component.literal(kind).withStyle(entry.data(3).equals("death") ? ChatFormatting.RED : ChatFormatting.GRAY));
            }
            case "COMMAND" -> line.append(player(entry.player())).append(Component.literal(" /" + entry.data(0)).withStyle(style -> style.withColor(ChatFormatting.WHITE)
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Нажмите, чтобы скопировать")))
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/" + entry.data(0)))));
            case "RESTORE" -> line.append(player(entry.player())).append(Component.literal(entry.data(0).equals("return") ? " вернул " : " забрал из снимка ")
                            .withStyle(ChatFormatting.LIGHT_PURPLE))
                    .append(Component.literal(entry.data(3) + " стак. ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(entry.data(0).equals("return") ? "игроку " + entry.data(1) : "смерти " + entry.data(1)).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" [" + entry.data(2) + "]").withStyle(style -> style.withColor(ChatFormatting.DARK_AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/plog items " + entry.data(2)))));
            case "KILL" -> {
                MutableComponent victim = entry.data(0).equals("minecraft:player") ? player(entry.data(1))
                        : thing(entry.data(0)).withStyle(ChatFormatting.WHITE).append(entry.data(1).isEmpty() ? Component.empty() : Component.literal(" «" + entry.data(1) + "»"));
                line.append(Component.literal("⚔ ").withStyle(ChatFormatting.RED)).append(player(entry.player()))
                        .append(Component.literal(" убил ").withStyle(ChatFormatting.RED)).append(victim);
                if (!entry.data(2).isEmpty()) {
                    line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY)).append(item(entry.data(2), entry.data(3)));
                }
                if (!entry.data(4).isEmpty()) {
                    line.append(Component.literal(" · хозяин " + entry.data(4)).withStyle(ChatFormatting.GRAY));
                }
            }
            case "EVENT" -> {
                boolean spawn = entry.data(0).equals("spawn");
                String count = entry.data(2);
                MutableComponent players = Component.literal("Игроки в этот момент:\n" + (entry.data(4).isEmpty() ? "—" : entry.data(4).replace("; ", "\n")));
                line.append(Component.literal(spawn ? "☣ Призван " : "💥 Взрыв ").withStyle(ChatFormatting.GOLD))
                        .append(entry.data(1).equals("block") ? Component.literal("блока (кровать/якорь)") : thing(entry.data(1)))
                        .append(Component.literal(!spawn && !count.equals("1") ? " ×" + count : "").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(entry.data(3).isEmpty() ? " · виновник неизвестен" : " · " + entry.data(3)).withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(" [игроки]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, players))));
            }
            case "PVP" -> {
                line.append(Component.literal("🗡 ").withStyle(ChatFormatting.RED)).append(player(entry.player()))
                        .append(Component.literal(" ударил ").withStyle(ChatFormatting.RED)).append(player(entry.data(0)))
                        .append(Component.literal(" ×" + entry.data(2) + " · урон " + entry.data(1) + " · осталось ♥ " + entry.data(5)).withStyle(ChatFormatting.GRAY));
                if (!entry.data(3).isEmpty()) {
                    line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY)).append(entry.data(3).contains(":") && ResourceLocation.tryParse(entry.data(3)) != null
                            && BuiltInRegistries.ITEM.containsKey(ResourceLocation.tryParse(entry.data(3))) ? item(entry.data(3), entry.data(4)) : thing(entry.data(3)).withStyle(ChatFormatting.WHITE));
                }
            }
            case "FIRE" -> line.append(Component.literal("🔥 ").withStyle(ChatFormatting.GOLD)).append(player(entry.player()))
                    .append(Component.literal(" применил ").withStyle(ChatFormatting.GOLD)).append(item(entry.data(0), ""))
                    .append(Component.literal(" на ").withStyle(ChatFormatting.GRAY)).append(thing(entry.data(1)).withStyle(ChatFormatting.GRAY));
            case "CHAT" -> line.append(player(entry.player())).append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(entry.data(0)).withStyle(ChatFormatting.WHITE));
            case "SIGN" -> line.append(player(entry.player())).append(Component.literal(entry.data(1).isEmpty() ? " стёр табличку" : " написал на табличке: ")
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(entry.data(1)).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(entry.data(0).equals("back") ? " (обратная сторона)" : "").withStyle(ChatFormatting.GRAY));
            case "BOOK" -> {
                boolean signed = entry.data(0).equals("sign");
                MutableComponent pages = Component.literal(entry.data(3).isEmpty() ? "пусто" : entry.data(3).replace(" ¶ ", "\n\n"));
                line.append(player(entry.player())).append(Component.literal(signed ? " подписал книгу «" + entry.data(1) + "»" : " изменил книгу")
                                .withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" · стр. " + entry.data(2) + " [текст]").withStyle(style -> style.withColor(ChatFormatting.GRAY)
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, pages))));
            }
            case "RENAME" -> line.append(player(entry.player())).append(Component.literal(" переименовал ").withStyle(ChatFormatting.AQUA))
                    .append(item(entry.data(0), "")).append(Component.literal(entry.data(1).isEmpty() ? "" : " «" + entry.data(1) + "»").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" → «" + entry.data(2) + "»").withStyle(ChatFormatting.WHITE));
            default -> line.append(Component.literal(entry.type() + " " + entry.player() + " " + String.join(" ", Arrays.asList(entry.data()))));
        }
        return line.append(place(entry));
    }

    private static Component deathButtons(String id) {
        MutableComponent hover = Component.literal("Вещи на момент смерти").withStyle(ChatFormatting.GOLD);
        DeathStore.Snapshot snapshot = PlayerLog.server == null ? null : DeathStore.load(id, PlayerLog.server.registryAccess());
        int stacks = 0;
        if (snapshot != null) {
            stacks = snapshot.stacks();
            int shown = 0;
            for (var entry : DeathStore.summary(snapshot).entrySet()) {
                if (shown++ >= 20) {
                    hover.append(Component.literal("\n…").withStyle(ChatFormatting.GRAY));
                    break;
                }
                hover.append(Component.literal("\n" + entry.getValue() + "× ").withStyle(ChatFormatting.WHITE)).append(item(entry.getKey(), ""));
            }
            hover.append(Component.literal("\nОпыт: " + snapshot.level() + " ур." + (snapshot.xpReturned() ? " (уже возвращён)" : "")).withStyle(ChatFormatting.GRAY));
        }
        hover.append(Component.literal("\n\nНажмите — открыть. Забранное из окна удаляется из снимка.").withStyle(ChatFormatting.GRAY));
        ChatFormatting color = stacks > 0 ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY;
        MutableComponent result = Component.literal(" [вещи: " + stacks + "]").withStyle(style -> style.withColor(color)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/plog items " + id)));
        return result.append(Component.literal(" [вернуть]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Отдать игроку всё оставшееся и опыт (игрок должен быть в сети)")))
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/plog return " + id))));
    }

    private static int items(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer viewer = context.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(context, "id");
        if (DeathStore.busy(id)) {
            context.getSource().sendFailure(Component.literal("Этот снимок уже открыт кем-то другим"));
            return 0;
        }
        if (!DeathStore.open(viewer, id)) {
            context.getSource().sendFailure(Component.literal("Снимок " + id + " не найден"));
            return 0;
        }
        return 1;
    }

    private static int giveBack(CommandContext<CommandSourceStack> context) {
        String id = StringArgumentType.getString(context, "id");
        if (DeathStore.busy(id)) {
            context.getSource().sendFailure(Component.literal("Снимок сейчас открыт, сначала закройте окно"));
            return 0;
        }
        DeathStore.Snapshot snapshot = DeathStore.load(id, context.getSource().registryAccess());
        if (snapshot == null) {
            context.getSource().sendFailure(Component.literal("Снимок " + id + " не найден"));
            return 0;
        }
        ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayer(snapshot.uuid());
        if (target == null) {
            context.getSource().sendFailure(Component.literal(snapshot.player() + " не в сети"));
            return 0;
        }
        boolean xp = !snapshot.xpReturned() && snapshot.level() > target.experienceLevel;
        if (snapshot.stacks() == 0 && !xp) {
            context.getSource().sendFailure(Component.literal("В снимке ничего не осталось"));
            return 0;
        }
        int given = DeathStore.giveBack(target, id);
        String by = context.getSource().getTextName();
        LogStore.write("RESTORE", by, context.getSource().getEntity() == null ? new java.util.UUID(0L, 0L) : context.getSource().getEntity().getUUID(),
                target.level().dimension().location().toString(), target.blockPosition(), "return", snapshot.player(), id, String.valueOf(given));
        context.getSource().sendSuccess(() -> Component.literal(snapshot.player() + " получил " + given + " стак." + (xp ? " и опыт до " + snapshot.level() + " ур." : "")), true);
        target.sendSystemMessage(Component.literal("Вам вернули вещи, бывшие при смерти").withStyle(ChatFormatting.GREEN));
        return given;
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static int parse(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

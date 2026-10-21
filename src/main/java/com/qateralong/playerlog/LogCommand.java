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
    private static final List<String> TYPES = List.of("all", "containers", "joins", "deaths", "kills", "events");
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
        Predicate<LogStore.Entry> filter = entry -> switch (kind) {
            case "containers" -> entry.player().equalsIgnoreCase(name) && (entry.type().equals("CONTAINER") || entry.type().equals("BREAK"));
            case "joins" -> entry.player().equalsIgnoreCase(name) && (entry.type().equals("JOIN") || entry.type().equals("LEAVE"));
            case "deaths" -> entry.type().equals("DEATH") && (entry.player().equalsIgnoreCase(name) || entry.data(1).equalsIgnoreCase(name))
                    || entry.type().equals("KILL") && (entry.player().equalsIgnoreCase(name) || entry.data(1).equalsIgnoreCase(name));
            case "kills" -> entry.type().equals("KILL") && entry.player().equalsIgnoreCase(name);
            case "events" -> entry.type().equals("EVENT") && (entry.player().equalsIgnoreCase(name) || mentions(entry.data(4), name));
            default -> entry.player().equalsIgnoreCase(name) || entry.type().equals("DEATH") && entry.data(1).equalsIgnoreCase(name)
                    || entry.type().equals("KILL") && entry.data(1).equalsIgnoreCase(name)
                    || entry.type().equals("EVENT") && mentions(entry.data(4), name);
        };
        String title = switch (kind) {
            case "containers" -> "Сундуки: " + name;
            case "joins" -> "Входы и выходы: " + name;
            case "deaths" -> "Смерти и убийства: " + name;
            case "kills" -> "Убийства: " + name;
            case "events" -> "События рядом с " + name;
            default -> "Журнал: " + name;
        };
        show(context.getSource(), title, "/plog player " + name + " " + kind, filter, page, true);
        return 1;
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
        LogStore.search(entry -> entry.type().equals("JOIN") && entry.player().equalsIgnoreCase(name), 3650, 0, 1)
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

    private static Component line(LogStore.Entry entry, boolean withPlace) {
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
                hover.append(Component.literal(mods.isEmpty() ? "Список модов неизвестен (нужен Mod Guard)" : "Моды: " + shorten(mods, 1500)));
                if (!entry.data(3).isEmpty()) {
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
            }
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
            default -> line.append(Component.literal(entry.type() + " " + entry.player() + " " + String.join(" ", Arrays.asList(entry.data()))));
        }
        return line.append(place(entry));
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

package com.example.ultcheck;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class UltCheck implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, Map<String, String>>>() {}.getType();

    // ip -> (uuid -> last known name)
    private static Map<String, Map<String, String>> ipToAccounts = new LinkedHashMap<>();
    private static Path file;

    @Override
    public void onInitialize() {
        file = FabricLoader.getInstance().getConfigDir().resolve("ultcheck.json");
        load();

        // Запоминаем IP каждого, кто заходит на сервер
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.player;
            String ip = p.getIp();
            if (ip == null || ip.isEmpty()) return;
            synchronized (UltCheck.class) {
                ipToAccounts.computeIfAbsent(ip, k -> new LinkedHashMap<>())
                        .put(p.getUuidAsString(), p.getGameProfile().getName());
                save();
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("ultcheck")
                        .requires(src -> src.hasPermissionLevel(3)) // только операторы
                        .executes(ctx -> run(ctx.getSource(), false))
                        .then(CommandManager.literal("all")
                                .executes(ctx -> run(ctx.getSource(), true)))
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .executes(ctx -> runOne(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"))))));
    }

    // /ultcheck       -> все игроки онлайн + их твинки
    // /ultcheck all   -> все аккаунты из базы, у которых есть твинки
    private static int run(ServerCommandSource src, boolean all) {
        MinecraftServer server = src.getServer();
        Data d = buildData();
        Set<String> online = new HashSet<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            online.add(p.getUuidAsString());
            d.names.put(p.getUuidAsString(), p.getGameProfile().getName());
        }

        List<String> targets = new ArrayList<>(all ? d.names.keySet() : online);
        targets.sort(Comparator.comparing(u -> d.names.getOrDefault(u, "?").toLowerCase()));

        int shown = 0;
        src.sendFeedback(() -> Text.literal(all ? "=== UltCheck: вся база ===" : "=== UltCheck: игроки онлайн ===")
                .formatted(Formatting.GOLD, Formatting.BOLD), false);

        for (String uuid : targets) {
            Set<String> alts = altsOf(d, uuid);
            if (all && alts.isEmpty()) continue;
            src.sendFeedback(() -> line(d, uuid, alts, online), false);
            shown++;
        }
        int total = shown;
        src.sendFeedback(() -> Text.literal("Показано: " + total).formatted(Formatting.GRAY), false);
        return shown;
    }

    // /ultcheck <ник> -> как обычный /alts для одного игрока
    private static int runOne(ServerCommandSource src, String nick) {
        Data d = buildData();
        Set<String> online = new HashSet<>();
        for (ServerPlayerEntity p : src.getServer().getPlayerManager().getPlayerList()) {
            online.add(p.getUuidAsString());
        }
        String uuid = null;
        for (Map.Entry<String, String> e : d.names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(nick)) { uuid = e.getKey(); break; }
        }
        if (uuid == null) {
            src.sendFeedback(() -> Text.literal("Игрок не найден в базе: " + nick).formatted(Formatting.RED), false);
            return 0;
        }
        final String u = uuid;
        Set<String> alts = altsOf(d, u);
        src.sendFeedback(() -> line(d, u, alts, online), false);
        return 1;
    }

    private static MutableText line(Data d, String uuid, Set<String> alts, Set<String> online) {
        boolean on = online.contains(uuid);
        MutableText t = Text.literal(d.names.getOrDefault(uuid, "?"))
                .formatted(on ? Formatting.GREEN : Formatting.GRAY);
        if (alts.isEmpty()) {
            return t.append(Text.literal(" — твинков нет").formatted(Formatting.DARK_GRAY));
        }
        t.append(Text.literal(" — твинки (" + alts.size() + "): ").formatted(Formatting.YELLOW));
        boolean first = true;
        for (String a : alts) {
            if (!first) t.append(Text.literal(", ").formatted(Formatting.GRAY));
            first = false;
            t.append(Text.literal(d.names.getOrDefault(a, "?"))
                    .formatted(online.contains(a) ? Formatting.GREEN : Formatting.RED));
        }
        return t;
    }

    private static Set<String> altsOf(Data d, String uuid) {
        Set<String> result = new LinkedHashSet<>();
        for (String ip : d.ipsOf.getOrDefault(uuid, Set.of())) {
            result.addAll(ipToAccounts.getOrDefault(ip, Map.of()).keySet());
        }
        result.remove(uuid);
        return result;
    }

    private static class Data {
        Map<String, String> names = new HashMap<>();      // uuid -> name
        Map<String, Set<String>> ipsOf = new HashMap<>(); // uuid -> ips
    }

    private static synchronized Data buildData() {
        Data d = new Data();
        for (Map.Entry<String, Map<String, String>> e : ipToAccounts.entrySet()) {
            for (Map.Entry<String, String> acc : e.getValue().entrySet()) {
                d.names.put(acc.getKey(), acc.getValue());
                d.ipsOf.computeIfAbsent(acc.getKey(), k -> new HashSet<>()).add(e.getKey());
            }
        }
        return d;
    }

    private static void load() {
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    Map<String, Map<String, String>> m = GSON.fromJson(r, TYPE);
                    if (m != null) ipToAccounts = m;
                }
            }
        } catch (Exception e) {
            System.err.println("[UltCheck] Не удалось загрузить базу: " + e);
        }
    }

    private static void save() {
        try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(ipToAccounts, TYPE, w);
        } catch (Exception e) {
            System.err.println("[UltCheck] Не удалось сохранить базу: " + e);
        }
    }
}

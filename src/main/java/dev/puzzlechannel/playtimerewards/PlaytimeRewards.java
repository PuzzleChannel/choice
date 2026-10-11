package dev.puzzlechannel.playtimerewards;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlaytimeRewards implements ModInitializer {
    public static final String MOD_ID = "daily_playtime_rewards";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int[] THRESHOLDS = {1800, 3600, 7200, 14400};
    private static final String[] DEFAULT_ITEMS = {
            "minecraft:bread", "minecraft:water_bucket", "minecraft:golden_apple", "vmtrade:vmutcoin"
    };
    private static final int[] DEFAULT_COUNTS = {12, 1, 1, 1};

    private static final Map<UUID, PlayerRecord> PLAYERS = new HashMap<>();
    private static final Reward[] REWARDS = new Reward[4];
    private static Path dataPath;
    private static MinecraftServer server;
    private static int tickCounter;

    private static final class PlayerRecord {
        int seconds;
        int claimedMask;
        String date = LocalDate.now().toString();
    }

    private record Reward(String itemId, int count) { }

    @Override
    public void onInitialize() {
        for (int i = 0; i < REWARDS.length; i++) {
            REWARDS[i] = new Reward(DEFAULT_ITEMS[i], DEFAULT_COUNTS[i]);
        }

        PayloadTypeRegistry.playS2C().register(PlaytimePayloads.STATE_TYPE, PlaytimePayloads.State.CODEC);
        PayloadTypeRegistry.playC2S().register(PlaytimePayloads.CLAIM_TYPE, PlaytimePayloads.Claim.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            dataPath = FabricLoader.getInstance().getConfigDir().resolve("daily-playtime-rewards.json");
            loadData();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            saveData();
            server = null;
        });

        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (server == null) server = s;
            tickCounter++;
            if (tickCounter % 20 == 0) {
                for (ServerPlayer player : s.getPlayerList().getPlayers()) {
                    PlayerRecord record = getRecord(player);
                    record.seconds = Math.min(record.seconds + 1, THRESHOLDS[3]);
                    sendState(player, record);
                }
                saveData();
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(PlaytimePayloads.CLAIM_TYPE, (payload, context) ->
                context.server().execute(() -> claimReward(context.player(), payload.rewardIndex())));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("playtimerewards")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("set")
                                .then(Commands.argument("reward", IntegerArgumentType.integer(1, 4))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> {
                                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                                    int index = IntegerArgumentType.getInteger(context, "reward") - 1;
                                                    int count = IntegerArgumentType.getInteger(context, "count");
                                                    ItemStack held = player.getMainHandItem();
                                                    if (held.isEmpty()) {
                                                        context.getSource().sendFailure(Component.literal("Возьми предмет в основную руку."));
                                                        return 0;
                                                    }
                                                    REWARDS[index] = new Reward(BuiltInRegistries.ITEM.getKey(held.getItem()).toString(), count);
                                                    saveData();
                                                    context.getSource().sendSuccess(() -> Component.literal(
                                                            "Награда " + (index + 1) + " изменена: " + REWARDS[index].itemId + " x" + count), true);
                                                    for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                                                        sendState(online, getRecord(online));
                                                    }
                                                    return 1;
                                                }))))
                        .then(Commands.literal("reload")
                                .executes(context -> {
                                    loadData();
                                    context.getSource().sendSuccess(() -> Component.literal("Настройки наград загружены."), true);
                                    return 1;
                                }))
                        .then(Commands.literal("save")
                                .executes(context -> {
                                    saveData();
                                    context.getSource().sendSuccess(() -> Component.literal("Данные игрового времени сохранены."), true);
                                    return 1;
                                }))));
    }

    private static PlayerRecord getRecord(ServerPlayer player) {
        PlayerRecord record = PLAYERS.computeIfAbsent(player.getUUID(), ignored -> new PlayerRecord());
        String today = LocalDate.now().toString();
        if (!today.equals(record.date)) {
            record.date = today;
            record.seconds = 0;
            record.claimedMask = 0;
            saveData();
        }
        return record;
    }

    private static void sendState(ServerPlayer player, PlayerRecord record) {
        if (ServerPlayNetworking.canSend(player, PlaytimePayloads.STATE_TYPE)) {
            ServerPlayNetworking.send(player, new PlaytimePayloads.State(record.seconds, record.claimedMask));
        }
    }

    private static void claimReward(ServerPlayer player, int index) {
        if (index < 0 || index >= REWARDS.length) return;
        PlayerRecord record = getRecord(player);
        int bit = 1 << index;
        if ((record.claimedMask & bit) != 0 || record.seconds < THRESHOLDS[index]) {
            sendState(player, record);
            return;
        }

        Reward reward = REWARDS[index];
        Identifier itemIdentifier = Identifier.tryParse(reward.itemId);
        if (itemIdentifier == null || !BuiltInRegistries.ITEM.containsKey(itemIdentifier)) {
            player.sendSystemMessage(Component.literal("Предмет награды не найден: " + reward.itemId));
            return;
        }
        Item item = BuiltInRegistries.ITEM.get(itemIdentifier);
        if (item == net.minecraft.world.item.Items.AIR) {
            player.sendSystemMessage(Component.literal("Предмет награды недоступен: " + reward.itemId + ". Проверь, загружен ли VMTrade."));
            return;
        }

        ItemStack stack = new ItemStack(item, reward.count);
        if (!hasRoom(player, stack)) {
            player.sendSystemMessage(Component.literal("Освободи место в инвентаре, затем забери награду."));
            return;
        }

        // Persist the claim before inventory mutation: after a crash this can never be claimed twice.
        record.claimedMask |= bit;
        saveData();
        boolean inserted = player.getInventory().add(stack);
        if (!inserted || !stack.isEmpty()) {
            // The capacity check should prevent this; do not drop leftovers into the world.
            record.claimedMask &= ~bit;
            saveData();
            player.sendSystemMessage(Component.literal("Не удалось выдать предмет. Попробуй освободить место в инвентаре."));
            sendState(player, record);
            return;
        }
        player.sendSystemMessage(Component.literal("Получена награда за " + (THRESHOLDS[index] / 60) + " мин. игры!"));
        sendState(player, record);
    }

    private static boolean hasRoom(ServerPlayer player, ItemStack incoming) {
        int remaining = incoming.getCount();
        for (ItemStack existing : player.getInventory().items) {
            if (existing.isEmpty()) {
                remaining -= incoming.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(existing, incoming)) {
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }

    private static void loadData() {
        PLAYERS.clear();
        if (dataPath == null || !Files.exists(dataPath)) return;
        try {
            JsonObject root = GSON.fromJson(Files.readString(dataPath), JsonObject.class);
            if (root == null) return;
            if (root.has("rewards")) {
                JsonArray rewards = root.getAsJsonArray("rewards");
                for (int i = 0; i < Math.min(rewards.size(), REWARDS.length); i++) {
                    JsonObject value = rewards.get(i).getAsJsonObject();
                    String item = value.has("item") ? value.get("item").getAsString() : DEFAULT_ITEMS[i];
                    int count = value.has("count") ? value.get("count").getAsInt() : DEFAULT_COUNTS[i];
                    if (count >= 1 && count <= 64 && Identifier.tryParse(item) != null) REWARDS[i] = new Reward(item, count);
                }
            }
            if (root.has("players")) {
                for (JsonElement element : root.getAsJsonArray("players")) {
                    JsonObject value = element.getAsJsonObject();
                    UUID uuid = UUID.fromString(value.get("uuid").getAsString());
                    PlayerRecord record = new PlayerRecord();
                    record.seconds = Math.max(0, Math.min(THRESHOLDS[3], value.get("seconds").getAsInt()));
                    record.claimedMask = value.get("claimed").getAsInt() & 0b1111;
                    record.date = value.get("date").getAsString();
                    PLAYERS.put(uuid, record);
                }
            }
        } catch (Exception exception) {
            System.err.println("[Daily Playtime Rewards] Failed to load data: " + exception.getMessage());
        }
    }

    private static void saveData() {
        if (dataPath == null) return;
        JsonObject root = new JsonObject();
        JsonArray rewards = new JsonArray();
        for (Reward reward : REWARDS) {
            JsonObject value = new JsonObject();
            value.addProperty("item", reward.itemId);
            value.addProperty("count", reward.count);
            rewards.add(value);
        }
        root.add("rewards", rewards);
        JsonArray players = new JsonArray();
        PLAYERS.forEach((uuid, record) -> {
            JsonObject value = new JsonObject();
            value.addProperty("uuid", uuid.toString());
            value.addProperty("seconds", record.seconds);
            value.addProperty("claimed", record.claimedMask);
            value.addProperty("date", record.date);
            players.add(value);
        });
        root.add("players", players);
        try {
            Files.createDirectories(dataPath.getParent());
            Path temporary = dataPath.resolveSibling(dataPath.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(root));
            try {
                Files.move(temporary, dataPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(temporary, dataPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            System.err.println("[Daily Playtime Rewards] Failed to save data: " + exception.getMessage());
        }
    }
}

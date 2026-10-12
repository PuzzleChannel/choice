package dev.puzzlechannel.playtimerewards;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlaytimeRewards implements ModInitializer {
    public static final String MOD_ID = "daily_playtime_rewards";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int[] THRESHOLDS = {1800, 3600, 7200, 14400};
    private static final String[] DEFAULT_ITEMS = {
            "minecraft:bread", "minecraft:water_bucket", "minecraft:golden_apple", "vmtrade:vmcoin"
    };
    private static final int[] DEFAULT_COUNTS = {12, 1, 1, 1};
    private static final String VMCOIN_DESCRIPTION = "Используется для обмена на Маркете";
    private static final String[] DEFAULT_DESCRIPTIONS = {
            "",
            "",
            "",
            VMCOIN_DESCRIPTION
    };
    private static final Map<UUID, PlayerRecord> PLAYERS = new HashMap<>();
    private static final Reward[] REWARDS = new Reward[4];
    private static Path dataPath;
    private static MinecraftServer server;
    private static ZoneId resetZone = ZoneId.systemDefault();
    private static int tickCounter;
    private static boolean dataLoadFailed;

    private static final class PlayerRecord {
        int seconds;
        int claimedMask;
        int notifiedMask;
        String date = LocalDate.now(resetZone).toString();
    }

    private record Reward(String itemId, int count, String description) { }

    @Override
    public void onInitialize() {
        for (int i = 0; i < REWARDS.length; i++) REWARDS[i] = new Reward(DEFAULT_ITEMS[i], DEFAULT_COUNTS[i], DEFAULT_DESCRIPTIONS[i]);

        PayloadTypeRegistry.clientboundPlay().register(PlaytimePayloads.STATE_TYPE, PlaytimePayloads.State.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PlaytimePayloads.CLAIM_TYPE, PlaytimePayloads.Claim.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            dataPath = FabricLoader.getInstance().getConfigDir().resolve("daily-playtime-rewards.json");
            loadData();
            for (ServerPlayer player : s.getPlayerList().getPlayers()) {
                PlayerRecord record = getRecord(player);
                notifyReadyRewards(player, record);
                sendState(player, record);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            saveData();
            server = null;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> s.execute(() -> {
            PlayerRecord record = getRecord(handler.player);
            notifyReadyRewards(handler.player, record);
            sendState(handler.player, record);
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> saveData());

        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (server == null) server = s;
            tickCounter++;
            if (tickCounter % 20 == 0) {
                for (ServerPlayer player : s.getPlayerList().getPlayers()) {
                    PlayerRecord record = getRecord(player);
                    record.seconds = Math.min(record.seconds + 1, 86400);
                    notifyReadyRewards(player, record);
                    sendState(player, record);
                }
            }
            // Avoid writing the JSON file every second; save periodically and on disconnect/shutdown.
            if (tickCounter % 1200 == 0) saveData();
        });

        ServerPlayNetworking.registerGlobalReceiver(PlaytimePayloads.CLAIM_TYPE, (payload, context) ->
                context.server().execute(() -> claimReward(context.player(), payload.rewardIndex())));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("playtimerewards")
                        .requires(source -> Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source))
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
                                                    if (count > held.getMaxStackSize()) {
                                                        context.getSource().sendFailure(Component.literal("Количество превышает максимальный размер стака предмета: " + held.getMaxStackSize() + "."));
                                                        return 0;
                                                    }
                                                    Reward previousReward = REWARDS[index];
                                                    String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
                                                    REWARDS[index] = new Reward(itemId, count, descriptionFor(itemId));
                                                    if (saveData()) {
                                                        broadcastState();
                                                        context.getSource().sendSuccess(() -> Component.literal(
                                                                "Награда " + (index + 1) + " изменена: " + REWARDS[index].itemId + " x" + count), true);
                                                    } else {
                                                        REWARDS[index] = previousReward;
                                                        context.getSource().sendFailure(Component.literal("Не удалось сохранить данные. Проверь файл конфигурации и latest.log."));
                                                        return 0;
                                                    }
                                                    return 1;
                                                }))))
                        .then(Commands.literal("threshold")
                                .then(Commands.argument("reward", IntegerArgumentType.integer(1, 4))
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(60, 86400))
                                                .executes(context -> {
                                                    int index = IntegerArgumentType.getInteger(context, "reward") - 1;
                                                    int seconds = IntegerArgumentType.getInteger(context, "seconds");
                                                    int previous = index == 0 ? 0 : THRESHOLDS[index - 1];
                                                    int next = index == 3 ? 86401 : THRESHOLDS[index + 1];
                                                    if (seconds <= previous || seconds >= next) {
                                                        context.getSource().sendFailure(Component.literal("Порог должен быть строго между соседними порогами (" + PlaytimeSchedule.formatDuration(previous) + " и " + PlaytimeSchedule.formatDuration(next == 86401 ? 86400 : next) + ")."));
                                                        return 0;
                                                    }
                                                    int old = THRESHOLDS[index];
                                                    THRESHOLDS[index] = seconds;
                                                    if (!saveData()) {
                                                        THRESHOLDS[index] = old;
                                                        context.getSource().sendFailure(Component.literal("Не удалось сохранить настройки."));
                                                        return 0;
                                                    }
                                                    broadcastState();
                                                    context.getSource().sendSuccess(() -> Component.literal("Порог награды " + (index + 1) + ": " + PlaytimeSchedule.formatDuration(seconds)), true);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("timezone")
                                .then(Commands.argument("zone", StringArgumentType.word())
                                        .executes(context -> {
                                            String raw = StringArgumentType.getString(context, "zone");
                                            ZoneId old = resetZone;
                                            try {
                                                resetZone = ZoneId.of(raw);
                                            } catch (Exception exception) {
                                                context.getSource().sendFailure(Component.literal("Неизвестный часовой пояс. Пример: Europe/Stockholm или UTC."));
                                                return 0;
                                            }
                                            if (!saveData()) {
                                                resetZone = old;
                                                context.getSource().sendFailure(Component.literal("Не удалось сохранить настройки."));
                                                return 0;
                                            }
                                            context.getSource().sendSuccess(() -> Component.literal("Ежедневный сброс настроен по часовому поясу " + resetZone + "."), true);
                                            return 1;
                                        })))
                        .then(Commands.literal("status")
                                .executes(context -> {
                                    context.getSource().sendSuccess(() -> Component.literal("Сброс: " + resetZone + "; пороги: " +
                                            PlaytimeSchedule.formatDuration(THRESHOLDS[0]) + ", " +
                                            PlaytimeSchedule.formatDuration(THRESHOLDS[1]) + ", " +
                                            PlaytimeSchedule.formatDuration(THRESHOLDS[2]) + ", " +
                                            PlaytimeSchedule.formatDuration(THRESHOLDS[3])), false);
                                    if (server != null) {
                                        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                                            PlayerRecord record = getRecord(online);
                                            int claimed = Integer.bitCount(record.claimedMask & 0b1111);
                                            context.getSource().sendSuccess(() -> Component.literal(online.getName().getString() +
                                                    " — " + PlaytimeSchedule.formatDuration(record.seconds) + ", получено наград: " + claimed + "/4"), false);
                                        }
                                    }
                                    return 1;
                                }))
                        .then(Commands.literal("reload")
                                .executes(context -> {
                                    boolean loaded = loadData();
                                    if (loaded) {
                                        broadcastState();
                                        context.getSource().sendSuccess(() -> Component.literal("Настройки и данные загружены."), true);
                                        return 1;
                                    }
                                    context.getSource().sendFailure(Component.literal("Файл данных повреждён или недоступен. Он не перезаписан; исправь JSON или восстанови .bak."));
                                    return 0;
                                }))
                        .then(Commands.literal("save")
                                .executes(context -> {
                                    boolean saved = saveData();
                                    if (saved) context.getSource().sendSuccess(() -> Component.literal("Данные игрового времени сохранены."), true);
                                    else context.getSource().sendFailure(Component.literal("Сохранение отменено, чтобы не затереть данные. Проверь latest.log и JSON."));
                                    return saved ? 1 : 0;
                                }))));
    }

    private static PlayerRecord getRecord(ServerPlayer player) {
        PlayerRecord record = PLAYERS.computeIfAbsent(player.getUUID(), ignored -> new PlayerRecord());
        String today = LocalDate.now(resetZone).toString();
        if (!today.equals(record.date)) {
            record.date = today;
            record.seconds = 0;
            record.claimedMask = 0;
            record.notifiedMask = 0;
            saveData();
        }
        return record;
    }

    private static String descriptionFor(String itemId) {
        return "vmtrade:vmcoin".equals(itemId) ? VMCOIN_DESCRIPTION : "";
    }

    private static void notifyReadyRewards(ServerPlayer player, PlayerRecord record) {
        int newlyNotifiedMask = 0;
        for (int i = 0; i < REWARDS.length; i++) {
            int bit = 1 << i;
            if (record.seconds >= THRESHOLDS[i]
                    && (record.claimedMask & bit) == 0
                    && (record.notifiedMask & bit) == 0) {
                newlyNotifiedMask |= bit;
            }
        }
        if (newlyNotifiedMask == 0) return;

        // Persist the notification mask so a reconnect does not repeat the same message.
        record.notifiedMask |= newlyNotifiedMask;
        saveData();

        for (int i = 0; i < REWARDS.length; i++) {
            if ((newlyNotifiedMask & (1 << i)) == 0) continue;
            Component openButton = Component.literal("[ОТКРЫТЬ НАГРАДЫ]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.GOLD)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.RunCommand("/dailyrewards"))
                            .withHoverEvent(new HoverEvent.ShowText(
                                    Component.literal("Открыть меню ежедневных наград"))));
            player.sendSystemMessage(Component.literal("Награда №" + (i + 1) + " доступна! ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(openButton));
        }
    }

    private static void sendState(ServerPlayer player, PlayerRecord record) {
        if (ServerPlayNetworking.canSend(player, PlaytimePayloads.STATE_TYPE)) {
            ServerPlayNetworking.send(player, new PlaytimePayloads.State(record.seconds, record.claimedMask, PlaytimeSchedule.secondsUntilReset(java.time.Instant.now(), resetZone), encodeRewardConfig()));
        }
    }

    private static String encodeRewardConfig() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < REWARDS.length; i++) {
            if (i > 0) out.append(';');
            out.append(THRESHOLDS[i]).append(',').append(REWARDS[i].itemId).append(',').append(REWARDS[i].count).append(',').append(REWARDS[i].description.replace(";", ","));
        }
        return out.toString();
    }

    private static void broadcastState() {
        if (server == null) return;
        for (ServerPlayer online : server.getPlayerList().getPlayers()) sendState(online, getRecord(online));
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
        if (itemIdentifier == null) {
            player.sendSystemMessage(Component.literal("Неверный идентификатор предмета: " + reward.itemId));
            return;
        }
        Item item = BuiltInRegistries.ITEM.getValue(itemIdentifier);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            player.sendSystemMessage(Component.literal("Предмет награды недоступен: " + reward.itemId + ". Проверь, загружен ли нужный мод."));
            return;
        }
        ItemStack stack = new ItemStack(item, reward.count);
        if (!hasRoom(player, stack)) {
            player.sendSystemMessage(Component.literal("Освободи место в инвентаре, затем забери награду."));
            return;
        }

        record.claimedMask |= bit;
        if (!saveData()) {
            record.claimedMask &= ~bit;
            player.sendSystemMessage(Component.literal("Награда не выдана: не удалось сохранить отметку о получении."));
            sendState(player, record);
            return;
        }
        boolean inserted = player.getInventory().add(stack);
        if (!inserted || !stack.isEmpty()) {
            record.claimedMask &= ~bit;
            saveData();
            player.sendSystemMessage(Component.literal("Не удалось выдать предмет. Попробуй освободить место в инвентаре."));
            sendState(player, record);
            return;
        }
        player.sendSystemMessage(Component.literal("Награда получена!"));
        sendState(player, record);
    }

    private static boolean hasRoom(ServerPlayer player, ItemStack incoming) {
        int remaining = incoming.getCount();
        var inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty()) remaining -= incoming.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(existing, incoming))
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getCount());
            if (remaining <= 0) return true;
        }
        return false;
    }

    private static boolean loadData() {
        if (dataPath == null) return false;
        if (!Files.exists(dataPath)) {
            PLAYERS.clear();
            dataLoadFailed = false;
            return true;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(dataPath), JsonObject.class);
            if (root == null) throw new IllegalStateException("empty JSON");
            Map<UUID, PlayerRecord> loadedPlayers = new HashMap<>();
            Reward[] loadedRewards = REWARDS.clone();
            int[] loadedThresholds = THRESHOLDS.clone();
            ZoneId loadedZone = resetZone;

            if (root.has("timezone")) loadedZone = ZoneId.of(root.get("timezone").getAsString());
            if (root.has("thresholds")) {
                JsonArray values = root.getAsJsonArray("thresholds");
                if (values.size() != 4) throw new IllegalArgumentException("thresholds must contain 4 values");
                for (int i = 0; i < 4; i++) loadedThresholds[i] = values.get(i).getAsInt();
                for (int i = 0; i < 4; i++) {
                    if (loadedThresholds[i] < 60 || loadedThresholds[i] > 86400 ||
                            (i > 0 && loadedThresholds[i] <= loadedThresholds[i - 1]))
                        throw new IllegalArgumentException("invalid threshold order");
                }
            }
            if (root.has("rewards")) {
                JsonArray rewards = root.getAsJsonArray("rewards");
                for (int i = 0; i < Math.min(rewards.size(), loadedRewards.length); i++) {
                    JsonObject value = rewards.get(i).getAsJsonObject();
                    String item = value.has("item") ? value.get("item").getAsString() : DEFAULT_ITEMS[i];
                    // Migrate saved configs from the previous VMTrade item id to the actual market coin.
                    if ("vmtrade:vmutcoin".equals(item)) item = "vmtrade:vmcoin";
                    int count = value.has("count") ? value.get("count").getAsInt() : DEFAULT_COUNTS[i];
                    String description = descriptionFor(item);
                    if (count < 1 || count > 64 || Identifier.tryParse(item) == null)
                        throw new IllegalArgumentException("invalid reward " + (i + 1));
                    loadedRewards[i] = new Reward(item, count, description);
                }
            }
            if (root.has("players")) {
                for (JsonElement element : root.getAsJsonArray("players")) {
                    JsonObject value = element.getAsJsonObject();
                    UUID uuid = UUID.fromString(value.get("uuid").getAsString());
                    PlayerRecord record = new PlayerRecord();
                    record.seconds = Math.max(0, Math.min(86400, value.get("seconds").getAsInt()));
                    record.claimedMask = value.get("claimed").getAsInt() & 0b1111;
                    record.notifiedMask = value.has("notified") ? value.get("notified").getAsInt() & 0b1111 : 0;
                    record.date = value.get("date").getAsString();
                    loadedPlayers.put(uuid, record);
                }
            }

            PLAYERS.clear();
            PLAYERS.putAll(loadedPlayers);
            System.arraycopy(loadedRewards, 0, REWARDS, 0, REWARDS.length);
            System.arraycopy(loadedThresholds, 0, THRESHOLDS, 0, THRESHOLDS.length);
            resetZone = loadedZone;
            dataLoadFailed = false;
            return true;
        } catch (Exception exception) {
            dataLoadFailed = true;
            System.err.println("[Daily Playtime Rewards] Data load failed; refusing to overwrite file: " + exception.getMessage());
            return false;
        }
    }

    private static boolean saveData() {
        if (dataPath == null || dataLoadFailed) return false;
        JsonObject root = new JsonObject();
        root.addProperty("timezone", resetZone.getId());
        JsonArray thresholds = new JsonArray();
        for (int threshold : THRESHOLDS) thresholds.add(threshold);
        root.add("thresholds", thresholds);
        JsonArray rewards = new JsonArray();
        for (Reward reward : REWARDS) {
            JsonObject value = new JsonObject();
            value.addProperty("item", reward.itemId);
            value.addProperty("count", reward.count);
            value.addProperty("description", reward.description);
            rewards.add(value);
        }
        root.add("rewards", rewards);
        JsonArray players = new JsonArray();
        PLAYERS.forEach((uuid, record) -> {
            JsonObject value = new JsonObject();
            value.addProperty("uuid", uuid.toString());
            value.addProperty("seconds", record.seconds);
            value.addProperty("claimed", record.claimedMask);
            value.addProperty("notified", record.notifiedMask);
            value.addProperty("date", record.date);
            players.add(value);
        });
        root.add("players", players);
        try {
            Files.createDirectories(dataPath.getParent());
            Path temporary = dataPath.resolveSibling(dataPath.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(root));
            if (Files.exists(dataPath)) {
                Path backup = dataPath.resolveSibling(dataPath.getFileName() + ".bak");
                Files.copy(dataPath, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, dataPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(temporary, dataPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            System.err.println("[Daily Playtime Rewards] Failed to save data: " + exception.getMessage());
            return false;
        }
    }
}

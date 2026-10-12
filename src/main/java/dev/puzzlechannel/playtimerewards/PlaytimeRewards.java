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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class PlaytimeRewards implements ModInitializer {
    public static final String MOD_ID = "daily_playtime_rewards";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int[] THRESHOLDS = {1800, 3600, 7200, 14400};
    private static final String[] DEFAULT_ITEMS = {
            "minecraft:bread", "minecraft:water_bucket", "minecraft:golden_apple", "vmtrade:vmcoin"
    };
    private static final int[] DEFAULT_COUNTS = {8, 12, 1, 1};
    // Seven deterministic schedules, Monday first. Slot four is always the VMTrade coin.
    private static final String[][] WEEKLY_ITEMS = {
        {"minecraft:bread","minecraft:cooked_beef","minecraft:golden_apple","vmtrade:vmcoin"},
        {"minecraft:baked_potato","minecraft:cooked_cod","minecraft:ender_pearl","vmtrade:vmcoin"},
        {"minecraft:cooked_chicken","minecraft:golden_carrot","minecraft:golden_apple","vmtrade:vmcoin"},
        {"minecraft:cooked_porkchop","minecraft:golden_carrot","minecraft:enchanted_golden_apple","vmtrade:vmcoin"},
        {"minecraft:bread","minecraft:cooked_beef","minecraft:experience_bottle","vmtrade:vmcoin"},
        {"minecraft:baked_potato","minecraft:cooked_cod","minecraft:golden_apple","vmtrade:vmcoin"},
        {"minecraft:cooked_salmon","minecraft:golden_carrot","minecraft:ender_pearl","vmtrade:vmcoin"}
    };
    private static final int[][] WEEKLY_COUNTS = {
        {8,12,1,1},{16,24,2,1},{10,24,1,1},{12,6,1,1},{16,16,8,1},{20,32,2,1},{12,32,4,1}
    };
    private static final String VMCOIN_DESCRIPTION = "Используется для обмена на Маркете";
    private static final String[] DEFAULT_DESCRIPTIONS = {
            "",
            "",
            "",
            VMCOIN_DESCRIPTION
    };
    private static final Map<UUID, PlayerRecord> PLAYERS = new HashMap<>();
    private static final Reward[] REWARDS = new Reward[4];
    private static final Reward[][] WEEKLY_REWARDS = new Reward[7][4];
    private static int activeDay = -1;
    private static Path dataPath;
    private static MinecraftServer server;
    private static ZoneId resetZone = ZoneId.systemDefault();
    private static int tickCounter;
    private static boolean dataLoadFailed;
    private static final ClaimRequestLimiter CLAIM_REQUEST_LIMITER =
            new ClaimRequestLimiter(TimeUnit.MILLISECONDS.toNanos(500));

    private static final class PlayerRecord {
        int seconds;
        int claimedMask;
        int notifiedMask;
        String date = LocalDate.now(resetZone).toString();
    }

    private record Reward(String itemId, int count, String description) { }

    @Override
    public void onInitialize() {
        for (int day=0; day<7; day++) for (int i=0; i<4; i++) {
            String item=WEEKLY_ITEMS[day][i];
            WEEKLY_REWARDS[day][i]=new Reward(item,WEEKLY_COUNTS[day][i],descriptionFor(item));
        }
        refreshActiveRewards(LocalDate.now(resetZone));

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
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            CLAIM_REQUEST_LIMITER.clear(handler.player.getUUID());
            saveData();
        });

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

        ServerPlayNetworking.registerGlobalReceiver(PlaytimePayloads.CLAIM_TYPE, (payload, context) -> {
            ServerPlayer player = context.player();

            // Throttle on the network thread, before enqueueing work on the main server
            // thread. This prevents packet bursts from filling the server task queue.
            if (!CLAIM_REQUEST_LIMITER.tryAcquire(player.getUUID(), System.nanoTime())) return;

            context.server().execute(() -> {
                // A queued packet must not grant items after its sender has disconnected.
                if (context.server().getPlayerList().getPlayer(player.getUUID()) != player) return;
                claimReward(player, payload.rewardIndex());
            });
        });

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
                                                    if (index == 3 && count != 1) {
                                                        context.getSource().sendFailure(Component.literal("Четвёртая награда всегда выдаётся в количестве 1 vmtrade:vmcoin."));
                                                        return 0;
                                                    }
                                                    Reward previousReward = REWARDS[index];
                                                    int dayIndex = Math.max(0, activeDay);
                                                    String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
                                                    if (index == 3 && !"vmtrade:vmcoin".equals(itemId)) {
                                                        context.getSource().sendFailure(Component.literal("Четвёртая награда всегда должна быть vmtrade:vmcoin."));
                                                        return 0;
                                                    }
                                                    REWARDS[index] = new Reward(itemId, count, descriptionFor(itemId));
                                                    WEEKLY_REWARDS[dayIndex][index] = REWARDS[index];
                                                    if (saveData()) {
                                                        broadcastState();
                                                        context.getSource().sendSuccess(() -> Component.literal(
                                                                "Награда " + (index + 1) + " изменена: " + REWARDS[index].itemId + " x" + count), true);
                                                    } else {
                                                        REWARDS[index] = previousReward;
                                                        WEEKLY_REWARDS[dayIndex][index] = previousReward;
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

    private static void refreshActiveRewards(LocalDate date) {
        int day=date.getDayOfWeek().getValue()-1;
        if (day==activeDay) return;
        activeDay=day;
        System.arraycopy(WEEKLY_REWARDS[day],0,REWARDS,0,4);
        REWARDS[3]=new Reward("vmtrade:vmcoin",1,VMCOIN_DESCRIPTION);
        WEEKLY_REWARDS[day][3]=REWARDS[3];
    }

    private static PlayerRecord getRecord(ServerPlayer player) {
        LocalDate localDate=LocalDate.now(resetZone);
        refreshActiveRewards(localDate);
        PlayerRecord record = PLAYERS.computeIfAbsent(player.getUUID(), ignored -> new PlayerRecord());
        String today = localDate.toString();
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
        ItemStack prototype = new ItemStack(item);
        if (reward.count < 1 || reward.count > prototype.getMaxStackSize()) {
            player.sendSystemMessage(Component.literal("Настройка награды некорректна: количество превышает размер стака предмета."));
            return;
        }
        ItemStack stack = new ItemStack(item, reward.count);
        if (!hasRoom(player, stack)) {
            player.sendSystemMessage(Component.literal("Освободи место в инвентаре, затем забери награду."));
            return;
        }

        // Keep a rollback snapshot in case another mod causes a partial inventory insert.
        // Never clear the claim bit while any part of the reward might remain in inventory.
        ItemStack[] inventoryBefore = snapshotMainInventory(player);
        record.claimedMask |= bit;
        if (!saveData()) {
            record.claimedMask &= ~bit;
            player.sendSystemMessage(Component.literal("Награда не выдана: не удалось сохранить отметку о получении."));
            sendState(player, record);
            return;
        }

        boolean inserted;
        try {
            inserted = player.getInventory().add(stack) && stack.isEmpty();
        } catch (RuntimeException exception) {
            inserted = false;
            System.err.println("[Daily Playtime Rewards] Inventory insertion failed for "
                    + player.getUUID() + ": " + exception.getMessage());
        }
        if (!inserted) {
            restoreMainInventory(player, inventoryBefore);
            int claimedMaskBeforeRollback = record.claimedMask;
            record.claimedMask &= ~bit;
            if (!saveData()) {
                // The persisted claim bit is still set. Keep it set in memory as well,
                // so a later retry cannot duplicate a partially delivered reward.
                record.claimedMask = claimedMaskBeforeRollback;
            }
            player.sendSystemMessage(Component.literal(
                    (record.claimedMask & bit) != 0
                            ? "Выдача отменена безопасно, но запись о получении не удалось откатить. Обратись к администратору."
                            : "Выдача отменена безопасно. Освободи место и попробуй снова."));
            sendState(player, record);
            return;
        }

        player.sendSystemMessage(Component.literal("Награда получена!"));
        sendState(player, record);
    }

    private static ItemStack[] snapshotMainInventory(ServerPlayer player) {
        ItemStack[] snapshot = new ItemStack[36];
        for (int i = 0; i < snapshot.length; i++) {
            snapshot[i] = player.getInventory().getItem(i).copy();
        }
        return snapshot;
    }

    private static void restoreMainInventory(ServerPlayer player, ItemStack[] snapshot) {
        var inventory = player.getInventory();
        for (int i = 0; i < snapshot.length; i++) {
            inventory.setItem(i, snapshot[i].copy());
        }
    }

    private static boolean hasRoom(ServerPlayer player, ItemStack incoming) {
        int remaining = incoming.getCount();
        int stackLimit = incoming.getMaxStackSize();
        var inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty()) {
                remaining -= stackLimit;
            } else if (ItemStack.isSameItemSameComponents(existing, incoming)) {
                int compatibleLimit = Math.min(existing.getMaxStackSize(), stackLimit);
                remaining -= Math.max(0, compatibleLimit - existing.getCount());
            }
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
            Reward[][] loadedWeek = new Reward[7][4];
            for(int d=0;d<7;d++) for(int i=0;i<4;i++) {
                String item=WEEKLY_ITEMS[d][i];
                loadedWeek[d][i]=new Reward(item,WEEKLY_COUNTS[d][i],descriptionFor(item));
            }
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
            if (root.has("weeklyRewards")) {
                JsonArray week=root.getAsJsonArray("weeklyRewards");
                if(week.size()!=7) throw new IllegalArgumentException("weeklyRewards must contain seven days");
                for(int d=0;d<7;d++) {
                    JsonArray day=week.get(d).getAsJsonArray();
                    if(day.size()!=4) throw new IllegalArgumentException("each weekday needs four rewards");
                    for(int i=0;i<4;i++) {
                        JsonObject v=day.get(i).getAsJsonObject();
                        String item=v.has("item")?v.get("item").getAsString():WEEKLY_ITEMS[d][i];
                        if("vmtrade:vmutcoin".equals(item)) item="vmtrade:vmcoin";
                        if(i==3) item="vmtrade:vmcoin";
                        int count=i==3?1:(v.has("count")?v.get("count").getAsInt():WEEKLY_COUNTS[d][i]);
                        String desc=v.has("description")?v.get("description").getAsString():descriptionFor(item);
                        Identifier id=Identifier.tryParse(item);
                        if(id==null||count<1||count>64) throw new IllegalArgumentException("invalid weekly reward");
                        Item registered=BuiltInRegistries.ITEM.getValue(id);
                        if(registered!=null&&registered!=net.minecraft.world.item.Items.AIR&&count>new ItemStack(registered).getMaxStackSize())
                            throw new IllegalArgumentException("reward stack too large");
                        loadedWeek[d][i]=new Reward(item,count,desc);
                    }
                }
            } else if(root.has("rewards")) {
                // Migrate the old four-reward config to today's weekday.
                JsonArray old=root.getAsJsonArray("rewards");
                int d=LocalDate.now(loadedZone).getDayOfWeek().getValue()-1;
                for(int i=0;i<Math.min(4,old.size());i++) {
                    JsonObject v=old.get(i).getAsJsonObject();
                    String item=i==3?"vmtrade:vmcoin":v.has("item")?v.get("item").getAsString():WEEKLY_ITEMS[d][i];
                    int count=i==3?1:v.has("count")?v.get("count").getAsInt():WEEKLY_COUNTS[d][i];
                    loadedWeek[d][i]=new Reward(item,count,descriptionFor(item));
                }
            }
            int todayIndex=LocalDate.now(loadedZone).getDayOfWeek().getValue()-1;
            System.arraycopy(loadedWeek[todayIndex],0,loadedRewards,0,4);
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
            for(int d=0;d<7;d++) System.arraycopy(loadedWeek[d],0,WEEKLY_REWARDS[d],0,4);
            System.arraycopy(loadedRewards, 0, REWARDS, 0, REWARDS.length);
            System.arraycopy(loadedThresholds, 0, THRESHOLDS, 0, THRESHOLDS.length);
            resetZone = loadedZone;
            activeDay = todayIndex;
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
        JsonArray week = new JsonArray();
        for(int d=0;d<7;d++) {
            JsonArray day=new JsonArray();
            for(int i=0;i<4;i++) {
                Reward reward=i==3?new Reward("vmtrade:vmcoin",1,VMCOIN_DESCRIPTION):WEEKLY_REWARDS[d][i];
                JsonObject value=new JsonObject();
                value.addProperty("item",reward.itemId);
                value.addProperty("count",reward.count);
                value.addProperty("description",reward.description);
                day.add(value);
            }
            week.add(day);
        }
        root.add("weeklyRewards",week);
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
            byte[] jsonBytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);

            // Flush the complete new state before replacing the last known-good file.
            // This is especially important when a claimed bit must be durable before
            // the item is inserted into the player's inventory.
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(jsonBytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }

            if (Files.exists(dataPath)) {
                Path backup = dataPath.resolveSibling(dataPath.getFileName() + ".bak");
                Files.copy(dataPath, backup, StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel backupChannel = FileChannel.open(backup, StandardOpenOption.WRITE)) {
                    backupChannel.force(true);
                }
            }
            try {
                Files.move(temporary, dataPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temporary, dataPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Directory fsync is supported on common Unix filesystems but not on every
            // platform (notably some Windows filesystems), so treat it as best-effort.
            try (FileChannel directoryChannel = FileChannel.open(dataPath.getParent(), StandardOpenOption.READ)) {
                directoryChannel.force(true);
            } catch (IOException | UnsupportedOperationException ignored) {
                // The file contents have still been forced before the rename.
            }
            return true;
        } catch (IOException exception) {
            System.err.println("[Daily Playtime Rewards] Failed to save data: " + exception.getMessage());
            return false;
        }
    }
}

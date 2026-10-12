package dev.puzzlechannel.playtimerewards.client;

import dev.puzzlechannel.playtimerewards.PlaytimePayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class PlaytimeRewardsClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.parse("daily_playtime_rewards:general"));
    private static final KeyMapping OPEN_MENU = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.daily_playtime_rewards.open", GLFW.GLFW_KEY_P, CATEGORY));

    private static volatile int dailySeconds;
    private static volatile int claimedMask;
    private static volatile int[] thresholds = {1800, 3600, 7200, 14400};
    private static volatile String[] itemIds = {
            "minecraft:bread", "minecraft:water_bucket", "minecraft:golden_apple", "vmtrade:vmutcoin"
    };
    private static volatile int[] counts = {12, 1, 1, 1};

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(PlaytimePayloads.STATE_TYPE, (payload, context) ->
                context.client().execute(() -> {
                    dailySeconds = Math.max(0, Math.min(86400, payload.seconds()));
                    claimedMask = payload.claimedMask() & 0b1111;
                    applyRewardConfig(payload.rewardConfig());
                }));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_MENU.consumeClick()) {
                if (client.player != null) client.gui.setScreen(new RewardsScreen());
            }
        });
    }

    private static void applyRewardConfig(String config) {
        try {
            String[] entries = config.split(";");
            if (entries.length != 4) return;
            int[] newThresholds = new int[4];
            String[] newItemIds = new String[4];
            int[] newCounts = new int[4];
            for (int i = 0; i < 4; i++) {
                String[] fields = entries[i].split(",", 3);
                if (fields.length != 3) return;
                newThresholds[i] = Integer.parseInt(fields[0]);
                newItemIds[i] = fields[1];
                newCounts[i] = Integer.parseInt(fields[2]);
                if (newThresholds[i] < 1 || newCounts[i] < 1 || newCounts[i] > 64 || Identifier.tryParse(newItemIds[i]) == null) return;
            }
            thresholds = newThresholds;
            itemIds = newItemIds;
            counts = newCounts;
        } catch (RuntimeException ignored) {
            // Keep the last valid server-synchronized configuration.
        }
    }

    public static int dailySeconds() { return dailySeconds; }
    public static int claimedMask() { return claimedMask; }
    public static int threshold(int index) { return thresholds[index]; }
    public static int count(int index) { return counts[index]; }
    public static String itemId(int index) { return itemIds[index]; }
}

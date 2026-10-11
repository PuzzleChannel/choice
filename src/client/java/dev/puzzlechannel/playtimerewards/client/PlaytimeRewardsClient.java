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

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(PlaytimePayloads.STATE_TYPE, (payload, context) ->
                context.client().execute(() -> {
                    dailySeconds = payload.seconds();
                    claimedMask = payload.claimedMask();
                }));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_MENU.consumeClick()) {
                if (client.player != null) client.gui.setScreen(new RewardsScreen(dailySeconds, claimedMask));
            }
        });
    }

    public static int dailySeconds() {
        return dailySeconds;
    }

    public static int claimedMask() {
        return claimedMask;
    }
}

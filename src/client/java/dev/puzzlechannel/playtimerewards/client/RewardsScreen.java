package dev.puzzlechannel.playtimerewards.client;

import dev.puzzlechannel.playtimerewards.PlaytimePayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class RewardsScreen extends Screen {
    private static final int[] THRESHOLDS = {1800, 3600, 7200, 14400};
    private static final String[] TITLES = {
            "12 хлеба", "Ведро воды", "Золотое яблоко", "Монета VMTrade"
    };
    private static final String[] SUBTITLES = {
            "НАГРАДА I", "НАГРАДА II", "НАГРАДА III", "НАГРАДА IV"
    };
    private static final int BACKGROUND = 0xF0181715;
    private static final int PANEL = 0xFF29231E;
    private static final int PANEL_EDGE = 0xFF5C3927;
    private static final int ACCENT = 0xFFE58A3A;
    private static final int TEXT = 0xFFF4E8D9;
    private static final int MUTED = 0xFFB9A18D;

    public RewardsScreen(int seconds, int claimedMask) {
        super(Component.literal("Ежедневные награды"));
    }

    @Override
    protected void init() {
        int cardWidth = 144;
        int gap = 8;
        int totalWidth = cardWidth * 4 + gap * 3;
        int startX = (this.width - totalWidth) / 2;
        int cardY = Math.max(88, this.height / 2 - 60);
        for (int i = 0; i < 4; i++) {
            final int index = i;
            int x = startX + i * (cardWidth + gap);
            addRenderableWidget(Button.builder(Component.literal("ЗАБРАТЬ"), button ->
                    ClientPlayNetworking.send(new PlaytimePayloads.Claim(index)))
                    .bounds(x + 12, cardY + 111, cardWidth - 24, 20)
                    .build());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, this.height, BACKGROUND);
        int seconds = PlaytimeRewardsClient.dailySeconds();
        int claimed = PlaytimeRewardsClient.claimedMask();
        int cardWidth = 144;
        int gap = 8;
        int totalWidth = cardWidth * 4 + gap * 3;
        int startX = (this.width - totalWidth) / 2;
        int cardY = Math.max(88, this.height / 2 - 60);

        graphics.centeredText(this.font, "ЕЖЕДНЕВНЫЕ НАГРАДЫ", this.width / 2, 25, ACCENT);
        graphics.centeredText(this.font, "ИГРАЙ НА СЕРВЕРЕ — ПОЛУЧАЙ ПОЛЕЗНЫЕ ПРЕДМЕТЫ", this.width / 2, 43, MUTED);

        graphics.fill(this.width / 2 - 150, 59, this.width / 2 + 150, 78, PANEL);
        graphics.fill(this.width / 2 - 148, 61, this.width / 2 + 148, 76, 0xFF171411);
        int progress = Math.min(296, (int) (296.0 * seconds / THRESHOLDS[3]));
        graphics.fill(this.width / 2 - 148, 61, this.width / 2 - 148 + progress, 76, ACCENT);
        graphics.centeredText(this.font, formatTime(seconds) + " / 04:00:00", this.width / 2, 82, TEXT);

        for (int i = 0; i < 4; i++) {
            int x = startX + i * (cardWidth + gap);
            int threshold = THRESHOLDS[i];
            boolean claimedAlready = (claimed & (1 << i)) != 0;
            boolean unlocked = seconds >= threshold;
            graphics.fill(x, cardY, x + cardWidth, cardY + 142, PANEL_EDGE);
            graphics.fill(x + 1, cardY + 1, x + cardWidth - 1, cardY + 141, PANEL);
            graphics.fill(x + 1, cardY + 1, x + cardWidth - 1, cardY + 4, ACCENT);
            graphics.centeredText(this.font, SUBTITLES[i], x + cardWidth / 2, cardY + 15, MUTED);
            graphics.centeredText(this.font, TITLES[i], x + cardWidth / 2, cardY + 38, TEXT);
            graphics.centeredText(this.font, String.format("%02d:%02d:00", threshold / 3600, (threshold % 3600) / 60),
                    x + cardWidth / 2, cardY + 57, ACCENT);
            String status = claimedAlready ? "ПОЛУЧЕНО" : unlocked ? "ДОСТУПНО" : "ЗАКРЫТО";
            graphics.centeredText(this.font, status, x + cardWidth / 2, cardY + 81,
                    claimedAlready ? 0xFF83C783 : unlocked ? ACCENT : MUTED);
            int barX = x + 12;
            int barY = cardY + 96;
            graphics.fill(barX, barY, barX + cardWidth - 24, barY + 5, 0xFF171411);
            int fill = Math.min(cardWidth - 24, (int) ((cardWidth - 24) * Math.min(1.0, seconds / (double) threshold)));
            graphics.fill(barX, barY, barX + fill, barY + 5, claimedAlready ? 0xFF83C783 : ACCENT);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private static String formatTime(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
}

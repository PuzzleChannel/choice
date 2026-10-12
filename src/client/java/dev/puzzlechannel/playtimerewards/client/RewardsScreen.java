package dev.puzzlechannel.playtimerewards.client;

import dev.puzzlechannel.playtimerewards.PlaytimePayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class RewardsScreen extends Screen {
    private static final String[] SUBTITLES = {"НАГРАДА I", "НАГРАДА II", "НАГРАДА III", "НАГРАДА IV"};
    private static final int BACKGROUND = 0xF0181715;
    private static final int PANEL = 0xFF29231E;
    private static final int PANEL_EDGE = 0xFF5C3927;
    private static final int ACCENT = 0xFFE58A3A;
    private static final int TEXT = 0xFFF4E8D9;
    private static final int MUTED = 0xFFB9A18D;
    private final Button[] claimButtons = new Button[4];

    public RewardsScreen() { super(Component.literal("Ежедневные награды")); }

    @Override
    protected void init() {
        for (int i = 0; i < 4; i++) {
            final int index = i;
            claimButtons[i] = addRenderableWidget(Button.builder(Component.literal("ЗАБРАТЬ"), button ->
                    ClientPlayNetworking.send(new PlaytimePayloads.Claim(index)))
                    .bounds(0, 0, 120, 20).build());
        }
        layoutButtons();
    }

    private int columns() { return width >= 680 ? 4 : 2; }
    private int cardWidth() { return columns() == 4 ? 144 : Math.min(220, (width - 36) / 2); }
    private int cardHeight() { return 142; }
    private int gap() { return 8; }
    private int rows() { return 4 / columns(); }
    private int startX() { return (width - (cardWidth() * columns() + gap() * (columns() - 1))) / 2; }
    private int startY() { return Math.max(100, (height - (rows() * cardHeight() + gap() * (rows() - 1))) / 2 + 30); }

    private void layoutButtons() {
        for (int i = 0; i < 4; i++) {
            int col = i % columns(), row = i / columns();
            int x = startX() + col * (cardWidth() + gap());
            int y = startY() + row * (cardHeight() + gap());
            claimButtons[i].setPosition(x + 12, y + 111);
            claimButtons[i].setWidth(cardWidth() - 24);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        layoutButtons();
        graphics.fill(0, 0, width, height, BACKGROUND);
        int seconds = PlaytimeRewardsClient.dailySeconds(), claimed = PlaytimeRewardsClient.claimedMask();
        graphics.centeredText(font, "ЕЖЕДНЕВНЫЕ НАГРАДЫ", width / 2, 20, ACCENT);
        graphics.centeredText(font, "ИГРАЙ НА СЕРВЕРЕ — ПОЛУЧАЙ ПОЛЕЗНЫЕ ПРЕДМЕТЫ", width / 2, 38, MUTED);

        int barWidth = Math.min(296, width - 48), barX = (width - barWidth) / 2;
        graphics.fill(barX, 53, barX + barWidth, 72, PANEL);
        graphics.fill(barX + 2, 55, barX + barWidth - 2, 70, 0xFF171411);
        int progress = Math.min(barWidth - 4, (int) ((barWidth - 4.0) * seconds / PlaytimeRewardsClient.threshold(3)));
        graphics.fill(barX + 2, 55, barX + 2 + progress, 70, ACCENT);
        graphics.centeredText(font, formatTime(seconds) + " / " + formatTime(PlaytimeRewardsClient.threshold(3)), width / 2, 76, TEXT);

        int cw = cardWidth();
        for (int i = 0; i < 4; i++) {
            int col = i % columns(), row = i / columns();
            int x = startX() + col * (cw + gap()), y = startY() + row * (cardHeight() + gap());
            int threshold = PlaytimeRewardsClient.threshold(i);
            boolean claimedAlready = (claimed & (1 << i)) != 0, unlocked = seconds >= threshold;
            claimButtons[i].active = unlocked && !claimedAlready;

            graphics.fill(x, y, x + cw, y + cardHeight(), PANEL_EDGE);
            graphics.fill(x + 1, y + 1, x + cw - 1, y + cardHeight() - 1, PANEL);
            graphics.fill(x + 1, y + 1, x + cw - 1, y + 4, ACCENT);
            graphics.centeredText(font, SUBTITLES[i], x + cw / 2, y + 14, MUTED);
            graphics.centeredText(font, rewardTitle(i), x + cw / 2, y + 35, TEXT);
            graphics.centeredText(font, formatTime(threshold), x + cw / 2, y + 54, ACCENT);
            String status = claimedAlready ? "ПОЛУЧЕНО" : unlocked ? "ДОСТУПНО" : "ЗАКРЫТО";
            graphics.centeredText(font, status, x + cw / 2, y + 77, claimedAlready ? 0xFF83C783 : unlocked ? ACCENT : MUTED);
            int bx = x + 12, by = y + 94, inner = cw - 24;
            graphics.fill(bx, by, bx + inner, by + 5, 0xFF171411);
            int fill = Math.min(inner, (int) (inner * Math.min(1.0, seconds / (double) threshold)));
            graphics.fill(bx, by, bx + fill, by + 5, claimedAlready ? 0xFF83C783 : ACCENT);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private static String rewardTitle(int index) {
        String id = PlaytimeRewardsClient.itemId(index);
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String name = switch (path) {
            case "bread" -> "Хлеб";
            case "water_bucket" -> "Ведро воды";
            case "golden_apple" -> "Золотое яблоко";
            case "vmutcoin" -> "Монета VMTrade";
            default -> path.replace('_', ' ');
        };
        return name + " ×" + PlaytimeRewardsClient.count(index);
    }

    private static String formatTime(int seconds) {
        int hours = seconds / 3600, minutes = (seconds % 3600) / 60, secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
}

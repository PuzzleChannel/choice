package dev.puzzlechannel.playtimerewards.client;

import dev.puzzlechannel.playtimerewards.PlaytimePayloads;
import dev.puzzlechannel.playtimerewards.PlaytimeSchedule;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class RewardsScreen extends Screen {
    private static final String[] SUBTITLES = {"НАГРАДА I", "НАГРАДА II", "НАГРАДА III", "НАГРАДА IV"};
    private static final int BACKGROUND = 0xF0181715;
    private static final int PANEL = 0xFF29231E;
    private static final int PANEL_EDGE = 0xFF5C3927;
    private static final int ACCENT = 0xFFE58A3A;
    private static final int TEXT = 0xFFF4E8D9;
    private static final int MUTED = 0xFFB9A18D;
    private static final String VMCOIN_ID = "vmtrade:vmcoin";
    private static final String VMCOIN_DESCRIPTION = "Используется для обмена на Маркете";
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
    private int cardHeight() { return 166; }
    private int gap() { return 8; }
    private int rows() { return 4 / columns(); }
    private int startX() { return (width - (cardWidth() * columns() + gap() * (columns() - 1))) / 2; }
    private int startY() { return Math.max(120, (height - (rows() * cardHeight() + gap() * (rows() - 1))) / 2 + 42); }

    private void layoutButtons() {
        for (int i = 0; i < 4; i++) {
            int col = i % columns(), row = i / columns();
            int x = startX() + col * (cardWidth() + gap());
            int y = startY() + row * (cardHeight() + gap());
            claimButtons[i].setPosition(x + 12, y + 137);
            claimButtons[i].setWidth(cardWidth() - 24);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        layoutButtons();
        graphics.fill(0, 0, width, height, BACKGROUND);
        int seconds = PlaytimeRewardsClient.dailySeconds(), claimed = PlaytimeRewardsClient.claimedMask();
        graphics.centeredText(font, "ЕЖЕДНЕВНЫЕ НАГРАДЫ", width / 2, 15, ACCENT);
        graphics.centeredText(font, "ИГРАЙ НА СЕРВЕРЕ — ПОЛУЧАЙ ПОЛЕЗНЫЕ ПРЕДМЕТЫ", width / 2, 33, MUTED);
        graphics.centeredText(font, "СБРОС ЧЕРЕЗ " + PlaytimeSchedule.formatDuration(PlaytimeRewardsClient.secondsUntilReset()), width / 2, 51, TEXT);

        int barWidth = Math.min(296, width - 48), barX = (width - barWidth) / 2;
        graphics.fill(barX, 61, barX + barWidth, 80, PANEL);
        graphics.fill(barX + 2, 63, barX + barWidth - 2, 78, 0xFF171411);
        int progress = Math.min(barWidth - 4, (int) ((barWidth - 4.0) * seconds / Math.max(1.0, PlaytimeRewardsClient.threshold(3))));
        graphics.fill(barX + 2, 63, barX + 2 + progress, 78, ACCENT);
        graphics.centeredText(font, PlaytimeSchedule.formatDuration(seconds) + " / " + PlaytimeSchedule.formatDuration(PlaytimeRewardsClient.threshold(3)), width / 2, 83, TEXT);

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
            graphics.centeredText(font, SUBTITLES[i], x + cw / 2, y + 10, MUTED);
            drawRewardItem(graphics, i, x + cw / 2 - 8, y + 25);

            boolean vmcoin = VMCOIN_ID.equals(PlaytimeRewardsClient.itemId(i));
            if (vmcoin) {
                drawDescription(graphics, VMCOIN_DESCRIPTION, x + 8, y + 45, cw - 16, 2);
            }
            int thresholdY = vmcoin ? y + 73 : y + 55;
            int statusY = vmcoin ? y + 90 : y + 72;
            int progressY = vmcoin ? y + 107 : y + 89;
            graphics.centeredText(font, PlaytimeSchedule.formatDuration(threshold), x + cw / 2, thresholdY, ACCENT);
            String status = claimedAlready ? "ПОЛУЧЕНО" : unlocked ? "ДОСТУПНО" : "ЗАКРЫТО";
            graphics.centeredText(font, status, x + cw / 2, statusY, claimedAlready ? 0xFF83C783 : unlocked ? ACCENT : MUTED);
            int bx = x + 12, by = progressY, inner = cw - 24;
            graphics.fill(bx, by, bx + inner, by + 5, 0xFF171411);
            int fill = Math.min(inner, (int) (inner * Math.min(1.0, seconds / (double) threshold)));
            graphics.fill(bx, by, bx + fill, by + 5, claimedAlready ? 0xFF83C783 : ACCENT);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawDescription(GuiGraphicsExtractor graphics, String description, int x, int y, int maxWidth, int maxLines) {
        StringBuilder line = new StringBuilder();
        int lineNumber = 0;
        for (String word : description.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.width(candidate) > maxWidth && !line.isEmpty()) {
                graphics.centeredText(font, line.toString(), x + maxWidth / 2, y + lineNumber * 11, MUTED);
                line.setLength(0);
                line.append(word);
                if (++lineNumber >= maxLines) return;
            } else {
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
        }
        if (!line.isEmpty() && lineNumber < maxLines) {
            graphics.centeredText(font, line.toString(), x + maxWidth / 2, y + lineNumber * 11, MUTED);
        }
    }

    private void drawRewardItem(GuiGraphicsExtractor graphics, int index, int x, int y) {
        Identifier id = Identifier.tryParse(PlaytimeRewardsClient.itemId(index));
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
        int count = PlaytimeRewardsClient.count(index);
        if (item == null || item == Items.AIR) {
            graphics.centeredText(font, "?", x + 8, y + 4, MUTED);
            return;
        }

        ItemStack stack = new ItemStack(item, count);
        graphics.item(stack, x, y);
        // Pass a custom count label so even single-item rewards display “1”.
        graphics.itemDecorations(font, stack, x, y, Integer.toString(count));
    }
}

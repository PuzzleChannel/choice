package dev.puzzlechannel.playtimerewards.client;

import dev.puzzlechannel.playtimerewards.PlaytimeSchedule;
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
                    PlaytimeRewardsClient.requestClaim(index))
                    .bounds(0, 0, 120, 20).build());
        }
        layoutButtons();
    }

    // Minecraft's GUI width/height already account for the active GUI scale. Use those
    // dimensions rather than assuming a fixed 854x480-style viewport.
    private int outerPadding() { return Math.min(20, Math.max(6, width / 40)); }
    private int gap() { return width < 440 ? 5 : 8; }
    private int columns() {
        int available = Math.max(1, width - outerPadding() * 2);
        if (available >= 4 * 115 + gap() * 3) return 4;
        if (available >= 2 * 105 + gap()) return 2;
        return 1;
    }
    private int rows() { return 4 / columns(); }
    private int cardWidth() {
        int available = Math.max(1, width - outerPadding() * 2 - gap() * (columns() - 1));
        int maxWidth = columns() == 4 ? 180 : columns() == 2 ? 220 : 300;
        return Math.max(1, Math.min(maxWidth, available / columns()));
    }
    private int cardHeight() {
        int margin = verticalMargin();
        int available = height - margin * 2 - headerHeight() - headerCardsGap()
                - gap() * (rows() - 1);
        int perRow = available / Math.max(1, rows());
        return Math.max(110, Math.min(166, perRow));
    }
    private int verticalMargin() { return height < 300 ? 8 : 12; }
    private int headerHeight() { return height < 300 ? 64 : height < 380 ? 74 : 82; }
    private int headerCardsGap() { return height < 300 ? 5 : 10; }
    private int buttonHeight() { return Math.max(14, Math.min(20, Math.round(20f * cardScale()))); }
    private int buttonBottomPadding() { return height < 380 ? 5 : 8; }
    private float cardScale() { return Math.min(1.0f, cardHeight() / 166.0f); }
    private int gridWidth() { return cardWidth() * columns() + gap() * (columns() - 1); }
    private int startX() { return (width - gridWidth()) / 2; }

    private int startTop() {
        int contentHeight = headerHeight() + headerCardsGap()
                + cardHeight() * rows() + gap() * (rows() - 1);
        return Math.max(verticalMargin(), (height - contentHeight) / 2);
    }

    private int startY() { return startTop() + headerHeight() + headerCardsGap(); }

    private void layoutButtons() {
        int cw = cardWidth();
        int ch = cardHeight();
        int inset = Math.min(12, Math.max(4, cw / 12));
        int buttonHeight = buttonHeight();
        int buttonY = ch - buttonHeight - buttonBottomPadding();
        for (int i = 0; i < 4; i++) {
            int col = i % columns(), row = i / columns();
            int x = startX() + col * (cw + gap());
            int y = startY() + row * (ch + gap());
            claimButtons[i].setPosition(x + inset, y + buttonY);
            claimButtons[i].setWidth(Math.max(1, cw - inset * 2));
            claimButtons[i].setHeight(buttonHeight);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        layoutButtons();
        graphics.fill(0, 0, width, height, BACKGROUND);

        int seconds = PlaytimeRewardsClient.dailySeconds();
        int claimed = PlaytimeRewardsClient.claimedMask();
        int top = startTop();
        boolean compactHeader = height < 380 || width < 520;

        // Keep the heading, reset timer and overall progress grouped together directly
        // above the cards. The whole group is centred vertically as the viewport changes.
        graphics.centeredText(font, "ЕЖЕДНЕВНЫЕ НАГРАДЫ", width / 2, top + 2, ACCENT);
        graphics.centeredText(font,
                compactHeader ? "ИГРАЙ — ПОЛУЧАЙ НАГРАДЫ" : "ИГРАЙ НА СЕРВЕРЕ — ПОЛУЧАЙ ПОЛЕЗНЫЕ ПРЕДМЕТЫ",
                width / 2, top + (compactHeader ? 17 : 21), MUTED);
        graphics.centeredText(font,
                "СБРОС ЧЕРЕЗ " + PlaytimeSchedule.formatDuration(PlaytimeRewardsClient.secondsUntilReset()),
                width / 2, top + (compactHeader ? 31 : 39), TEXT);

        int barTop = top + (compactHeader ? 39 : 48);
        int barHeight = height < 380 ? 12 : 15;
        int barWidth = Math.max(1, Math.min(296, width - outerPadding() * 2));
        int barX = (width - barWidth) / 2;
        graphics.fill(barX, barTop, barX + barWidth, barTop + barHeight + 4, PANEL);
        graphics.fill(barX + 2, barTop + 2, barX + barWidth - 2, barTop + barHeight, 0xFF171411);
        int innerBarWidth = Math.max(0, barWidth - 4);
        int progress = Math.min(innerBarWidth, (int) (innerBarWidth
                * seconds / Math.max(1.0, PlaytimeRewardsClient.threshold(3))));
        graphics.fill(barX + 2, barTop + 2, barX + 2 + progress, barTop + barHeight, ACCENT);
        graphics.centeredText(font,
                PlaytimeSchedule.formatDuration(seconds) + " / "
                        + PlaytimeSchedule.formatDuration(PlaytimeRewardsClient.threshold(3)),
                width / 2, barTop + barHeight + 5, TEXT);

        int cw = cardWidth();
        int ch = cardHeight();
        float scale = cardScale();
        boolean smallCards = ch < 130;
        for (int i = 0; i < 4; i++) {
            int col = i % columns(), row = i / columns();
            int x = startX() + col * (cw + gap());
            int y = startY() + row * (ch + gap());
            int threshold = PlaytimeRewardsClient.threshold(i);
            boolean claimedAlready = (claimed & (1 << i)) != 0, unlocked = seconds >= threshold;
            claimButtons[i].active = unlocked && !claimedAlready
                    && !PlaytimeRewardsClient.isClaimClickCoolingDown(i);

            graphics.fill(x, y, x + cw, y + ch, PANEL_EDGE);
            graphics.fill(x + 1, y + 1, x + cw - 1, y + ch - 1, PANEL);
            graphics.fill(x + 1, y + 1, x + cw - 1, y + 4, ACCENT);
            graphics.centeredText(font, SUBTITLES[i], x + cw / 2, y + Math.max(6, Math.round(10 * scale)), MUTED);

            int itemY = y + Math.max(21, Math.round(25 * scale));
            drawRewardItem(graphics, i, x + cw / 2 - 8, itemY);

            boolean vmcoin = VMCOIN_ID.equals(PlaytimeRewardsClient.itemId(i));
            int thresholdY;
            if (vmcoin) {
                int descriptionY = Math.max(itemY - y + 19, Math.round(45 * scale));
                drawDescription(graphics, VMCOIN_DESCRIPTION, x + 8, y + descriptionY, cw - 16,
                        ch < 145 ? 2 : 2);
                thresholdY = Math.max(Math.round(73 * scale), descriptionY + 24);
            } else {
                thresholdY = Math.max(Math.round(55 * scale), itemY - y + 24);
            }

            int statusY = thresholdY + Math.max(14, Math.round(17 * scale));
            int progressY = statusY + Math.max(14, Math.round(17 * scale));
            if (smallCards) {
                // In short windows, prioritise the item, timer, status and claim button;
                // the progress strip is hidden rather than overlapping the button.
                int compactButtonY = ch - buttonHeight() - buttonBottomPadding();
                thresholdY = Math.min(thresholdY, compactButtonY - 29);
                statusY = thresholdY + 15;
                progressY = -1;
            } else {
                int maxProgressY = ch - buttonHeight() - buttonBottomPadding() - 13;
                progressY = Math.min(progressY, maxProgressY);
                statusY = Math.min(statusY, progressY - 15);
                thresholdY = Math.min(thresholdY, statusY - 15);
            }

            graphics.centeredText(font, PlaytimeSchedule.formatDuration(threshold),
                    x + cw / 2, y + thresholdY, ACCENT);
            String status = claimedAlready ? "ПОЛУЧЕНО" : unlocked ? "ДОСТУПНО" : "ЗАКРЫТО";
            graphics.centeredText(font, status, x + cw / 2, y + statusY,
                    claimedAlready ? 0xFF83C783 : unlocked ? ACCENT : MUTED);
            if (progressY >= 0) {
                int bx = x + 12, by = y + progressY, inner = Math.max(1, cw - 24);
                graphics.fill(bx, by, bx + inner, by + 5, 0xFF171411);
                int fill = Math.min(inner, (int) (inner * Math.min(1.0, seconds / (double) threshold)));
                graphics.fill(bx, by, bx + fill, by + 5, claimedAlready ? 0xFF83C783 : ACCENT);
            }
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawDescription(GuiGraphicsExtractor graphics, String description, int x, int y,
                                 int maxWidth, int maxLines) {
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
        graphics.itemDecorations(font, stack, x, y, Integer.toString(count));
    }
}

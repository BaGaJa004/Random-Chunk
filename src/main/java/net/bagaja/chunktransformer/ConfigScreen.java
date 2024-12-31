package net.bagaja.chunktransformer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.List;

public class ConfigScreen extends Screen {
    private final Screen lastScreen;
    private final BlockConfig config;
    private int scrollOffset = 0;
    private static final int BUTTONS_PER_PAGE = 10;
    private List<Block> allBlocks;

    public ConfigScreen(Screen lastScreen, BlockConfig config) {
        super(Component.literal("Chunk Transformer Configuration"));
        this.lastScreen = lastScreen;
        this.config = config;
        this.allBlocks = new ArrayList<>();
        ForgeRegistries.BLOCKS.forEach(this.allBlocks::add);
    }

    @Override
    protected void init() {
        int buttonWidth = 200;
        int buttonHeight = 20;
        int spacing = 24;

        // Add navigation buttons
        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> minecraft.setScreen(lastScreen))
                .pos(width / 2 - 100, height - 28)
                .size(200, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("⬆"), button -> scrollUp())
                .pos(width / 2 + buttonWidth / 2 + 4, 20)
                .size(20, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("⬇"), button -> scrollDown())
                .pos(width / 2 + buttonWidth / 2 + 4, height - 48)
                .size(20, 20)
                .build());

        // Add block toggle buttons
        int startIndex = scrollOffset * BUTTONS_PER_PAGE;
        for (int i = 0; i < BUTTONS_PER_PAGE && startIndex + i < allBlocks.size(); i++) {
            Block block = allBlocks.get(startIndex + i);
            String blockId = ForgeRegistries.BLOCKS.getKey(block).toString();
            boolean enabled = config.isBlockEnabled(block);

            this.addRenderableWidget(Button.builder(
                            Component.literal((enabled ? "✔ " : "✘ ") + blockId),
                            button -> {
                                config.toggleBlock(blockId);
                                button.setMessage(Component.literal(
                                        (config.isBlockEnabled(block) ? "✔ " : "✘ ") + blockId
                                ));
                            })
                    .pos(width / 2 - buttonWidth / 2, 20 + i * spacing)
                    .size(buttonWidth, buttonHeight)
                    .build());
        }
    }

    private void scrollUp() {
        if (scrollOffset > 0) {
            scrollOffset--;
            init();
        }
    }

    private void scrollDown() {

    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    private void renderBackground(GuiGraphics graphics) {
    }
}
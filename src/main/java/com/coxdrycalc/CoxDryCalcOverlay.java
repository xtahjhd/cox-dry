package com.coxdrycalc;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.QuantityFormatter;

class CoxDryCalcOverlay extends OverlayPanel
{
    private static final Color PURPLE = new Color(0xEF, 0x20, 0xFF);
    private static final Color CM_ORANGE = new Color(0xFB, 0x92, 0x3C);
    private static final Color COMBINED_TEAL = new Color(0x2D, 0xD4, 0xBF);
    private static final Color LABEL = new Color(0xC8, 0xC8, 0xC8);
    private static final Color DRY = new Color(0xFF, 0xB0, 0x40);
    private static final Color VERY_DRY = new Color(0xFF, 0x50, 0x50);
    // wide enough for the longest purple name
    private static final int WIDTH = 190;
    private static final RaidMode[] ORDER = {RaidMode.COMBINED, RaidMode.REGULAR, RaidMode.CM};

    private final CoxDryCalcPlugin plugin;
    private final CoxDryCalcConfig config;

    @Inject
    CoxDryCalcOverlay(CoxDryCalcPlugin plugin, CoxDryCalcConfig config)
    {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        CoxDryCalcConfig.OverlayMode mode = config.overlayMode();
        if (mode == CoxDryCalcConfig.OverlayMode.NEVER
                || (mode == CoxDryCalcConfig.OverlayMode.IN_COX && !plugin.isInCox())
                || (config.hideDuringRaid() && plugin.isRaidInProgress()))
        {
            return null;
        }

        panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("CoX Dry Calc")
                .color(PURPLE)
                .build());

        // show every mode, the cm varbit is also set in scaled regular raids
        for (RaidMode m : ORDER)
        {
            addMode(plugin.getStats(m));
        }
        return super.render(graphics);
    }

    private void addMode(ModeStats s)
    {
        if (s == null)
        {
            return;
        }
        List<LayoutableRenderableEntity> lines = panelComponent.getChildren();
        lines.add(LineComponent.builder()
                .left(s.mode == RaidMode.COMBINED ? "Both modes" : s.mode.getLabel())
                .leftColor(color(s.mode))
                .right(QuantityFormatter.formatNumber(s.raids) + (s.raids == 1 ? " raid" : " raids"))
                .rightColor(Color.WHITE)
                .build());
        lines.add(LineComponent.builder()
                .left("  Points")
                .leftColor(LABEL)
                .right(QuantityFormatter.formatNumber(s.points))
                .build());
        double chance = s.wouldHaveOne();
        lines.add(LineComponent.builder()
                .left("  Would have one")
                .leftColor(LABEL)
                .right(String.format("%.1f%%", 100 * chance))
                .rightColor(chance >= 0.75 ? VERY_DRY : chance >= 0.5 ? DRY : Color.WHITE)
                .build());
        if (!s.lastPurple.isEmpty())
        {
            lines.add(LineComponent.builder()
                    .left("  Last")
                    .leftColor(LABEL)
                    .right(s.lastPurple)
                    .rightColor(PURPLE)
                    .build());
        }
    }

    private static Color color(RaidMode mode)
    {
        switch (mode)
        {
            case CM:
                return CM_ORANGE;
            case COMBINED:
                return COMBINED_TEAL;
            default:
                return PURPLE;
        }
    }
}
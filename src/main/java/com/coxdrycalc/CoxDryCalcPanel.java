package com.coxdrycalc;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.QuantityFormatter;

class CoxDryCalcPanel extends PluginPanel
{
    private static final Color PURPLE = new Color(0xC0, 0x84, 0xFC);
    private static final Color CM_ORANGE = new Color(0xFB, 0x92, 0x3C);
    private static final Color COMBINED_TEAL = new Color(0x2D, 0xD4, 0xBF);
    private static final int RECENT = 5;

    private final Map<RaidMode, ModeCard> cards = new EnumMap<>(RaidMode.class);

    CoxDryCalcPanel(Runnable onExport)
    {
        super(false);
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel title = new JLabel("CoX Dry Calc");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(PURPLE);
        title.setAlignmentX(LEFT_ALIGNMENT);
        content.add(title);

        JLabel sub = new JLabel("<html>Your purples against the drop rate, from every raid this plugin has logged.</html>");
        sub.setFont(FontManager.getRunescapeSmallFont());
        sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        sub.setBorder(new EmptyBorder(2, 0, 10, 0));
        sub.setAlignmentX(LEFT_ALIGNMENT);
        content.add(sub);

        for (RaidMode mode : new RaidMode[]{RaidMode.COMBINED, RaidMode.REGULAR, RaidMode.CM})
        {
            ModeCard card = new ModeCard(mode);
            card.setAlignmentX(LEFT_ALIGNMENT);
            cards.put(mode, card);
            content.add(card);
        }

        JButton export = new JButton("Export raid log (CSV)");
        export.setToolTipText("Save cox-raids.csv to open in Excel or Google Sheets");
        export.setFocusPainted(false);
        export.addActionListener(e -> onExport.run());
        export.setAlignmentX(LEFT_ALIGNMENT);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(ColorScheme.DARK_GRAY_COLOR);
        bottom.setBorder(new EmptyBorder(4, 0, 0, 0));
        bottom.add(export, BorderLayout.CENTER);
        bottom.setAlignmentX(LEFT_ALIGNMENT);
        content.add(bottom);

        add(content, BorderLayout.NORTH);
    }

    // call on the swing thread, pass copies of the stats
    void update(Map<RaidMode, ModeStats> stats)
    {
        stats.forEach((mode, s) -> cards.get(mode).update(s));
        revalidate();
        repaint();
    }

    private static class ModeCard extends JPanel
    {
        private final JLabel raids = value();
        private final JLabel points = value();
        private final JLabel purples = value();
        private final JLabel expected = value();
        private final JLabel luck = value();
        private final JLabel perPurple = value();
        private final JLabel dryRaids = value();
        private final JLabel dryPoints = value();
        private final JLabel wouldHave = value();
        private final JPanel recent = new JPanel();
        private final JPanel sizes = new JPanel(new GridBagLayout());

        ModeCard(RaidMode mode)
        {
            setLayout(new BorderLayout());
            setBackground(ColorScheme.DARKER_GRAY_COLOR);
            setBorder(BorderFactory.createCompoundBorder(
                    new EmptyBorder(0, 0, 10, 0),
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, 3, 0, 0, color(mode)),
                            new EmptyBorder(8, 8, 8, 8))));

            JLabel header = new JLabel(mode == RaidMode.CM ? "Challenge Mode"
                    : mode == RaidMode.COMBINED ? "Both modes" : "Regular");
            header.setFont(FontManager.getRunescapeBoldFont());
            header.setForeground(color(mode));
            header.setBorder(new EmptyBorder(0, 0, 6, 0));
            add(header, BorderLayout.NORTH);

            JPanel grid = new JPanel(new GridBagLayout());
            grid.setOpaque(false);
            int row = 0;
            row = section(grid, row, "Current dry streak");
            row = line(grid, row, "Raids", dryRaids);
            row = line(grid, row, "Points", dryPoints);
            row = line(grid, row, "Would have one", wouldHave);
            row = section(grid, row, "All logged raids");
            row = line(grid, row, "Raids", raids);
            row = line(grid, row, "Your points", points);
            row = line(grid, row, "Your purples", purples);
            row = line(grid, row, "Expected purples", expected);
            row = line(grid, row, "Luck", luck);
            row = line(grid, row, "Points per purple", perPurple);
            section(grid, row, "By team size");

            sizes.setOpaque(false);
            JPanel recentHeader = new JPanel(new GridBagLayout());
            recentHeader.setOpaque(false);
            section(recentHeader, 1, "Recent purples");
            recent.setLayout(new BoxLayout(recent, BoxLayout.Y_AXIS));
            recent.setOpaque(false);

            JPanel body = new JPanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setOpaque(false);
            for (JPanel part : new JPanel[]{grid, sizes, recentHeader, recent})
            {
                part.setAlignmentX(LEFT_ALIGNMENT);
                body.add(part);
            }
            add(body, BorderLayout.CENTER);
        }

        void update(ModeStats s)
        {
            dryRaids.setText(QuantityFormatter.formatNumber(s.raids));
            dryPoints.setText(QuantityFormatter.formatNumber(s.points));
            wouldHave.setText(String.format("%.1f%%", 100 * s.wouldHaveOne()));
            raids.setText(QuantityFormatter.formatNumber(s.totalRaids));
            points.setText(QuantityFormatter.formatNumber(s.totalPoints));
            purples.setText(Integer.toString(s.totalPurples));
            expected.setText(String.format("%.2f", s.expectedPurples));
            if (s.expectedPurples > 0)
            {
                double l = s.totalPurples / s.expectedPurples;
                luck.setText(String.format("%.2fx", l));
                luck.setForeground(l >= 1 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
            }
            else
            {
                luck.setText("-");
                luck.setForeground(Color.WHITE);
            }
            perPurple.setText(s.totalPurples > 0
                    ? QuantityFormatter.quantityToStackSize(s.totalPoints / s.totalPurples)
                    : "None yet");

            sizes.removeAll();
            if (s.bySize.isEmpty())
            {
                addCell(sizes, small("No raids logged yet.", ColorScheme.LIGHT_GRAY_COLOR), 0, 0, 4, GridBagConstraints.WEST);
            }
            else
            {
                String[] head = {"Size", "Raids", "Purples", "Luck"};
                for (int i = 0; i < head.length; i++)
                {
                    addCell(sizes, small(head[i], ColorScheme.MEDIUM_GRAY_COLOR), i, 0, 1,
                            i == 0 ? GridBagConstraints.WEST : GridBagConstraints.EAST);
                }
                int r = 1;
                for (Map.Entry<Integer, ModeStats.SizeStats> e : s.bySize.entrySet())
                {
                    ModeStats.SizeStats z = e.getValue();
                    addCell(sizes, small(sizeName(e.getKey()), Color.WHITE), 0, r, 1, GridBagConstraints.WEST);
                    addCell(sizes, small(QuantityFormatter.formatNumber(z.raids), Color.WHITE), 1, r, 1, GridBagConstraints.EAST);
                    addCell(sizes, small(Integer.toString(z.purples), z.purples > 0 ? PURPLE : Color.WHITE), 2, r, 1, GridBagConstraints.EAST);
                    String l = z.expected > 0 ? String.format("%.2fx", z.purples / z.expected) : "-";
                    Color lc = z.expected <= 0 ? Color.WHITE
                            : z.purples / z.expected >= 1 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR;
                    addCell(sizes, small(l, lc), 3, r, 1, GridBagConstraints.EAST);
                    r++;
                }
            }

            recent.removeAll();
            List<ModeStats.Purple> h = s.history;
            if (h.isEmpty())
            {
                recent.add(small("None yet. Good luck.", ColorScheme.LIGHT_GRAY_COLOR));
            }
            for (int i = h.size() - 1; i >= Math.max(0, h.size() - RECENT); i--)
            {
                ModeStats.Purple p = h.get(i);
                JLabel item = new JLabel(p.item);
                item.setFont(FontManager.getRunescapeSmallFont());
                item.setForeground(PURPLE);
                recent.add(item);
                recent.add(small(p.date + " · " + QuantityFormatter.formatNumber(p.raids)
                        + (p.raids == 1 ? " raid · " : " raids · ")
                        + QuantityFormatter.quantityToStackSize(p.points) + " pts", ColorScheme.LIGHT_GRAY_COLOR));
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

        private static String sizeName(int size)
        {
            switch (size)
            {
                case 1:
                    return "Solo";
                case 2:
                    return "Duo";
                case 3:
                    return "Trio";
                default:
                    return size + "-man";
            }
        }

        private static void addCell(JPanel table, JLabel label, int x, int y, int width, int anchor)
        {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = x;
            c.gridy = y;
            c.gridwidth = width;
            c.weightx = x == 0 ? 1 : 0;
            c.anchor = anchor;
            c.insets = new Insets(0, x == 0 ? 0 : 10, 0, 0);
            table.add(label, c);
        }

        private static JLabel value()
        {
            JLabel l = new JLabel("0");
            l.setFont(FontManager.getRunescapeSmallFont());
            l.setForeground(Color.WHITE);
            l.setHorizontalAlignment(SwingConstants.RIGHT);
            return l;
        }

        private static JLabel small(String text, Color color)
        {
            JLabel l = new JLabel(text);
            l.setFont(FontManager.getRunescapeSmallFont());
            l.setForeground(color);
            l.setBorder(new EmptyBorder(0, 0, 3, 0));
            return l;
        }

        private static int section(JPanel grid, int row, String text)
        {
            JLabel l = new JLabel(text.toUpperCase());
            l.setFont(FontManager.getRunescapeSmallFont());
            l.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = row;
            c.gridwidth = 2;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(row == 0 ? 0 : 6, 0, 2, 0);
            grid.add(l, c);
            return row + 1;
        }

        private static int line(JPanel grid, int row, String label, JLabel value)
        {
            JLabel l = new JLabel(label);
            l.setFont(FontManager.getRunescapeSmallFont());
            l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = row;
            c.weightx = 1;
            c.anchor = GridBagConstraints.WEST;
            grid.add(l, c);
            c = new GridBagConstraints();
            c.gridx = 1;
            c.gridy = row;
            c.anchor = GridBagConstraints.EAST;
            grid.add(value, c);
            return row + 1;
        }
    }
}
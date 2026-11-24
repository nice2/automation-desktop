package checker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;

//Shows LED states in their color: a dot per LED (filled = steady, ring = flashing, grey ring = off) and the name in the
//LED's color. Works on anything whose text is one or more state names joined by " / ", e.g. "Red Continuous" or
//"Yellow Slow Flash / Green Slow Flash".
final class LedColors {

    private LedColors() {
    }

    static final Color RED = new Color(214, 40, 40), GREEN = new Color(30, 155, 60), YELLOW = new Color(235, 180, 0), OFF = new Color(160, 160, 160);
    private static final Color YELLOW_TEXT = new Color(150, 105, 0); //yellow itself is unreadable on white

    //One LED: its color and whether it flashes
    private static final class Lamp {
        final Color color;
        final boolean flashing;

        Lamp(Color color, boolean flashing) {
            this.color = color;
            this.flashing = flashing;
        }
    }

    private static List<Lamp> lamps(String text) {
        List<Lamp> list = new ArrayList<>();
        for (String part : text.split(" / ")) {
            String t = part.toLowerCase();
            Color c = t.contains("red") ? RED : t.contains("green") ? GREEN : t.contains("yellow") || t.contains("amber") ? YELLOW : null;
            list.add(c == null ? new Lamp(OFF, true) : new Lamp(c, t.contains("flash")));
        }
        return list;
    }

    //Text color for the first LED in the text, or null when it is not a color
    private static Color textColor(String text) {
        List<Lamp> l = lamps(text);
        if (l.size() != 1 || l.get(0).color == OFF) return null;
        Color c = l.get(0).color;
        return c == YELLOW ? YELLOW_TEXT : c.darker();
    }

    private static final class Dots implements Icon {
        final List<Lamp> lamps;

        Dots(List<Lamp> lamps) {
            this.lamps = lamps;
        }

        public int getIconWidth() {
            return lamps.size() * 14;
        }

        public int getIconHeight() {
            return 12;
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < lamps.size(); i++) {
                Lamp l = lamps.get(i);
                int dx = x + i * 14 + 1;
                if (l.flashing) {
                    g2.setColor(l.color);
                    g2.setStroke(new BasicStroke(2.2f));
                    g2.drawOval(dx + 1, y + 1, 9, 9);
                } else {
                    g2.setColor(l.color);
                    g2.fillOval(dx, y, 11, 11);
                    g2.setColor(l.color.darker());
                    g2.setStroke(new BasicStroke(1f));
                    g2.drawOval(dx, y, 11, 11);
                }
            }
            g2.dispose();
        }
    }

    private static void style(JLabel label, Object value, boolean selected) {
        String text = value == null ? "" : value.toString();
        label.setText(text);
        label.setIcon(text.isBlank() ? null : new Dots(lamps(text)));
        label.setIconTextGap(6);
        Color c = textColor(text);
        if (!selected && c != null) label.setForeground(c);
    }

    @SuppressWarnings("serial")
    static final class Cell extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, col);
            if (!selected) setForeground(t.getForeground());
            style(this, value, selected);
            return this;
        }
    }

    @SuppressWarnings("serial")
    static final class Item extends DefaultListCellRenderer {
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            style(this, value, selected);
            return this;
        }
    }

    //Colored cells for these columns of a table
    static void columns(JTable t, int... columns) {
        for (int c : columns) t.getColumnModel().getColumn(c).setCellRenderer(new Cell());
    }

    static <T> JComboBox<T> combo(T[] items) {
        JComboBox<T> box = new JComboBox<>(items);
        box.setRenderer(new Item());
        return box;
    }
}

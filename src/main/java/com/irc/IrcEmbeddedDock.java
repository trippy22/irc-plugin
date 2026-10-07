package com.irc;

import net.runelite.client.ui.ContainableFrame;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

/** Adds a bottom pane to the existing root pane, without reparenting the game or its native canvas. */
final class IrcEmbeddedDock {
    static final int MIN_HEIGHT = 180;
    private static final int DIVIDER_HEIGHT = 8;
    private final JFrame window;
    private final JRootPane root;
    private final LayoutManager originalLayout;
    private final LayoutManager layout = new DockLayout();
    private final Dimension originalMinimum;
    private final JPanel pane = new JPanel(new BorderLayout());
    private final IntConsumer heightChanged;
    private int height;

    IrcEmbeddedDock(JFrame window, JPanel content, int height, IntConsumer heightChanged) {
        this.window = window;
        this.root = window.getRootPane();
        this.originalLayout = root.getLayout();
        this.originalMinimum = window.isMinimumSizeSet() ? window.getMinimumSize() : null;
        // setMinimumSize can itself enlarge a fixed-size RuneLite window. Base the final size
        // on its original bounds, otherwise that growth gets counted a second time.
        Rectangle originalBounds = window.getBounds();
        this.height = Math.max(MIN_HEIGHT, height);
        this.heightChanged = heightChanged;
        pane.setName("ircEmbeddedDock");
        JPanel divider = new JPanel();
        divider.setName("ircBottomDivider");
        divider.setBackground(new Color(65, 72, 81));
        divider.setPreferredSize(new Dimension(0, DIVIDER_HEIGHT));
        divider.setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
        divider.setToolTipText("Drag to resize IRC");
        MouseAdapter drag = new MouseAdapter() {
            private int startY;
            private int startHeight;
            @Override public void mousePressed(MouseEvent e) {
                startY = e.getYOnScreen();
                startHeight = pane.getHeight() - DIVIDER_HEIGHT;
            }
            @Override public void mouseDragged(MouseEvent e) {
                setHeight(startHeight + startY - e.getYOnScreen());
            }
        };
        divider.addMouseListener(drag);
        divider.addMouseMotionListener(drag);
        pane.add(divider, BorderLayout.NORTH);
        pane.add(content, BorderLayout.CENTER);
        // Keep the content pane, its layout and the heavyweight canvas exactly where they are.
        root.getLayeredPane().add(pane, JLayeredPane.DEFAULT_LAYER);
        root.setLayout(layout);
        updateMinimum();
        if ((window.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0) {
            Rectangle work = workArea(window);
            int target = Math.max(window.getMinimumSize().height,
                    Math.min(work.height, originalBounds.height + this.height + DIVIDER_HEIGHT));
            int y = Math.max(work.y, Math.min(originalBounds.y, work.y + work.height - target));
            window.setBounds(originalBounds.x, y, originalBounds.width, target);
        }
        refresh();
    }

    int getHeight() { return height; }

    void setHeight(int requested) {
        int available = root.getContentPane().getHeight() + pane.getHeight()
                - root.getContentPane().getMinimumSize().height - DIVIDER_HEIGHT;
        height = Math.max(MIN_HEIGHT, Math.min(requested, available));
        refresh();
        heightChanged.accept(height);
    }

    void remove() {
        int reclaimed = pane.getHeight();
        root.getLayeredPane().remove(pane);
        pane.removeAll();
        if (root.getLayout() == layout) root.setLayout(originalLayout);
        window.setMinimumSize(originalMinimum);
        if (window instanceof ContainableFrame) ((ContainableFrame) window).revalidateMinimumSize();
        if ((window.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0 && reclaimed > 0) {
            window.setSize(window.getWidth(), Math.max(window.getMinimumSize().height, window.getHeight() - reclaimed));
        }
        refresh();
    }

    private void updateMinimum() {
        if (window instanceof ContainableFrame) ((ContainableFrame) window).revalidateMinimumSize();
        else window.setMinimumSize(window.getLayout().minimumLayoutSize(window));
    }

    private void refresh() {
        root.revalidate();
        window.validate();
        root.repaint();
    }

    private static Rectangle workArea(Window window) {
        GraphicsConfiguration gc = window.getGraphicsConfiguration();
        Rectangle bounds = new Rectangle(gc.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        return bounds;
    }

    private final class DockLayout implements LayoutManager2 {
        @Override public Dimension preferredLayoutSize(Container target) {
            Dimension size = new Dimension(originalLayout.preferredLayoutSize(target));
            size.height += height + DIVIDER_HEIGHT;
            return size;
        }
        @Override public Dimension minimumLayoutSize(Container target) {
            Dimension size = new Dimension(originalLayout.minimumLayoutSize(target));
            size.height += MIN_HEIGHT + DIVIDER_HEIGHT;
            return size;
        }
        @Override public void layoutContainer(Container target) {
            originalLayout.layoutContainer(target);
            Container game = root.getContentPane();
            Rectangle area = game.getBounds();
            int dock = Math.min(height + DIVIDER_HEIGHT, Math.max(0, area.height - game.getMinimumSize().height));
            game.setBounds(area.x, area.y, area.width, area.height - dock);
            pane.setBounds(area.x, area.y + area.height - dock, area.width, dock);
        }
        @Override public void addLayoutComponent(String name, Component component) {
            originalLayout.addLayoutComponent(name, component);
        }
        @Override public void removeLayoutComponent(Component component) {
            originalLayout.removeLayoutComponent(component);
        }
        @Override public void addLayoutComponent(Component component, Object constraints) {
            if (originalLayout instanceof LayoutManager2) ((LayoutManager2) originalLayout).addLayoutComponent(component, constraints);
        }
        @Override public Dimension maximumLayoutSize(Container target) { return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE); }
        @Override public float getLayoutAlignmentX(Container target) { return 0; }
        @Override public float getLayoutAlignmentY(Container target) { return 0; }
        @Override public void invalidateLayout(Container target) {
            if (originalLayout instanceof LayoutManager2) ((LayoutManager2) originalLayout).invalidateLayout(target);
        }
    }
}

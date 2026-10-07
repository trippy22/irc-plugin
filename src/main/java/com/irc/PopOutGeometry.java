package com.irc;

import java.awt.Rectangle;
import java.util.List;

/**
 * Where the pop-out was and how big, saved in the config so it reopens the same after a restart.
 * {@link #bounds} are the window's normal (unmaximized) bounds, so un-maximizing a window that
 * reopened maximized still lands somewhere sensible.
 */
final class PopOutGeometry {
    static final String CONFIG_KEY = "popOutBounds";

    final Rectangle bounds;
    final boolean maximized;
    final int dockHeight;
    final boolean embedded;

    PopOutGeometry(Rectangle bounds, boolean maximized) {
        this(bounds, maximized, 0);
    }

    PopOutGeometry(Rectangle bounds, boolean maximized, int dockHeight) {
        this(bounds, maximized, dockHeight, false);
    }

    PopOutGeometry(Rectangle bounds, boolean maximized, int dockHeight, boolean embedded) {
        this.bounds = new Rectangle(bounds);
        this.maximized = maximized;
        this.dockHeight = dockHeight;
        this.embedded = embedded;
    }

    /** Floating bounds, optionally followed by ",maximized", ",bottom,height" or ",embedded,height". */
    String serialize() {
        return bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height
                + (dockHeight > 0 ? (embedded ? ",embedded," : ",bottom,") + dockHeight : maximized ? ",maximized" : "");
    }

    /** Null for anything unreadable, so a bad value falls back to the default placement. */
    static PopOutGeometry parse(String value) {
        if (value == null) return null;
        String[] parts = value.trim().split(",");
        boolean embedded = parts.length == 6 && "embedded".equals(parts[4]);
        boolean bottom = parts.length == 6 && ("bottom".equals(parts[4]) || embedded);
        if (parts.length != 4 && !(parts.length == 5 && "maximized".equals(parts[4])) && !bottom) return null;
        try {
            Rectangle bounds = new Rectangle(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()));
            if (bounds.width <= 0 || bounds.height <= 0) return null;
            int height = bottom ? Integer.parseInt(parts[5].trim()) : 0;
            if (bottom && height <= 0) return null;
            return new PopOutGeometry(bounds, parts.length == 5, height, embedded);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether the middle of the title bar is on one of {@code screens}, so the window can be dragged. */
    boolean titleBarVisible(List<Rectangle> screens) {
        for (Rectangle screen : screens) {
            if (screen.contains(bounds.x + bounds.width / 2, bounds.y + 10)) return true;
        }
        return false;
    }
}

package com.irc;

import java.awt.Rectangle;

/** Screen coordinates use AWT's logical pixels, including on monitors with different scaling. */
final class BottomDockGeometry {
    static final int MIN_HEIGHT = 240;
    static final int DEFAULT_HEIGHT = 280;
    static final int SNAP_DISTANCE = 24;

    private BottomDockGeometry() { }

    static Rectangle below(Rectangle owner, Rectangle workArea, int requestedHeight) {
        int height = Math.min(workArea.height, Math.max(MIN_HEIGHT, requestedHeight));
        int width = Math.min(workArea.width, Math.max(640, owner.width));
        int x = Math.max(workArea.x, Math.min(owner.x, workArea.x + workArea.width - width));
        int y = Math.max(workArea.y, Math.min(owner.y + owner.height, workArea.y + workArea.height - height));
        return new Rectangle(x, y, width, height);
    }

    static boolean nearBottom(Rectangle floating, Rectangle owner) {
        int overlap = Math.min(floating.x + floating.width, owner.x + owner.width)
                - Math.max(floating.x, owner.x);
        return Math.abs((long) floating.y - owner.y - owner.height) <= SNAP_DISTANCE
                && overlap >= Math.min(floating.width, owner.width) / 2;
    }
}

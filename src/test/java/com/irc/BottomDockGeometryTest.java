package com.irc;

import org.junit.Test;
import java.awt.Rectangle;
import static org.junit.Assert.*;

public class BottomDockGeometryTest {
    @Test
    public void attachesBelowAndKeepsTaskbarClear() {
        Rectangle work = new Rectangle(0, 0, 1920, 1040);
        assertEquals(new Rectangle(100, 600, 800, 280),
                BottomDockGeometry.below(new Rectangle(100, 100, 800, 500), work, 280));
        assertEquals(new Rectangle(100, 760, 800, 280),
                BottomDockGeometry.below(new Rectangle(100, 100, 800, 900), work, 280));
    }

    @Test
    public void clampsOnSecondaryMonitorsAndSmallWorkAreas() {
        Rectangle work = new Rectangle(-1280, 40, 1280, 680);
        Rectangle result = BottomDockGeometry.below(new Rectangle(-1400, 500, 1400, 600), work, 4000);
        assertEquals(work, result);
        assertEquals(240, BottomDockGeometry.below(new Rectangle(-1200, 40, 800, 400), work, 10).height);
    }

    @Test
    public void snappingRequiresProximityAndHorizontalOverlap() {
        Rectangle owner = new Rectangle(100, 100, 800, 500);
        assertTrue(BottomDockGeometry.nearBottom(new Rectangle(200, 620, 700, 300), owner));
        assertFalse(BottomDockGeometry.nearBottom(new Rectangle(200, 630, 700, 300), owner));
        assertFalse(BottomDockGeometry.nearBottom(new Rectangle(850, 600, 700, 300), owner));
    }

    @Test
    public void remembersAttachmentWithoutLosingFloatingBounds() {
        Rectangle floating = new Rectangle(-1000, 100, 900, 600);
        PopOutGeometry restored = PopOutGeometry.parse(new PopOutGeometry(floating, false, 310).serialize());
        assertNotNull(restored);
        assertEquals(floating, restored.bounds);
        assertEquals(310, restored.dockHeight);
        assertFalse(restored.maximized);
        assertNull(PopOutGeometry.parse("1,2,900,600,bottom,-5"));
        assertNull(PopOutGeometry.parse("1,2,900,600,bottom,nope"));
    }
}

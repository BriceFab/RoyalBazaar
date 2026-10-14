package com.mystipixel.royalbazaar.market;

/** Puts a price or freeze on an item the way the plugin does: by applying a newer database row. */
public final class Rows {

    private Rows() {
    }

    public static void setMid(MarketItem item, double mid) {
        item.apply(new StateRow(item.id(), mid, 0, item.frozen(), item.version() + 1, 0));
    }

    public static void setFrozen(MarketItem item, boolean frozen) {
        item.apply(new StateRow(item.id(), item.mid(), 0, frozen, item.version() + 1, 0));
    }
}

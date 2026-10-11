package com.mystipixel.royalbazaar.market;

import java.util.Locale;

/** Which directions players may trade an item in. Comes from the category file, never persisted. */
public enum TradeMode {
    BOTH,
    BUY_ONLY,
    SELL_ONLY;

    public boolean allows(TradeSide side) {
        return switch (side) {
            case BUY -> this != SELL_ONLY;
            case SELL -> this != BUY_ONLY;
        };
    }

    // config value: both, buy_only or sell_only (case and - vs _ ignored); null when unrecognised
    public static TradeMode parse(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (TradeMode mode : values()) {
            if (mode.name().equals(key)) {
                return mode;
            }
        }
        return null;
    }
}

package com.mystipixel.royalbazaar.market;

/**
 * Units bought and sold in the last 24 hours across every server: the totals from {@code rb_transactions}
 * at the last refresh, plus this server's own trades since then. Main-thread only.
 */
public final class VolumeWindow {

    private long baseBought;
    private long baseSold;
    private long recentBought;
    private long recentSold;

    public void recordBuy(long qty) {
        recentBought += qty;
    }

    public void recordSell(long qty) {
        recentSold += qty;
    }

    /** New database totals; this server's trades are in them, so the local counts start again. */
    public void reset(long bought, long sold) {
        baseBought = bought;
        baseSold = sold;
        recentBought = 0;
        recentSold = 0;
    }

    public long bought24h() {
        return baseBought + recentBought;
    }

    public long sold24h() {
        return baseSold + recentSold;
    }

    public long net24h() {
        return bought24h() - sold24h();
    }
}

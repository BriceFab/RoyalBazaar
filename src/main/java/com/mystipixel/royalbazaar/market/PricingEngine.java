package com.mystipixel.royalbazaar.market;

/**
 * Stateless bazaar pricing on an AMM-style curve: buying {@code q} moves {@code mid' = mid · e^(q/E)},
 * selling moves it down. Cost is the integral along the curve, so round-trips never profit.
 */
public final class PricingEngine {

    private PricingEngine() {
    }

    public static double buyPrice(MarketItem i) {
        return i.mid() * (1.0 + i.spread() / 2.0);
    }

    public static double sellPrice(MarketItem i) {
        return i.mid() * (1.0 - i.spread() / 2.0);
    }

    /** Total cost to buy {@code q} units at the current mid, including the buy-side spread. */
    public static double buyCost(MarketItem i, long q) {
        return buyCost(i.curve(), i.mid(), q);
    }

    public static double buyCost(Curve c, double mid, long q) {
        double e = c.elasticity();
        double raw = e * mid * Math.expm1(q / e);          // E·mid·(e^(q/E) − 1)
        return raw * (1.0 + c.spread() / 2.0);
    }

    /** Total proceeds from selling {@code q} units at the current mid, less the sell-side spread. */
    public static double sellProceeds(MarketItem i, long q) {
        return sellProceeds(i.curve(), i.mid(), q);
    }

    public static double sellProceeds(Curve c, double mid, long q) {
        double e = c.elasticity();
        double raw = e * mid * -Math.expm1(-q / e);        // E·mid·(1 − e^(−q/E))
        return raw * (1.0 - c.spread() / 2.0);
    }

    public static double midAfterBuy(MarketItem i, long q) {
        return midAfterBuy(i.curve(), i.mid(), q);
    }

    public static double midAfterBuy(Curve c, double mid, long q) {
        return c.clamp(mid * Math.exp(q / c.elasticity()));
    }

    public static double midAfterSell(MarketItem i, long q) {
        return midAfterSell(i.curve(), i.mid(), q);
    }

    public static double midAfterSell(Curve c, double mid, long q) {
        return c.clamp(mid * Math.exp(-q / c.elasticity()));
    }

    /** Pull mid a fraction {@code reversionRate} of the way back toward base, in log-space. */
    public static double revert(MarketItem i) {
        return revert(i.curve(), i.mid());
    }

    public static double revert(Curve c, double mid) {
        if (mid <= 0 || c.basePrice() <= 0) {
            return c.basePrice();
        }
        double logMid = Math.log(mid);
        double logBase = Math.log(c.basePrice());
        double next = Math.exp(logMid + (logBase - logMid) * c.reversionRate());
        return c.clamp(next);
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

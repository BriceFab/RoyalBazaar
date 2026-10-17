package com.mystipixel.royalbazaar.market;

/**
 * An item's pricing parameters, without its live price. Immutable, so the database thread can price
 * trades and reversion from it while the {@link MarketItem} itself stays on the main thread.
 */
public record Curve(String id, double basePrice, double spread, double elasticity, double reversionRate,
                    double floor, double ceiling) {

    public double clamp(double mid) {
        return PricingEngine.clamp(mid, floor, ceiling);
    }

    public double startingMid() {
        return clamp(basePrice);
    }
}

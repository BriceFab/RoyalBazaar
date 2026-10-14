package com.mystipixel.royalbazaar.market;

/** One {@code rb_state} row as read from the database. */
public record StateRow(String id, double mid, double midYesterday, boolean frozen, long version, long updatedAt) {
}

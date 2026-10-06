package me.simpleauction;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class Listing {
    public final String id;
    public final UUID seller;
    public final String sellerName;
    public final ItemStack item;
    public final double price;
    public final long createdAt;

    public Listing(String id, UUID seller, String sellerName, ItemStack item, double price, long createdAt) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.item = item;
        this.price = price;
        this.createdAt = createdAt;
    }
}

package ru.simpleauction;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Один лот. Активен до expireAt, потом лежит у владельца в «истёкших». */
public final class Lot {
    public final UUID id;
    public final UUID seller;
    public final String sellerName;
    public final ItemStack item;
    public final double price;
    public final long listedAt;
    public long expireAt;
    /** Получал ли владелец уведомление об истечении (в файл не пишется). */
    public boolean notified;

    public Lot(UUID id, UUID seller, String sellerName, ItemStack item, double price, long listedAt, long expireAt) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.item = item;
        this.price = price;
        this.listedAt = listedAt;
        this.expireAt = expireAt;
    }

    public boolean isActive(long now) {
        return now < expireAt;
    }
}

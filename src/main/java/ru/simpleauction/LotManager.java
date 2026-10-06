package ru.simpleauction;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

/** Хранилище лотов в plugins/SimpleAuction/lots.yml. Всё вызывается из основного потока. */
public final class LotManager {
    private final SimpleAuction plugin;
    private final Map<UUID, Lot> lots = new LinkedHashMap<>();
    private final File file;

    public LotManager(SimpleAuction plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lots.yml");
    }

    public void load() {
        lots.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("lots");
        if (sec == null) return;
        long now = System.currentTimeMillis();
        for (String key : sec.getKeys(false)) {
            try {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) continue;
                ItemStack item = s.getItemStack("item");
                if (item == null) {
                    plugin.getLogger().warning("Лот " + key + " пропущен: не удалось прочитать предмет");
                    continue;
                }
                Lot lot = new Lot(UUID.fromString(key), UUID.fromString(s.getString("seller", "")),
                        s.getString("seller-name", "?"), item, s.getDouble("price"),
                        s.getLong("listed-at"), s.getLong("expire-at"));
                lot.notified = !lot.isActive(now);
                lots.put(lot.id, lot);
            } catch (Exception ex) {
                plugin.getLogger().warning("Лот " + key + " пропущен: " + ex.getMessage());
            }
        }
        purge();
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Lot l : lots.values()) {
            String p = "lots." + l.id + ".";
            y.set(p + "seller", l.seller.toString());
            y.set(p + "seller-name", l.sellerName);
            y.set(p + "item", l.item);
            y.set(p + "price", l.price);
            y.set(p + "listed-at", l.listedAt);
            y.set(p + "expire-at", l.expireAt);
        }
        try {
            plugin.getDataFolder().mkdirs();
            File tmp = new File(plugin.getDataFolder(), "lots.yml.tmp");
            y.save(tmp);
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().severe("Не удалось сохранить lots.yml: " + ex.getMessage());
        }
    }

    public long activeMillis() {
        return (long) (plugin.getConfig().getDouble("active-hours", 24) * 3_600_000L);
    }

    public long expiredMillis() {
        return (long) (plugin.getConfig().getDouble("expired-hours", 24) * 3_600_000L);
    }

    public long deleteAt(Lot l) {
        return l.expireAt + expiredMillis();
    }

    public void add(Lot lot) {
        lots.put(lot.id, lot);
    }

    public Lot get(UUID id) {
        return lots.get(id);
    }

    public Lot remove(UUID id) {
        return lots.remove(id);
    }

    /** Активные лоты, новые сверху. */
    public List<Lot> active(long now) {
        return lots.values().stream()
                .filter(l -> l.isActive(now))
                .sorted(Comparator.comparingLong((Lot l) -> l.listedAt).reversed())
                .collect(Collectors.toList());
    }

    /** Истёкшие лоты игрока, ещё не удалённые. */
    public List<Lot> expiredOf(UUID owner, long now) {
        return lots.values().stream()
                .filter(l -> l.seller.equals(owner) && !l.isActive(now) && now < deleteAt(l))
                .sorted(Comparator.comparingLong((Lot l) -> l.expireAt).reversed())
                .collect(Collectors.toList());
    }

    public int countActive(UUID owner, long now) {
        int c = 0;
        for (Lot l : lots.values()) if (l.seller.equals(owner) && l.isActive(now)) c++;
        return c;
    }

    /** Удаляет лоты, у которых вышел и срок хранения. Возвращает количество. */
    public int purge() {
        long now = System.currentTimeMillis();
        int before = lots.size();
        lots.values().removeIf(l -> now >= deleteAt(l));
        int removed = before - lots.size();
        if (removed > 0) save();
        return removed;
    }
}

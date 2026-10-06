package ru.simpleauction;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AuctionGui implements Listener {
    static final int PER_PAGE = 45;

    public enum View { MAIN, EXPIRED }

    static final class Holder implements InventoryHolder {
        final View view;
        final int page;
        final int pages;
        final UUID[] ids = new UUID[PER_PAGE];
        Inventory inventory;

        Holder(View view, int page, int pages) {
            this.view = view;
            this.page = page;
            this.pages = pages;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SimpleAuction plugin;

    public AuctionGui(SimpleAuction plugin) {
        this.plugin = plugin;
    }

    // ───────────────────────── отрисовка ─────────────────────────

    public void open(Player p, View view, int page) {
        FileConfiguration cfg = plugin.getConfig();
        LotManager lm = plugin.lots();
        long now = System.currentTimeMillis();

        List<Lot> list = view == View.MAIN ? lm.active(now) : lm.expiredOf(p.getUniqueId(), now);
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(1, Math.min(page, pages));

        Holder h = new Holder(view, page, pages);
        String title = plugin.msg(view == View.MAIN ? "gui-title-main" : "gui-title-expired",
                "page", page, "pages", pages);
        Inventory inv = Bukkit.createInventory(h, 54, title);
        h.inventory = inv;

        int from = (page - 1) * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < list.size(); i++) {
            Lot lot = list.get(from + i);
            h.ids[i] = lot.id;
            inv.setItem(i, lotItem(p, lot, view, now));
        }

        // нижний ряд
        if (cfg.getBoolean("gui.filler.enabled", true)) {
            ItemStack filler = button(material("gui.filler.material", Material.GRAY_STAINED_GLASS_PANE),
                    SimpleAuction.color(cfg.getString("gui.filler.name", " ")), null, false);
            for (int s = PER_PAGE; s < 54; s++) inv.setItem(s, filler);
        }

        set(inv, cfg.getInt("gui.prev-button.slot", 48),
                button(material("gui.prev-button.material", Material.ARROW),
                        plugin.msg("btn-prev", "prev", Math.max(1, page - 1)), null, false));
        set(inv, cfg.getInt("gui.next-button.slot", 50),
                button(material("gui.next-button.material", Material.ARROW),
                        plugin.msg("btn-next", "next", Math.min(pages, page + 1)), null, false));

        if (view == View.MAIN) {
            int used = lm.countActive(p.getUniqueId(), now);
            int expiredCount = lm.expiredOf(p.getUniqueId(), now).size();

            set(inv, cfg.getInt("gui.refresh-button.slot", 49),
                    button(material("gui.refresh-button.material", Material.SUNFLOWER),
                            SimpleAuction.color(cfg.getString("gui.refresh-button.name", "&e&l☀ Обновить")),
                            coloredList("gui.refresh-button.lore",
                                    "page", page, "pages", pages, "total", list.size()),
                            cfg.getBoolean("gui.refresh-button.glow", true)));

            set(inv, cfg.getInt("gui.expired-button.slot", 53),
                    button(material("gui.expired-button.material", Material.CHEST),
                            plugin.msg("btn-expired", "count", expiredCount), null,
                            cfg.getBoolean("gui.expired-button.glow", false)));

            set(inv, cfg.getInt("slots-book.slot", 45),
                    button(material("slots-book.material", Material.ENCHANTED_BOOK),
                            SimpleAuction.color(cfg.getString("slots-book.name", "&6Лимиты слотов")),
                            coloredList("slots-book.lore", "max", plugin.slotLimit(p), "used", used),
                            cfg.getBoolean("slots-book.glow", true)));
        } else {
            set(inv, cfg.getInt("gui.back-button.slot", 49),
                    button(material("gui.back-button.material", Material.BARRIER),
                            plugin.msg("btn-back"), null, false));
        }

        p.openInventory(inv);
    }

    private ItemStack lotItem(Player viewer, Lot lot, View view, long now) {
        ItemStack it = lot.item.clone();
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        List<String> lore = m.hasLore() && m.getLore() != null ? new ArrayList<>(m.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add(plugin.msg("lore-price", "price", plugin.money(lot.price)));
        if (view == View.MAIN) {
            lore.add(plugin.msg("lore-seller", "player", lot.sellerName));
            lore.add(plugin.msg("lore-time-left", "time", SimpleAuction.formatTime(lot.expireAt - now)));
            lore.add("");
            boolean own = lot.seller.equals(viewer.getUniqueId());
            lore.add(plugin.msg(own ? "lore-click-cancel" : "lore-click-buy"));
        } else {
            lore.add(plugin.msg("lore-delete-in", "time",
                    SimpleAuction.formatTime(plugin.lots().deleteAt(lot) - now)));
            lore.add("");
            lore.add(plugin.msg("lore-click-claim"));
        }
        m.setLore(lore);
        it.setItemMeta(m);
        return it;
    }

    private Material material(String path, Material def) {
        Material m = Material.matchMaterial(plugin.getConfig().getString(path, def.name()));
        return m == null || m.isAir() ? def : m;
    }

    private List<String> coloredList(String path, Object... kv) {
        List<String> out = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList(path)) {
            out.add(SimpleAuction.color(SimpleAuction.replace(line, kv)));
        }
        return out;
    }

    private void set(Inventory inv, int slot, ItemStack item) {
        if (slot >= PER_PAGE && slot < 54) inv.setItem(slot, item);
    }

    private ItemStack button(Material mat, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        m.setDisplayName(name);
        if (lore != null) m.setLore(lore);
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_POTION_EFFECTS);
        if (glow) {
            Enchantment e = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
            if (e != null) m.addEnchant(e, 1, true);
        }
        it.setItemMeta(m);
        return it;
    }

    // ───────────────────────── книга ─────────────────────────

    public void openBook(Player p) {
        FileConfiguration cfg = plugin.getConfig();
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta m = (BookMeta) book.getItemMeta();
        if (m == null) return;
        m.setTitle(SimpleAuction.color(cfg.getString("slots-book.title", "Слоты")));
        m.setAuthor(SimpleAuction.color(cfg.getString("slots-book.author", "Аукцион")));
        for (String page : cfg.getStringList("slots-book.pages")) {
            m.addPage(SimpleAuction.color(page));
        }
        book.setItemMeta(m);
        p.openBook(book);
    }

    // ───────────────────────── клики ─────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != top) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 54) return;
        if (slot < PER_PAGE) lotClick(p, h, slot, e.getClick());
        else bottomClick(p, h, slot);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    private void bottomClick(Player p, Holder h, int slot) {
        FileConfiguration cfg = plugin.getConfig();
        if (slot == cfg.getInt("gui.prev-button.slot", 48)) {
            if (h.page <= 1) p.sendMessage(plugin.msg("no-prev-page"));
            else open(p, h.view, h.page - 1);
        } else if (slot == cfg.getInt("gui.next-button.slot", 50)) {
            if (h.page >= h.pages) p.sendMessage(plugin.msg("no-next-page"));
            else open(p, h.view, h.page + 1);
        } else if (h.view == View.MAIN) {
            if (slot == cfg.getInt("gui.refresh-button.slot", 49)) {
                open(p, View.MAIN, h.page);
                p.sendMessage(plugin.msg("refreshed"));
            } else if (slot == cfg.getInt("gui.expired-button.slot", 53)) {
                if (!plugin.can(p, "expired")) p.sendMessage(plugin.msg("no-permission"));
                else open(p, View.EXPIRED, 1);
            } else if (slot == cfg.getInt("slots-book.slot", 45)) {
                if (!plugin.can(p, "slots-book")) {
                    p.sendMessage(plugin.msg("no-permission"));
                } else {
                    p.closeInventory();
                    Bukkit.getScheduler().runTask(plugin, () -> openBook(p));
                }
            }
        } else if (slot == cfg.getInt("gui.back-button.slot", 49)) {
            open(p, View.MAIN, 1);
        }
    }

    private void lotClick(Player p, Holder h, int slot, ClickType click) {
        UUID id = h.ids[slot];
        if (id == null) return;
        long now = System.currentTimeMillis();
        Lot lot = plugin.lots().get(id);

        if (lot == null) {
            p.sendMessage(plugin.msg("lot-gone"));
            open(p, h.view, h.page);
            return;
        }

        if (h.view == View.EXPIRED) {
            claim(p, h, lot, now);
            return;
        }

        if (!lot.isActive(now)) {
            p.sendMessage(plugin.msg("lot-gone"));
            open(p, h.view, h.page);
            return;
        }

        boolean own = lot.seller.equals(p.getUniqueId());
        if (!own && click == ClickType.SHIFT_RIGHT && plugin.can(p, "admin")) {
            // админ: снять чужой лот, предмет уйдёт в «истёкшие» владельца
            lot.expireAt = now;
            plugin.lots().save();
            p.sendMessage(plugin.msg("cancelled", "item", plugin.itemName(lot.item)));
            open(p, h.view, h.page);
            return;
        }
        if (own) cancel(p, h, lot);
        else buy(p, h, lot);
    }

    private void cancel(Player p, Holder h, Lot lot) {
        if (!plugin.can(p, "cancel")) {
            p.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (!canFit(p.getInventory(), lot.item)) {
            p.sendMessage(plugin.msg("no-space"));
            return;
        }
        plugin.lots().remove(lot.id);
        plugin.lots().save();
        give(p, lot.item);
        p.sendMessage(plugin.msg("cancelled", "item", plugin.itemName(lot.item)));
        open(p, h.view, h.page);
    }

    private void claim(Player p, Holder h, Lot lot, long now) {
        if (!plugin.can(p, "expired")) {
            p.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (!lot.seller.equals(p.getUniqueId()) || lot.isActive(now)) {
            p.sendMessage(plugin.msg("lot-gone"));
            open(p, h.view, h.page);
            return;
        }
        if (!canFit(p.getInventory(), lot.item)) {
            p.sendMessage(plugin.msg("no-space"));
            return;
        }
        plugin.lots().remove(lot.id);
        plugin.lots().save();
        give(p, lot.item);
        p.sendMessage(plugin.msg("claimed", "item", plugin.itemName(lot.item)));
        open(p, h.view, h.page);
    }

    private void buy(Player p, Holder h, Lot lot) {
        if (!plugin.can(p, "buy")) {
            p.sendMessage(plugin.msg("no-permission"));
            return;
        }
        Economy eco = plugin.economy();
        if (eco == null) {
            p.sendMessage(plugin.msg("no-economy"));
            return;
        }
        if (!canFit(p.getInventory(), lot.item)) {
            p.sendMessage(plugin.msg("no-space"));
            return;
        }
        if (!eco.has(p, lot.price)) {
            p.sendMessage(plugin.msg("no-money", "price", plugin.money(lot.price)));
            return;
        }
        EconomyResponse withdraw = eco.withdrawPlayer(p, lot.price);
        if (!withdraw.transactionSuccess()) {
            p.sendMessage(plugin.msg("no-money", "price", plugin.money(lot.price)));
            return;
        }

        // лот убираем и сохраняем до выдачи — повторно его купить уже нельзя
        plugin.lots().remove(lot.id);
        plugin.lots().save();
        give(p, lot.item);

        double commission = Math.max(0, Math.min(100, plugin.getConfig().getDouble("commission-percent", 5)));
        double payout = Math.round(lot.price * (100.0 - commission)) / 100.0;
        EconomyResponse deposit = eco.depositPlayer(Bukkit.getOfflinePlayer(lot.seller), payout);
        if (!deposit.transactionSuccess()) {
            plugin.getLogger().severe("Не удалось выплатить " + payout + " игроку " + lot.sellerName
                    + " (" + lot.seller + ") за лот " + lot.id + ": " + deposit.errorMessage);
        }

        String itemName = plugin.itemName(lot.item);
        p.sendMessage(plugin.msg("bought", "item", itemName, "price", plugin.money(lot.price)));
        Player seller = Bukkit.getPlayer(lot.seller);
        if (seller != null) {
            seller.sendMessage(plugin.msg("sold", "player", p.getName(), "item", itemName,
                    "money", plugin.money(payout)));
        }
        open(p, h.view, h.page);
    }

    // ───────────────────────── инвентарь ─────────────────────────

    private static boolean canFit(PlayerInventory inv, ItemStack item) {
        int remaining = item.getAmount();
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) remaining -= item.getMaxStackSize();
            else if (s.isSimilar(item)) remaining -= Math.max(0, s.getMaxStackSize() - s.getAmount());
            if (remaining <= 0) return true;
        }
        return false;
    }

    private static void give(Player p, ItemStack item) {
        var left = p.getInventory().addItem(item.clone());
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }
}

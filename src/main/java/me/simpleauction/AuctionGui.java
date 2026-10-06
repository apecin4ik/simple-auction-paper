package me.simpleauction;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AuctionGui implements Listener {

    public enum Type { MAIN, EXPIRED }

    public static class AuctionHolder implements InventoryHolder {
        final Type type;
        final int page;
        final Map<Integer, String> slots = new HashMap<>();
        Inventory inv;

        AuctionHolder(Type type, int page) {
            this.type = type;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_SWITCH = 49;
    private static final int SLOT_NEXT = 53;

    private final SimpleAuction plugin;

    public AuctionGui(SimpleAuction plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------- rendering

    public void open(Player p, Type type, int page) {
        List<Listing> list = type == Type.MAIN
                ? plugin.activeListings()
                : plugin.expiredOf(p.getUniqueId());

        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        AuctionHolder holder = new AuctionHolder(type, page);
        String title = plugin.msg(type == Type.MAIN ? "gui-title-main" : "gui-title-expired",
                "page", String.valueOf(page + 1), "pages", String.valueOf(pages));
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.inv = inv;

        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = page * PAGE_SIZE + i;
            if (idx >= list.size()) break;
            Listing l = list.get(idx);
            inv.setItem(i, display(l, type, p));
            holder.slots.put(i, l.id);
        }

        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int s = 45; s < 54; s++) inv.setItem(s, filler);

        if (page > 0) inv.setItem(SLOT_PREV, button(Material.ARROW, plugin.msg("btn-prev")));
        if (page < pages - 1) inv.setItem(SLOT_NEXT, button(Material.ARROW, plugin.msg("btn-next")));

        if (type == Type.MAIN) {
            int count = plugin.expiredOf(p.getUniqueId()).size();
            inv.setItem(SLOT_SWITCH, button(Material.CHEST,
                    plugin.msg("btn-expired", "count", String.valueOf(count))));
        } else {
            inv.setItem(SLOT_SWITCH, button(Material.BARRIER, plugin.msg("btn-back")));
        }

        p.openInventory(inv);
    }

    private ItemStack display(Listing l, Type type, Player viewer) {
        ItemStack d = l.item.clone();
        ItemMeta m = d.getItemMeta();
        if (m == null) return d;

        List<String> lore = m.hasLore() && m.getLore() != null ? new ArrayList<>(m.getLore()) : new ArrayList<>();
        lore.add(" ");
        lore.add(plugin.msg("lore-price", "price", formatPrice(l.price)));

        if (type == Type.MAIN) {
            lore.add(plugin.msg("lore-seller", "player", l.sellerName));
            lore.add(plugin.msg("lore-time-left", "time", SimpleAuction.fmtTime(plugin.activeLeft(l))));
            lore.add(" ");
            lore.add(plugin.msg(l.seller.equals(viewer.getUniqueId()) ? "lore-click-cancel" : "lore-click-buy"));
        } else {
            lore.add(plugin.msg("lore-delete-in", "time", SimpleAuction.fmtTime(plugin.expiredLeft(l))));
            lore.add(" ");
            lore.add(plugin.msg("lore-click-claim"));
        }

        m.setLore(lore);
        d.setItemMeta(m);
        return d;
    }

    private ItemStack button(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(name);
            it.setItemMeta(m);
        }
        return it;
    }

    private String formatPrice(double price) {
        Economy eco = plugin.economy();
        return eco != null ? eco.format(price) : String.valueOf(price);
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof AuctionHolder h)) return;

        // Внутри аукциона нельзя ничего перекладывать
        e.setCancelled(true);

        if (e.getClickedInventory() == null || e.getClickedInventory() != top) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int slot = e.getRawSlot();

        if (slot == SLOT_PREV && h.page > 0) {
            reopen(p, h.type, h.page - 1);
            return;
        }
        if (slot == SLOT_NEXT && top.getItem(SLOT_NEXT) != null
                && top.getItem(SLOT_NEXT).getType() == Material.ARROW) {
            reopen(p, h.type, h.page + 1);
            return;
        }
        if (slot == SLOT_SWITCH) {
            reopen(p, h.type == Type.MAIN ? Type.EXPIRED : Type.MAIN, 0);
            return;
        }

        String id = h.slots.get(slot);
        if (id == null) return;

        if (h.type == Type.MAIN) {
            buyOrCancel(p, id);
        } else {
            claim(p, id);
        }
        reopen(p, h.type, h.page);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof AuctionHolder)) return;
        int topSize = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < topSize) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            int count = plugin.expiredOf(p.getUniqueId()).size();
            if (count > 0) p.sendMessage(plugin.msg("expired-notify", "count", String.valueOf(count)));
        }, 60L);
    }

    private void reopen(Player p, Type type, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> open(p, type, page));
    }

    // --------------------------------------------------------------- actions

    private void buyOrCancel(Player p, String id) {
        Listing l = plugin.get(id);
        if (l == null || !plugin.isActive(l)) {
            p.sendMessage(plugin.msg("lot-gone"));
            return;
        }

        // Свой лот: снять с продажи
        if (l.seller.equals(p.getUniqueId())) {
            if (!SimpleAuction.hasSpace(p, l.item)) {
                p.sendMessage(plugin.msg("no-space"));
                return;
            }
            plugin.remove(id);
            plugin.save();
            SimpleAuction.give(p, l.item.clone());
            p.sendMessage(plugin.msg("cancelled", "item", SimpleAuction.itemName(l.item)));
            return;
        }

        if (!p.hasPermission("auction.buy")) {
            p.sendMessage(plugin.msg("no-permission"));
            return;
        }

        Economy eco = plugin.economy();
        if (eco == null) {
            p.sendMessage(plugin.msg("no-economy"));
            return;
        }
        if (!eco.has(p, l.price)) {
            p.sendMessage(plugin.msg("no-money", "price", eco.format(l.price)));
            return;
        }
        if (!SimpleAuction.hasSpace(p, l.item)) {
            p.sendMessage(plugin.msg("no-space"));
            return;
        }

        EconomyResponse withdraw = eco.withdrawPlayer(p, l.price);
        if (!withdraw.transactionSuccess()) {
            p.sendMessage(plugin.msg("no-money", "price", eco.format(l.price)));
            return;
        }

        // Всё выполняется в главном потоке, так что лот не может быть куплен дважды.
        // Лот удаляется и сохраняется ДО выдачи предмета, чтобы сбой не привёл к дюпу.
        plugin.remove(id);
        plugin.save();

        double commission = plugin.getConfig().getDouble("commission-percent", 5);
        double payout = SimpleAuction.round2(l.price * (1.0 - commission / 100.0));
        eco.depositPlayer(Bukkit.getOfflinePlayer(l.seller), payout);

        SimpleAuction.give(p, l.item.clone());
        String itemName = SimpleAuction.itemName(l.item);
        p.sendMessage(plugin.msg("bought", "item", itemName, "price", eco.format(l.price)));

        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null && seller.isOnline()) {
            seller.sendMessage(plugin.msg("sold", "player", p.getName(), "item", itemName,
                    "money", eco.format(payout)));
        }
    }

    private void claim(Player p, String id) {
        Listing l = plugin.get(id);
        if (l == null || !plugin.isExpired(l) || !l.seller.equals(p.getUniqueId())) {
            p.sendMessage(plugin.msg("lot-gone"));
            return;
        }
        if (!SimpleAuction.hasSpace(p, l.item)) {
            p.sendMessage(plugin.msg("no-space"));
            return;
        }
        plugin.remove(id);
        plugin.save();
        SimpleAuction.give(p, l.item.clone());
        p.sendMessage(plugin.msg("claimed", "item", SimpleAuction.itemName(l.item)));
    }
}

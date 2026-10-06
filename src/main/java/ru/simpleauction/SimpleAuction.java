package ru.simpleauction;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class SimpleAuction extends JavaPlugin implements Listener {
    private Economy economy;
    private LotManager lots;
    private AuctionGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        lots = new LotManager(this);
        lots.load();
        gui = new AuctionGui(this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(this, this);

        AuctionCommand cmd = new AuctionCommand(this);
        PluginCommand pc = getCommand("ah");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        if (economy() == null) {
            getLogger().warning("Экономика Vault не найдена. Покупка и продажа заработают, когда она появится.");
        }
        // раз в минуту: чистим просроченное и уведомляем игроков
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            lots.purge();
            for (Player p : Bukkit.getOnlinePlayers()) notifyExpired(p, false);
        }, 20L * 60, 20L * 60);
    }

    @Override
    public void onDisable() {
        if (lots != null) lots.save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) notifyExpired(p, true);
        }, 40L);
    }

    public void notifyExpired(Player p, boolean force) {
        List<Lot> ex = lots.expiredOf(p.getUniqueId(), System.currentTimeMillis());
        if (ex.isEmpty()) return;
        boolean fresh = ex.stream().anyMatch(l -> !l.notified);
        if (!force && !fresh) return;
        ex.forEach(l -> l.notified = true);
        p.sendMessage(msg("expired-notify", "count", ex.size()));
    }

    public void reload() {
        reloadConfig();
    }

    // ───────────── доступ к частям ─────────────

    public LotManager lots() {
        return lots;
    }

    public AuctionGui gui() {
        return gui;
    }

    public Economy economy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        return economy;
    }

    // ───────────── права и лимиты ─────────────

    /** key — ключ из секции permissions конфига (use, sell, buy, cancel, expired, slots-book, reload, admin). */
    public boolean can(CommandSender s, String key) {
        String fallback = "simpleauction." + key.replace("-book", "");
        return s.hasPermission(getConfig().getString("permissions." + key, fallback));
    }

    public int slotLimit(Player p) {
        int best = getConfig().getInt("slots.default", getConfig().getInt("max-listings", 2));
        var groups = getConfig().getConfigurationSection("slots.groups");
        if (groups != null) {
            for (String g : groups.getKeys(false)) {
                if (p.hasPermission("simpleauction.slots." + g)) best = Math.max(best, groups.getInt(g));
            }
        }
        return best;
    }

    // ───────────── сообщения и форматирование ─────────────

    public String msg(String key, Object... kv) {
        String raw = getConfig().getString("messages." + key);
        if (raw == null) raw = "&c[нет сообщения messages." + key + "]";
        return color(replace(raw, kv));
    }

    public static String replace(String s, Object... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return s;
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public String money(double v) {
        Economy eco = economy();
        return eco != null ? eco.format(v) : number(v);
    }

    public static String number(double v) {
        return v == Math.floor(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v);
    }

    public String itemName(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        String name;
        if (m != null && m.hasDisplayName()) {
            name = m.getDisplayName();
        } else {
            StringBuilder sb = new StringBuilder();
            for (String part : it.getType().name().toLowerCase(Locale.ROOT).split("_")) {
                if (part.isEmpty()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            name = sb.toString();
        }
        return it.getAmount() > 1 ? name + ChatColor.GRAY + " x" + it.getAmount() : name;
    }

    public static String formatTime(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60;
        if (h > 0) return h + "ч " + m + "мин";
        if (m > 0) return m + "мин";
        return "<1мин";
    }

    public static UUID uuidOf(Player p) {
        return p.getUniqueId();
    }
}

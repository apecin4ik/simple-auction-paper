package ru.simpleauction;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AuctionCommand implements CommandExecutor, TabCompleter {
    private final SimpleAuction plugin;

    public AuctionCommand(SimpleAuction plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        if (sub.equals("reload")) {
            if (!plugin.can(sender, "reload")) {
                sender.sendMessage(plugin.msg("no-permission"));
                return true;
            }
            plugin.reload();
            sender.sendMessage(plugin.msg("reloaded"));
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("only-player"));
            return true;
        }

        switch (sub) {
            case "" -> {
                if (!plugin.can(p, "use")) {
                    p.sendMessage(plugin.msg("no-permission"));
                    return true;
                }
                plugin.gui().open(p, AuctionGui.View.MAIN, 1);
            }
            case "sell" -> sell(p, args);
            case "expired" -> {
                if (!plugin.can(p, "expired")) {
                    p.sendMessage(plugin.msg("no-permission"));
                    return true;
                }
                plugin.gui().open(p, AuctionGui.View.EXPIRED, 1);
            }
            case "slots" -> {
                if (!plugin.can(p, "slots-book")) {
                    p.sendMessage(plugin.msg("no-permission"));
                    return true;
                }
                p.sendMessage(plugin.msg("slots-info",
                        "max", plugin.slotLimit(p),
                        "used", plugin.lots().countActive(p.getUniqueId(), System.currentTimeMillis())));
                plugin.gui().openBook(p);
            }
            default -> p.sendMessage(plugin.msg("usage"));
        }
        return true;
    }

    private void sell(Player p, String[] args) {
        if (!plugin.can(p, "sell")) {
            p.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            p.sendMessage(plugin.msg("usage-sell"));
            return;
        }
        double price;
        try {
            price = Double.parseDouble(args[1].replace(',', '.'));
        } catch (NumberFormatException ex) {
            p.sendMessage(plugin.msg("bad-price"));
            return;
        }
        if (Double.isNaN(price) || Double.isInfinite(price) || price <= 0) {
            p.sendMessage(plugin.msg("bad-price"));
            return;
        }
        price = Math.round(price * 100.0) / 100.0;
        double min = plugin.getConfig().getDouble("min-price", 1);
        double max = plugin.getConfig().getDouble("max-price", 1_000_000_000);
        if (price < min || price > max) {
            p.sendMessage(plugin.msg("price-limits", "min", SimpleAuction.number(min), "max", SimpleAuction.number(max)));
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            p.sendMessage(plugin.msg("no-item"));
            return;
        }
        long now = System.currentTimeMillis();
        if (!plugin.can(p, "admin")) {
            int limit = plugin.slotLimit(p);
            if (plugin.lots().countActive(p.getUniqueId(), now) >= limit) {
                p.sendMessage(plugin.msg("limit-reached", "max", limit));
                return;
            }
        }
        ItemStack stack = hand.clone();
        Lot lot = new Lot(UUID.randomUUID(), p.getUniqueId(), p.getName(), stack, price, now,
                now + plugin.lots().activeMillis());
        plugin.lots().add(lot);
        p.getInventory().setItemInMainHand(null);
        plugin.lots().save();
        p.sendMessage(plugin.msg("listed", "item", plugin.itemName(stack), "price", plugin.money(price)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String a = args[0].toLowerCase(Locale.ROOT);
            for (String s : new String[]{"sell", "expired", "slots", "reload"}) {
                if (s.startsWith(a) && (!s.equals("reload") || plugin.can(sender, "reload"))) out.add(s);
            }
        }
        return out;
    }
}

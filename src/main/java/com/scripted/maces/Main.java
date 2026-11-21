package com.scripted.maces;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.*;

public class Main extends JavaPlugin implements Listener, TabCompleter {

    private NamespacedKey maceTypeKey;

    private final Map<UUID, Long> hellMarkedPlayers = new HashMap<>();
    private final Set<UUID> trappedInHell = new HashSet<>();
    private final Set<UUID> lightningProtected = new HashSet<>();

    private static final double HELL_X = 203007.5;
    private static final double HELL_Y = 260.0;
    private static final double HELL_Z = 4200.5;

    @Override
    public void onEnable() {
        maceTypeKey = new NamespacedKey(this, "scripted_mace_type");
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("scriptmace") != null) {
            getCommand("scriptmace").setTabCompleter(this);
        }
        startParticleTask();
    }

    public ItemStack createMace(String type) {
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        if (meta == null) return mace;

        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(maceTypeKey, PersistentDataType.STRING, type.toLowerCase());

        if (type.equalsIgnoreCase("heaven")) {
            meta.displayName(Component.text("Heaven Mace", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("A holy mace obtained by proving", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("your worth in Heaven.", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Power: ", NamedTextColor.GRAY)
                            .append(Component.text("2x Damage + 3x Lightning Strike", NamedTextColor.AQUA))
                            .decoration(TextDecoration.ITALIC, false)
            ));
        } else {
            meta.displayName(Component.text("Hell Mace", NamedTextColor.DARK_RED, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("The deadly mace of Hell, forged", NamedTextColor.RED)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("from the Devil's remains.", NamedTextColor.RED)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Power: ", NamedTextColor.GRAY)
                            .append(Component.text("3x Damage + Totem Pop Hell Banishment", NamedTextColor.DARK_PURPLE))
                            .decoration(TextDecoration.ITALIC, false)
            ));
        }

        mace.setItemMeta(meta);
        return mace;
    }

    private String getMaceType(ItemStack item) {
        if (item == null || item.getType() != Material.MACE || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(maceTypeKey, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        String type = getMaceType(weapon);
        if (type == null) return;

        if (type.equals("heaven")) {
            event.setDamage(event.getDamage() * 2.0);

            UUID attackerId = attacker.getUniqueId();
            lightningProtected.add(attackerId);
            Bukkit.getScheduler().runTaskLater(this, () -> lightningProtected.remove(attackerId), 25L);

            Location strikeLoc = victim.getLocation();
            for (int i = 0; i < 3; i++) {
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (victim.getWorld() != null) {
                        victim.getWorld().strikeLightning(victim.isValid() ? victim.getLocation() : strikeLoc);
                    }
                }, i * 3L);
            }
        } else if (type.equals("hell")) {
            event.setDamage(event.getDamage() * 3.0);

            if (victim instanceof Player targetPlayer) {
                hellMarkedPlayers.put(targetPlayer.getUniqueId(), System.currentTimeMillis() + 5000L);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLightningDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (lightningProtected.contains(player.getUniqueId())) {
            if (event.getCause() == EntityDamageEvent.DamageCause.LIGHTNING
                    || event.getCause() == EntityDamageEvent.DamageCause.FIRE
                    || event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK) {
                event.setCancelled(true);
                player.setFireTicks(0);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotemPop(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Long expiry = hellMarkedPlayers.get(victim.getUniqueId());
        if (expiry == null || System.currentTimeMillis() > expiry) return;

        hellMarkedPlayers.remove(victim.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> sendToHell(victim), 1L);
    }

    private void sendToHell(Player victim) {
        if (!victim.isOnline()) return;

        World world = victim.getWorld();
        Location hellLoc = new Location(world, HELL_X, HELL_Y, HELL_Z);

        for (int i = 0; i < victim.getInventory().getSize(); i++) {
            ItemStack item = victim.getInventory().getItem(i);
            if (item != null && (item.getType() == Material.TOTEM_OF_UNDYING
                    || item.getType() == Material.GOLDEN_APPLE
                    || item.getType() == Material.ENCHANTED_GOLDEN_APPLE)) {
                victim.getInventory().setItem(i, null);
            }
        }
        victim.getInventory().setItemInOffHand(null);

        victim.teleport(hellLoc);
        trappedInHell.add(victim.getUniqueId());

        victim.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 200, 1, false, false));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 2, false, false));
        world.playSound(hellLoc, Sound.ENTITY_WARDEN_ROAR, 2.0f, 0.6f);
        world.playSound(hellLoc, Sound.ENTITY_WITHER_SPAWN, 1.5f, 0.5f);

        victim.showTitle(Title.title(
                Component.text("WELCOME TO HELL", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                Component.text("There is no escape.", NamedTextColor.RED),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))
        ));

        Warden monster = (Warden) world.spawnEntity(hellLoc, EntityType.WARDEN);
        monster.customName(Component.text("The Mysterious Monster", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        monster.setCustomNameVisible(true);
        monster.setInvisible(true);
        monster.setSilent(true);
        monster.setTarget(victim);
        monster.increaseAnger(victim, 150);

        if (monster.getAttribute(Attribute.ATTACK_DAMAGE) != null) {
            monster.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(60.0);
        }
        if (monster.getAttribute(Attribute.MOVEMENT_SPEED) != null) {
            monster.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.45);
        }

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!victim.isOnline() || victim.isDead() || !trappedInHell.contains(victim.getUniqueId()) || ticks > 300) {
                    if (monster.isValid()) monster.remove();
                    trappedInHell.remove(victim.getUniqueId());
                    cancel();
                    return;
                }
                monster.setTarget(victim);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, monster.getLocation().add(0, 1.2, 0), 15, 0.4, 0.8, 0.4, 0.02);
                world.spawnParticle(Particle.LARGE_SMOKE, monster.getLocation().add(0, 1.0, 0), 10, 0.4, 0.8, 0.4, 0.01);
                ticks += 5;
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    @EventHandler
    public void onVictimDeath(PlayerDeathEvent event) {
        trappedInHell.remove(event.getEntity().getUniqueId());
        hellMarkedPlayers.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        trappedInHell.remove(id);
        hellMarkedPlayers.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandWhileTrapped(PlayerCommandPreprocessEvent event) {
        if (trappedInHell.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("You cannot use commands in Hell.", NamedTextColor.DARK_RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEscapeItemUse(PlayerInteractEvent event) {
        if (!trappedInHell.contains(event.getPlayer().getUniqueId())) return;
        ItemStack item = event.getItem();
        if (item != null && (item.getType() == Material.ENDER_PEARL || item.getType() == Material.CHORUS_FRUIT)) {
            event.setCancelled(true);
        }
    }

    private void startParticleTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String type = getMaceType(p.getInventory().getItemInMainHand());
                    if ("heaven".equals(type)) {
                        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1.0, 0), 3, 0.3, 0.4, 0.3, 0.01);
                    } else if ("hell".equals(type)) {
                        p.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, p.getLocation().add(0, 1.0, 0), 4, 0.3, 0.4, 0.3, 0.01);
                    }
                }
            }
        }.runTaskTimer(this, 10L, 10L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("scriptedmaces.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(Component.text("Usage: /scriptmace give <heaven|hell> [player]", NamedTextColor.YELLOW));
            return true;
        }

        String type = args[1].toLowerCase();
        if (!type.equals("heaven") && !type.equals("hell")) {
            sender.sendMessage(Component.text("Mace type must be 'heaven' or 'hell'.", NamedTextColor.RED));
            return true;
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Specify a player from console.", NamedTextColor.RED));
            return true;
        }

        target.getInventory().addItem(createMace(type));
        sender.sendMessage(Component.text("Gave " + type + " mace to " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("give");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return List.of("heaven", "hell");
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return null;
        return Collections.emptyList();
    }
}

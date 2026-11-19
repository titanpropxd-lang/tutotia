package es.xsinex.tutorial;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.NamespacedKey;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

/**
 * El mundo del tutorial: se crea solo (vacío), se protege, se construye una isla con un comando y tiene el viaje en barco del final.
 * Todo lo que se coloca (spawn, ruta del barco) se guarda en mundo.yml.
 */
final class Mundo implements Listener {
    private final TutorialPlugin pl;
    private final File file;
    private YamlConfiguration data;
    private final NamespacedKey kBarco;
    private boolean construyendo = false;

    int isla() { return Math.max(40, Math.min(160, pl.getConfig().getInt("isla.radio", 75))); }      // radio de la isla
    int oceano() { return isla() + 125; }                                                              // radio del mar
    static final int NIVEL = 64;         // altura de la hierba de la isla (el agua llega a 63)

    final Recursos rec;

    Mundo(TutorialPlugin pl) {
        this.pl = pl;
        this.rec = new Recursos(pl, this);
        this.file = new File(pl.getDataFolder(), "mundo.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        this.kBarco = new NamespacedKey(pl, "barco_tutorial");
    }

    void guardar() { try { data.save(file); } catch (Exception ex) { pl.getLogger().warning("mundo.yml: " + ex); } }
    void recargar() { data = YamlConfiguration.loadConfiguration(file); }

    // ------------------------------------------------------------------ el mundo
    String nombre() { return pl.getConfig().getString("mundo.nombre", "aetheria_tutorial"); }

    /** true si en la carpeta del servidor ya esta el mapa del pueblo (carpeta del mundo con region/): se usa tal cual, sin construir nada. */
    boolean mapaPropio() { return new File(Bukkit.getWorldContainer(), nombre() + File.separator + "region").isDirectory(); }

    /** Carga (o crea vacío) el mundo del tutorial. Devuelve null si está desactivado. */
    World mundo() {
        if (!pl.getConfig().getBoolean("mundo.activo", true)) return null;
        World w = Bukkit.getWorld(nombre());
        if (w == null && mapaPropio()) {
            try { w = new WorldCreator(nombre()).createWorld(); pl.getLogger().info("Mapa del pueblo '" + nombre() + "' cargado."); }
            catch (Throwable t) { pl.getLogger().warning("No pude cargar el mapa " + nombre() + ": " + t); }
        }
        if (w == null && pl.getConfig().getBoolean("mundo.crear", true)) {
            try {
                WorldCreator wc = new WorldCreator(nombre());
                wc.type(WorldType.FLAT);
                wc.generateStructures(false);
                wc.generatorSettings("{\"layers\":[],\"biome\":\"minecraft:plains\"}");
                w = wc.createWorld();
                pl.getLogger().info("Mundo del tutorial '" + nombre() + "' creado (vacío). La isla se construye sola al arrancar.");
            } catch (Throwable t) { pl.getLogger().warning("No pude crear el mundo " + nombre() + ": " + t); }
        }
        if (w != null) reglas(w);
        return w;
    }

    @SuppressWarnings("deprecation")
    private void reglas(World w) {
        try {
            w.setGameRuleValue("doDaylightCycle", "false");
            w.setGameRuleValue("doWeatherCycle", "false");
            w.setGameRuleValue("doMobSpawning", "false");
            w.setGameRuleValue("keepInventory", "true");
            w.setGameRuleValue("announceAdvancements", "false");
            w.setGameRuleValue("doFireTick", "false");
            w.setGameRuleValue("mobGriefing", "false");
            w.setTime(pl.getConfig().getLong("mundo.hora", 6000L));
            w.setStorm(false);
            w.setPVP(false);
            if (mapaPropio()) {
                w.getWorldBorder().setCenter(pl.getConfig().getDouble("mapa.borde.x", 28), pl.getConfig().getDouble("mapa.borde.z", -88));
                w.getWorldBorder().setSize(pl.getConfig().getDouble("mapa.borde.tam", 1100));
            } else {
                w.getWorldBorder().setCenter(0, 0);
                w.getWorldBorder().setSize(oceano() * 2 + 40);
            }
        } catch (Throwable t) { pl.getLogger().warning("reglas del mundo: " + t); }
    }

    boolean ocupado() { return construyendo; }

    /** Si la isla no existe (mundo nuevo o carpeta borrada), la construye sola. */
    void autoConstruir() {
        try {
            if (mapaPropio()) { aplicarMapa(); rec.construir(); return; }
            if (!pl.getConfig().getBoolean("isla.auto-construir", true) || construyendo) return;
            World w = mundo();
            if (w == null) return;
            boolean hecho = data.getBoolean("construido", false);
            if (hecho) {
                w.getChunkAt(0, 0).load(true);
                if (!w.getBlockAt(0, NIVEL - 1, 0).getType().isAir()) return;   // sigue ahi
                pl.getLogger().info("La isla del tutorial no esta en el mundo (¿borraste la carpeta?). Se vuelve a construir.");
            }
            pl.getLogger().info("Construyendo la isla del tutorial automaticamente (radio " + isla() + ")...");
            construir(Bukkit.getConsoleSender(), null);
        } catch (Throwable t) { pl.getLogger().warning("autoConstruir: " + t); }
    }

    /** Mapa del pueblo: coloca spawn, NPCs (Citizens) y la ruta del barco UNA vez (o cuando sube mapa.version en config). */
    void aplicarMapa() {
        World w = mundo();
        if (w == null) return;
        int ver = pl.getConfig().getInt("mapa.version", 1);
        if (data.getInt("mapa-aplicado", 0) == ver) return;
        try {
            List<Double> s = pl.getConfig().getDoubleList("mapa.spawn");
            if (s.size() >= 4) { Location ls = new Location(w, s.get(0), s.get(1), s.get(2), s.get(3).floatValue(), 0f); w.getChunkAt(ls).load(true); fijarSpawn(ls); }
            for (String id : new String[] { "alba", "brasa", "valdor" }) {
                List<Double> n = pl.getConfig().getDoubleList("mapa.npcs." + id);
                if (n.size() >= 4) { Location ln = new Location(w, n.get(0), n.get(1), n.get(2), n.get(3).floatValue(), 0f); w.getChunkAt(ln).load(true); pl.colocarNpc(id, ln); }
            }
            List<Location> r = new ArrayList<>();
            for (String p : pl.getConfig().getStringList("mapa.ruta")) {
                String[] q = p.split(",");
                if (q.length >= 3) r.add(new Location(w, Double.parseDouble(q[0].trim()), Double.parseDouble(q[1].trim()), Double.parseDouble(q[2].trim())));
            }
            if (r.size() >= 2 && !data.getBoolean("barco.manual", false)) guardarRuta(r);   // si la editaste a mano, no se pisa
            data.set("construido", true);
            data.set("mapa-aplicado", ver);
            guardar();
            pl.getLogger().info("Mapa del pueblo listo: spawn, 3 NPCs y ruta del barco (" + r.size() + " puntos).");
        } catch (Throwable t) { pl.getLogger().warning("aplicarMapa: " + t); }
    }

    boolean esDelTutorial(World w) { return w != null && w.getName().equals(nombre()); }

    Location spawn() {
        World w = mundo();
        if (w == null) return null;
        if (data.contains("spawn.x"))
            return new Location(w, data.getDouble("spawn.x"), data.getDouble("spawn.y"), data.getDouble("spawn.z"), (float) data.getDouble("spawn.yaw"), (float) data.getDouble("spawn.pitch"));
        return new Location(w, 0.5, NIVEL + 1, 15.5, 180f, 0f);
    }

    void fijarSpawn(Location l) {
        data.set("spawn.x", l.getX()); data.set("spawn.y", l.getY()); data.set("spawn.z", l.getZ());
        data.set("spawn.yaw", (double) l.getYaw()); data.set("spawn.pitch", (double) l.getPitch());
        guardar();
    }

    /** Lleva al jugador al spawn del tutorial (carga el chunk antes). */
    void llevar(Player p) {
        Location s = spawn();
        if (s == null) return;
        s.getChunk().load();
        p.teleport(s);
    }

    // ------------------------------------------------------------------ protección
    private boolean aqui(Entity e) { return e != null && esDelTutorial(e.getWorld()); }
    private boolean admin(Player p) { return p.hasPermission("tutorial.admin") && p.getGameMode() == org.bukkit.GameMode.CREATIVE; }

    @EventHandler(ignoreCancelled = true)
    public void dano(EntityDamageEvent ev) { if (ev.getEntity() instanceof Player && aqui(ev.getEntity())) ev.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void hambre(FoodLevelChangeEvent ev) { if (aqui(ev.getEntity())) { ev.setCancelled(true); ((Player) ev.getEntity()).setFoodLevel(20); } }

    @EventHandler(ignoreCancelled = true)
    public void romper(BlockBreakEvent ev) {
        if (!aqui(ev.getPlayer()) || admin(ev.getPlayer())) return;
        if (!rec.romper(ev)) ev.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void colocar(BlockPlaceEvent ev) {
        if (!aqui(ev.getPlayer()) || admin(ev.getPlayer())) return;
        if (!rec.colocar(ev)) ev.setCancelled(true);
    }

    /** Estaciones del taller: clic derecho abre la estación de Forja (las estaciones reales de Forja se registran en su base de datos, estas son del tutorial). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void estacion(PlayerInteractEvent ev) {
        if (ev.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || ev.getClickedBlock() == null || ev.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (!aqui(ev.getPlayer())) return;
        String st = rec.estacionEn(ev.getClickedBlock().getLocation());
        if (st == null) return;
        ev.setCancelled(true);
        Player p = ev.getPlayer();
        pl.estacionUsada(p, st);
        String cmd = pl.getConfig().getString("recursos.comando-estacion", "forja abrir {jugador} {estacion}").replace("{jugador}", p.getName()).replace("{estacion}", st);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    @EventHandler(ignoreCancelled = true)
    public void soltar(PlayerDropItemEvent ev) { if (aqui(ev.getPlayer()) && !admin(ev.getPlayer())) ev.setCancelled(true); }


    // ---- jugadores entre si: ni golpes, ni robos, ni empujones raros (config mundo.proteger-jugadores)
    private boolean protege() { return pl.getConfig().getBoolean("mundo.proteger-jugadores", true); }
    private boolean ajeno(Player p) { return protege() && aqui(p) && !admin(p); }

    /** Nadie pega a nadie (ni con arco, bola de nieve, tridente...) ni golpea cosas del mundo. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void golpes(EntityDamageByEntityEvent ev) {
        if (!protege() || !aqui(ev.getEntity())) return;
        Entity d = ev.getDamager();
        if (d instanceof Projectile && ((Projectile) d).getShooter() instanceof Entity) d = (Entity) ((Projectile) d).getShooter();
        if (d instanceof Player && admin((Player) d)) return;
        if (d instanceof Player || ev.getEntity() instanceof Player) ev.setCancelled(true);
    }

    /** Nada de recoger objetos del suelo (nadie puede robar lo que otro suelte o lo que haya). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void recoger(EntityPickupItemEvent ev) {
        if (ev.getEntity() instanceof Player && ajeno((Player) ev.getEntity())) ev.setCancelled(true);
    }

    /** Ningun cofre, barril, horno, tolva, etc.: solo el inventario propio y las mesas de uso (crafteo). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void abrirContenedor(InventoryOpenEvent ev) {
        if (!(ev.getPlayer() instanceof Player) || !ajeno((Player) ev.getPlayer())) return;
        InventoryType t = ev.getInventory().getType();
        if (t == InventoryType.CRAFTING || t == InventoryType.WORKBENCH || t == InventoryType.CREATIVE || t == InventoryType.PLAYER) return;
        if (ev.getInventory().getHolder() instanceof Player) return;
        // los menus propios de los plugins (guia, dialogos) no tienen bloque detras: se dejan pasar
        if (ev.getInventory().getHolder() == null || ev.getInventory().getHolder().getClass().getName().startsWith("es.xsinex")) return;
        ev.setCancelled(true);
    }

    /** No tocar a otros jugadores ni a marcos, soportes, cuadros ni vehiculos ajenos. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void interactuarEntidad(PlayerInteractEntityEvent ev) {
        if (!ajeno(ev.getPlayer())) return;
        Entity e = ev.getRightClicked();
        if (e instanceof Player || e instanceof org.bukkit.entity.Hanging || e instanceof org.bukkit.entity.ArmorStand
            || (e instanceof org.bukkit.entity.Vehicle && !e.getPersistentDataContainer().has(kBarco, PersistentDataType.BYTE)))
            ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void soportes(PlayerArmorStandManipulateEvent ev) { if (ajeno(ev.getPlayer())) ev.setCancelled(true); }

    /** Sin cubos, sin pisar cultivos, sin abrir cofres con clic derecho, sin huevos/pociones/etc. lanzados. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void interactuarBloque(PlayerInteractEvent ev) {
        if (!ajeno(ev.getPlayer())) return;
        if (ev.getAction() == org.bukkit.event.block.Action.PHYSICAL) { ev.setCancelled(true); return; }
        if (ev.getClickedBlock() != null && ev.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            Material m = ev.getClickedBlock().getType();
            String n = m.name();
            if (n.endsWith("CHEST") || n.equals("BARREL") || n.endsWith("SHULKER_BOX") || n.equals("HOPPER") || n.equals("DROPPER") || n.equals("DISPENSER")
                || n.equals("FURNACE") || n.equals("BLAST_FURNACE") || n.equals("SMOKER") || n.equals("BREWING_STAND") || n.equals("LECTERN") || n.equals("COMPOSTER")
                || n.equals("CAULDRON") || n.endsWith("_BED") || n.equals("RESPAWN_ANCHOR") || n.equals("CHISELED_BOOKSHELF") || n.equals("DECORATED_POT") || n.equals("JUKEBOX")) {
                ev.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void cubo1(PlayerBucketEmptyEvent ev) { if (ajeno(ev.getPlayer())) ev.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void cubo2(PlayerBucketFillEvent ev) { if (ajeno(ev.getPlayer())) ev.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void lanzar(ProjectileLaunchEvent ev) {
        if (ev.getEntity().getShooter() instanceof Player && ajeno((Player) ev.getEntity().getShooter())) ev.setCancelled(true);
    }

    /** No salir de la isla por perlas ni frutas del coro (el barco usa teleport PLUGIN y sigue funcionando). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void teletransporte(PlayerTeleportEvent ev) {
        if (!ajeno(ev.getPlayer())) return;
        PlayerTeleportEvent.TeleportCause c = ev.getCause();
        if (c == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || c == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void consumir(PlayerItemConsumeEvent ev) { /* comer/beber es libre en la isla */ }

    @EventHandler(ignoreCancelled = true)
    public void spawnMob(CreatureSpawnEvent ev) {
        if (!aqui(ev.getEntity())) return;
        CreatureSpawnEvent.SpawnReason r = ev.getSpawnReason();
        if (r == CreatureSpawnEvent.SpawnReason.CUSTOM || r == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG || r == CreatureSpawnEvent.SpawnReason.COMMAND) return;
        ev.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void explosion(EntityExplodeEvent ev) { if (aqui(ev.getEntity())) ev.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void clima(WeatherChangeEvent ev) { if (esDelTutorial(ev.getWorld()) && ev.toWeatherState()) ev.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void bajarDelBarco(VehicleExitEvent ev) {
        if (ev.getVehicle().getPersistentDataContainer().has(kBarco, PersistentDataType.BYTE)) ev.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void golpearBarco(VehicleDamageEvent ev) {
        if (ev.getVehicle().getPersistentDataContainer().has(kBarco, PersistentDataType.BYTE)) ev.setCancelled(true);
    }

    // ------------------------------------------------------------------ ruta del barco
    List<Location> ruta() {
        World w = mundo();
        List<Location> out = new ArrayList<>();
        if (w == null) return out;
        for (Object o : data.getList("barco.puntos", new ArrayList<>())) {
            if (!(o instanceof java.util.Map)) continue;
            java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
            try { out.add(new Location(w, ((Number) m.get("x")).doubleValue(), ((Number) m.get("y")).doubleValue(), ((Number) m.get("z")).doubleValue())); } catch (Throwable ignored) { }
        }
        return out;
    }

    void guardarRuta(List<Location> pts) {
        List<java.util.Map<String, Object>> l = new ArrayList<>();
        for (Location p : pts) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("x", p.getX()); m.put("y", p.getY()); m.put("z", p.getZ());
            l.add(m);
        }
        data.set("barco.puntos", l);
        guardar();
    }

    /** Si hay agua bajo el punto, lo pega a la superficie (+0.15) para que el bote no flote ni se hunda. */
    private Location ajustarAgua(Location l) {
        World w = l.getWorld();
        int bx = l.getBlockX(), bz = l.getBlockZ();
        for (int y = l.getBlockY() + 1; y >= l.getBlockY() - 8; y--) {
            Material m = w.getBlockAt(bx, y, bz).getType();
            if (m == Material.WATER) {
                int top = y;
                while (w.getBlockAt(bx, top + 1, bz).getType() == Material.WATER) top++;
                return new Location(w, l.getX(), top + 0.15, l.getZ());
            }
        }
        return new Location(w, l.getX(), l.getY(), l.getZ());
    }

    void anadirPunto(Location l) { List<Location> r = ruta(); r.add(ajustarAgua(l)); data.set("barco.manual", true); guardarRuta(r); }
    /** idx 1-based; devuelve false si no existe. */
    boolean quitarPunto(int idx) { List<Location> r = ruta(); if (idx < 1 || idx > r.size()) return false; r.remove(idx - 1); data.set("barco.manual", true); guardarRuta(r); return true; }
    boolean insertarPunto(int idx, Location l) { List<Location> r = ruta(); if (idx < 1 || idx > r.size() + 1) return false; r.add(idx - 1, ajustarAgua(l)); data.set("barco.manual", true); guardarRuta(r); return true; }
    boolean moverPunto(int idx, Location l) { List<Location> r = ruta(); if (idx < 1 || idx > r.size()) return false; r.set(idx - 1, ajustarAgua(l)); data.set("barco.manual", true); guardarRuta(r); return true; }
    /** muestra la ruta con particulas unos segundos solo al admin que la pide */
    void verRuta(final Player p, int seg) {
        final List<Location> r = ruta();
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!p.isOnline() || t >= seg * 2) { cancel(); return; }
                t++;
                for (int i = 0; i < r.size(); i++) {
                    Location a = r.get(i);
                    for (int k = 0; k < 6; k++) p.spawnParticle(org.bukkit.Particle.END_ROD, a.getX(), a.getY() + 0.5 + k * 0.5, a.getZ(), 1, 0, 0, 0, 0);
                    if (i + 1 < r.size()) {
                        Location b = r.get(i + 1);
                        int n = (int) Math.max(2, a.distance(b) / 1.5);
                        for (int k = 1; k < n; k++) p.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, a.getX() + (b.getX() - a.getX()) * k / n, a.getY() + 0.6, a.getZ() + (b.getZ() - a.getZ()) * k / n, 1, 0, 0, 0, 0);
                    }
                }
            }
        }.runTaskTimer(pl, 0L, 10L);
    }
    void limpiarRuta() { data.set("barco.manual", true); guardarRuta(new ArrayList<>()); }

    /** Curva suave (Catmull-Rom) por los puntos, muestreada cada ~0,5 bloques. */
    private List<Location> curva(List<Location> p) {
        List<Location> out = new ArrayList<>();
        if (p.size() < 2) return p;
        for (int i = 0; i < p.size() - 1; i++) {
            Location p0 = p.get(Math.max(0, i - 1)), p1 = p.get(i), p2 = p.get(i + 1), p3 = p.get(Math.min(p.size() - 1, i + 2));
            int n = Math.max(4, (int) (p1.distance(p2) * 2));
            for (int k = 0; k < n; k++) {
                double t = k / (double) n, t2 = t * t, t3 = t2 * t;
                double x = 0.5 * ((2 * p1.getX()) + (-p0.getX() + p2.getX()) * t + (2 * p0.getX() - 5 * p1.getX() + 4 * p2.getX() - p3.getX()) * t2 + (-p0.getX() + 3 * p1.getX() - 3 * p2.getX() + p3.getX()) * t3);
                double y = p1.getY() + (p2.getY() - p1.getY()) * t;
                double z = 0.5 * ((2 * p1.getZ()) + (-p0.getZ() + p2.getZ()) * t + (2 * p0.getZ() - 5 * p1.getZ() + 4 * p2.getZ() - p3.getZ()) * t2 + (-p0.getZ() + 3 * p1.getZ() - 3 * p2.getZ() + p3.getZ()) * t3);
                out.add(new Location(p1.getWorld(), x, y, z));
            }
        }
        out.add(p.get(p.size() - 1));
        return out;
    }

    private static Component c(String s) { return LegacyComponentSerializer.legacySection().deserialize(s.replace('&', '§')); }

    @SuppressWarnings("unchecked")
    private boolean moverBarco(Entity boat, Location l) {
        try {
            Class<?> tf = Class.forName("io.papermc.paper.entity.TeleportFlag");
            Class<?> es = Class.forName("io.papermc.paper.entity.TeleportFlag$EntityState");
            Object flag = Enum.valueOf((Class<Enum>) es.asSubclass(Enum.class), "RETAIN_PASSENGERS");
            Object arr = java.lang.reflect.Array.newInstance(tf, 1);
            java.lang.reflect.Array.set(arr, 0, flag);
            java.lang.reflect.Method m = Entity.class.getMethod("teleport", Location.class, arr.getClass());
            m.invoke(boat, l, arr);
            return true;
        } catch (Throwable t) { return false; }
    }

    /**
     * Viaje en barco: sube al jugador a un bote que recorre la ruta con titulos, sonido de olas y atardecer.
     * Al llegar al final se oscurece la pantalla y se ejecuta {@code fin}. Sin ruta: solo una transicion negra.
     */
    void barco(Player p, Runnable fin) {
        final UUID u = p.getUniqueId();
        List<Location> pts = ruta();
        if (pts.size() < 2) {
            pl.getLogger().warning("No hay ruta de barco (usa /tutorial construir o /tutorial barco punto). Se hace solo la transicion.");
            transicionSimple(p, fin);
            return;
        }
        List<Location> cur = curva(pts);
        Location ini = cur.get(0).clone();
        World w = ini.getWorld();
        ini.getChunk().load();
        p.teleport(ini.clone().add(0, 1, 0));
        EntityType tipo;
        try { tipo = EntityType.valueOf(pl.getConfig().getString("barco.tipo", "OAK_BOAT")); } catch (Throwable t) { tipo = EntityType.valueOf("BOAT"); }
        final Entity boat = w.spawnEntity(ini, tipo);
        boat.setInvulnerable(true);
        boat.getPersistentDataContainer().set(kBarco, PersistentDataType.BYTE, (byte) 1);
        boat.addPassenger(p);
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20, 0, false, false, false));
        p.setPlayerTime(pl.getConfig().getLong("barco.hora", 12600L), false);
        double vel = pl.getConfig().getDouble("barco.velocidad", 5.5);
        List<String> textos = pl.getConfig().getStringList("barco.textos");   // formato: "0.05|Titulo|Subtitulo"
        final boolean[] dicho = new boolean[textos.size()];
        final double[] dist = { 0 };
        // longitud total
        double total = 0;
        for (int i = 1; i < cur.size(); i++) total += cur.get(i - 1).distance(cur.get(i));
        final double totalF = total;
        final boolean[] apagado = { false };
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player pl2 = Bukkit.getPlayer(u);
                if (pl2 == null || !pl2.isOnline() || boat.isDead()) { boat.remove(); this.cancel(); return; }
                t++;
                dist[0] += vel / 20.0;
                double acc = 0; int k = 0;
                while (k < cur.size() - 1) {
                    double seg = cur.get(k).distance(cur.get(k + 1));
                    if (acc + seg >= dist[0]) break;
                    acc += seg; k++;
                }
                double frac = dist[0] / totalF;
                if (k >= cur.size() - 1 || frac >= 1.0) { finalizar(pl2, boat, fin); this.cancel(); return; }
                Location a = cur.get(k), b = cur.get(k + 1);
                double sl = Math.max(0.0001, a.distance(b));
                double f = (dist[0] - acc) / sl;
                Location pos = a.clone().add(b.clone().subtract(a).toVector().multiply(f));
                Vector dir = b.toVector().subtract(a.toVector());
                pos.setYaw((float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())));
                pos.setPitch(0f);
                if (!moverBarco(boat, pos)) {
                    // plan B: re-sentar al jugador
                    boat.teleport(pos);
                    if (!boat.getPassengers().contains(pl2)) boat.addPassenger(pl2);
                }
                if (!boat.getPassengers().contains(pl2)) boat.addPassenger(pl2);
                // sonido y particulas
                if (t % 50 == 0) pl2.playSound(pl2.getLocation(), Sound.BLOCK_WATER_AMBIENT, 0.7f, 1f);
                if (t % 30 == 0) pl2.playSound(pl2.getLocation(), Sound.ENTITY_BOAT_PADDLE_WATER, 0.5f, 0.8f);
                if (t % 3 == 0) pl2.getWorld().spawnParticle(Particle.SPLASH, pos.clone().add(0, 0.2, 0), 6, 0.6, 0.1, 0.6, 0.02);
                // textos
                for (int i = 0; i < textos.size(); i++) {
                    if (dicho[i]) continue;
                    String[] part = textos.get(i).split("\\|", 3);
                    double at;
                    try { at = Double.parseDouble(part[0].trim()); } catch (Throwable e) { dicho[i] = true; continue; }
                    if (frac >= at) {
                        dicho[i] = true;
                        pl2.showTitle(Title.title(c(part.length > 1 ? part[1] : ""), c(part.length > 2 ? part[2] : ""),
                                Title.Times.times(java.time.Duration.ofMillis(800), java.time.Duration.ofMillis(3200), java.time.Duration.ofMillis(1000))));
                    }
                }
                // se oscurece al final (3 s antes)
                if (!apagado[0] && (totalF - dist[0]) / vel <= 3.0) {
                    apagado[0] = true;
                    pl2.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0, false, false, false));
                }
            }
        }.runTaskTimer(pl, 1L, 1L);
    }

    private void finalizar(Player p, Entity boat, Runnable fin) {
        try { boat.removePassenger(p); } catch (Throwable ignored) { }
        boat.remove();
        p.resetPlayerTime();
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        if (fin != null) fin.run();
    }

    private void transicionSimple(Player p, Runnable fin) {
        p.showTitle(Title.title(c("§6⚓ Zarpando"), c("§7Rumbo a Aetheria"), Title.Times.times(java.time.Duration.ofMillis(600), java.time.Duration.ofMillis(2600), java.time.Duration.ofMillis(800))));
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0, false, false, false));
        p.playSound(p.getLocation(), Sound.BLOCK_WATER_AMBIENT, 1f, 1f);
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (fin != null) fin.run(); }, 70L);
    }

    // ------------------------------------------------------------------ construcción de la isla
    private World cw;
    private void b(int x, int y, int z, String bd) {
        try { cw.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(bd), false); } catch (Throwable ignored) { }
    }
    private void fill(int x0, int y0, int z0, int x1, int y1, int z1, String bd) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) b(x, y, z, bd);
    }
    private double radioIsla(double ang) { int r = isla(); return r + r * 0.09 * Math.sin(3 * ang) + r * 0.06 * Math.cos(5 * ang + 1); }

    /** Construye la isla poco a poco (varios ticks) para no bloquear el servidor. */
    void construir(CommandSender who, Location centro) {
        if (mapaPropio()) { who.sendMessage("§eEl tutorial usa el mapa del pueblo que ya está en el servidor: no hay nada que construir. (Para recolocar spawn/NPCs/barco sube mapa.version en config.yml y reinicia.)"); return; }
        if (construyendo) { who.sendMessage("§cYa se está construyendo."); return; }
        World w = mundo();
        if (w == null) { who.sendMessage("§cEl mundo del tutorial está desactivado o no se pudo crear."); return; }
        cw = w;
        construyendo = true;
        who.sendMessage("§eConstruyendo la isla del tutorial… (unos 30-60 s, no te alejes del mundo).");
        final int R = oceano();
        final int[] x = { -R };
        new BukkitRunnable() {
            @Override public void run() {
                try {
                    int hechas = 0;
                    while (x[0] <= R && hechas < 3500) {
                        for (int z = -R; z <= R; z++) {
                            double d = Math.sqrt((double) x[0] * x[0] + (double) z * z);
                            if (d > R) continue;
                            columna(x[0], z, d);
                            hechas++;
                        }
                        x[0]++;
                    }
                    if (x[0] > R) {
                        this.cancel();
                        estructuras();
                        construyendo = false;
                        pl.getLogger().info("Isla del tutorial construida.");
                        who.sendMessage("§a✔ Isla del tutorial construida. NPCs, spawn y ruta del barco colocados. Prueba: §e/tutorial probar");
                    }
                } catch (Throwable t) {
                    this.cancel(); construyendo = false;
                    who.sendMessage("§cError construyendo: " + t);
                    pl.getLogger().warning("construir: " + t);
                }
            }
        }.runTaskTimer(pl, 1L, 1L);
    }

    private void columna(int x, int z, double d) {
        double ang = Math.atan2(z, x);
        double r = radioIsla(ang);
        // fondo del mar
        for (int y = 52; y <= 59; y++) cw.getBlockAt(x, y, z).setType(y < 58 ? Material.STONE : Material.GRAVEL, false);
        cw.getBlockAt(x, 60, z).setType(Material.SAND, false);
        if (d <= r) {
            for (int y = 61; y <= 62; y++) cw.getBlockAt(x, y, z).setType(Material.DIRT, false);
            if (d > r - 3.5) {
                cw.getBlockAt(x, 63, z).setType(Material.SAND, false);
                cw.getBlockAt(x, 64, z).setType(Material.AIR, false);
            } else {
                cw.getBlockAt(x, 63, z).setType(Material.DIRT, false);
                cw.getBlockAt(x, 64, z).setType(Material.GRASS_BLOCK, false);
            }
        } else {
            for (int y = 61; y <= 63; y++) cw.getBlockAt(x, y, z).setType(Material.WATER, false);
        }
    }

    private void casa(int cx, int cz, int hx, int hz, String pared, String esquina, String suelo, String techo, String techoSlab, String puertaLado) {
        // hx, hz = medias dimensiones interiores; paredes en +-(h+1)
        int x0 = cx - hx - 1, x1 = cx + hx + 1, z0 = cz - hz - 1, z1 = cz + hz + 1, y0 = NIVEL;
        fill(x0, y0, z0, x1, y0, z1, suelo);
        fill(x0 + 1, y0 + 1, z0 + 1, x1 - 1, y0 + 6, z1 - 1, "minecraft:air");
        for (int y = y0 + 1; y <= y0 + 4; y++) {
            for (int x = x0; x <= x1; x++) { b(x, y, z0, pared); b(x, y, z1, pared); }
            for (int z = z0; z <= z1; z++) { b(x0, y, z, pared); b(x1, y, z, pared); }
        }
        for (int y = y0 + 1; y <= y0 + 4; y++) { b(x0, y, z0, esquina); b(x1, y, z0, esquina); b(x0, y, z1, esquina); b(x1, y, z1, esquina); }
        // ventanas
        for (int x = x0 + 2; x < x1 - 1; x += 3) { b(x, y0 + 3, z0, "minecraft:glass_pane"); b(x, y0 + 3, z1, "minecraft:glass_pane"); }
        for (int z = z0 + 2; z < z1 - 1; z += 3) { b(x0, y0 + 3, z, "minecraft:glass_pane"); b(x1, y0 + 3, z, "minecraft:glass_pane"); }
        // puerta (hueco de 2x3 hacia la plaza)
        int px = cx, pz = cz;
        if (puertaLado.equals("S")) pz = z1; else if (puertaLado.equals("N")) pz = z0; else if (puertaLado.equals("E")) px = x1; else px = x0;
        for (int dx = -1; dx <= 1; dx++) for (int y = y0 + 1; y <= y0 + 3; y++) {
            if (puertaLado.equals("S") || puertaLado.equals("N")) b(px + dx, y, pz, "minecraft:air"); else b(px, y, pz + dx, "minecraft:air");
        }
        // techo a dos aguas (cumbrera a lo largo de X)
        int capas = hz + 3;
        for (int k = 0; k < capas; k++) {
            int zs = z1 + 1 - k, zn = z0 - 1 + k;
            if (zs < zn) break;
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                if (zs == zn) { b(x, y0 + 5 + k, zs, techoSlab); continue; }
                b(x, y0 + 5 + k, zs, techo + "[facing=north,half=bottom]");
                b(x, y0 + 5 + k, zn, techo + "[facing=south,half=bottom]");
                for (int z = zn + 1; z < zs; z++) b(x, y0 + 5 + k, z, pared);
            }
        }
        // frontones (triangulos) rellenos con pared
        for (int k = 0; k < capas; k++) {
            int zs = z1 - k, zn = z0 + k;
            if (zs < zn) break;
            for (int z = zn; z <= zs; z++) { b(x0, y0 + 5 + k, z, pared); b(x1, y0 + 5 + k, z, pared); }
        }
    }

    private void luz(int x, int z) {
        fill(x, NIVEL + 1, z, x, NIVEL + 3, z, "minecraft:spruce_fence");
        b(x, NIVEL + 4, z, "minecraft:lantern[hanging=false]");
    }

    private void arbol(int x, int z, Random rnd) {
        int h = 4 + rnd.nextInt(3);
        for (int y = 1; y <= h; y++) b(x, NIVEL + y, z, "minecraft:oak_log");
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = h - 1; dy <= h + 1; dy++) {
            if (Math.abs(dx) + Math.abs(dz) + Math.max(0, dy - h) * 2 > 3) continue;
            if (dx == 0 && dz == 0 && dy < h + 1) continue;
            b(x + dx, NIVEL + dy, z + dz, "minecraft:oak_leaves[persistent=true]");
        }
    }

    private void estanque(int cx, int cz, int rad) {
        for (int x = -rad - 2; x <= rad + 2; x++) for (int z = -rad - 2; z <= rad + 2; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d <= rad) { b(cx + x, NIVEL, cz + z, "minecraft:water"); b(cx + x, NIVEL - 1, cz + z, "minecraft:water"); b(cx + x, NIVEL - 2, cz + z, "minecraft:clay"); b(cx + x, NIVEL + 1, cz + z, "minecraft:air"); }
            else if (d <= rad + 1.6) { b(cx + x, NIVEL, cz + z, "minecraft:sand"); b(cx + x, NIVEL + 1, cz + z, "minecraft:air"); }
        }
    }

    private void estructuras() {
        final int R = isla();
        final int D = (int) Math.round(R * 0.62);          // distancia de las casas al centro
        final int PL = 10;                                 // radio de la plaza
        Random rnd = new Random(1234);
        // plaza central (checker) + fuente
        for (int x = -PL; x <= PL; x++) for (int z = -PL; z <= PL; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d > PL + 0.3) continue;
            b(x, NIVEL, z, (d < 3.3) ? "minecraft:polished_andesite" : (d > PL - 1.2 ? "minecraft:stone_brick_slab[type=top]" : (((x + z) & 1) == 0 ? "minecraft:stone_bricks" : "minecraft:cracked_stone_bricks")));
        }
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) b(x, NIVEL, z, "minecraft:water");
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) if (Math.abs(x) == 2 || Math.abs(z) == 2) b(x, NIVEL + 1, z, "minecraft:stone_brick_wall");
        luz(2, 2); luz(-2, -2); luz(2, -2); luz(-2, 2);
        // caminos a las 3 casas y al muelle
        for (int i = PL; i <= R - 3; i++) for (int w = -1; w <= 1; w++) {
            if (i <= D - 5) { b(w, NIVEL, -i, "minecraft:dirt_path"); b(i, NIVEL, w, "minecraft:dirt_path"); b(-i, NIVEL, w, "minecraft:dirt_path"); }
            b(w, NIVEL, i, "minecraft:dirt_path");
        }
        for (int i = PL + 3; i <= R - 6; i += 7) { luz(2, i); luz(-2, i); }
        for (int i = PL + 3; i <= D - 6; i += 7) { luz(2, -i); luz(-2, -i); luz(i, 2); luz(i, -2); luz(-i, 2); luz(-i, -2); }
        // casas
        casa(0, -D, 4, 3, "minecraft:spruce_planks", "minecraft:stripped_oak_log", "minecraft:oak_planks", "minecraft:spruce_stairs", "minecraft:spruce_slab[type=bottom]", "S");
        casa(D, 0, 3, 4, "minecraft:stone_bricks", "minecraft:deepslate_bricks", "minecraft:polished_deepslate", "minecraft:deepslate_tile_stairs", "minecraft:deepslate_tile_slab[type=bottom]", "W");
        casa(-D, 0, 3, 4, "minecraft:dark_oak_planks", "minecraft:stripped_dark_oak_log", "minecraft:dark_oak_planks", "minecraft:dark_oak_stairs", "minecraft:dark_oak_slab[type=bottom]", "E");
        final int Y = NIVEL + 1;
        // -- interior Alba (norte): biblioteca
        for (int x = -4; x <= 4; x++) { b(x, Y, -D - 3, "minecraft:bookshelf"); b(x, Y + 1, -D - 3, "minecraft:bookshelf"); }
        b(-3, Y, -D + 2, "minecraft:red_bed[facing=north,part=foot]"); b(-3, Y, -D + 1, "minecraft:red_bed[facing=north,part=head]");
        b(3, Y, -D + 1, "minecraft:lectern[facing=south]"); b(3, Y, -D - 1, "minecraft:barrel[facing=up]");
        fill(-1, Y, -D, 1, Y, -D + 1, "minecraft:red_carpet");
        b(-4, Y + 3, -D + 1, "minecraft:lantern[hanging=true]"); b(4, Y + 3, -D + 1, "minecraft:lantern[hanging=true]"); b(0, Y + 3, -D - 1, "minecraft:lantern[hanging=true]");
        // -- interior Brasa (este): fragua
        b(D + 3, Y, -3, "minecraft:blast_furnace[facing=west]"); b(D + 3, Y, -2, "minecraft:furnace[facing=west]"); b(D + 3, Y, -1, "minecraft:furnace[facing=west]");
        b(D + 3, Y, 1, "minecraft:anvil[facing=north]"); b(D + 3, Y, 2, "minecraft:smithing_table"); b(D + 3, Y, 3, "minecraft:grindstone[face=floor,facing=west]");
        b(D - 2, Y, -4, "minecraft:cauldron"); b(D - 2, Y, 4, "minecraft:barrel[facing=up]"); b(D - 1, Y, 4, "minecraft:barrel[facing=up]");
        b(D + 1, Y, -4, "minecraft:crafting_table");
        fill(D + 3, NIVEL + 5, -3, D + 3, NIVEL + 9, -3, "minecraft:stone_brick_wall"); b(D + 3, NIVEL + 10, -3, "minecraft:campfire[lit=true,signal_fire=false]");
        b(D, Y + 3, -3, "minecraft:lantern[hanging=true]"); b(D, Y + 3, 3, "minecraft:lantern[hanging=true]");
        // -- interior Valdor (oeste): archivo
        for (int z = -3; z <= 3; z++) { b(-D - 3, Y, z, "minecraft:bookshelf"); b(-D - 3, Y + 1, z, "minecraft:bookshelf"); b(-D - 3, Y + 2, z, "minecraft:bookshelf"); }
        b(-D - 2, Y, -4, "minecraft:cartography_table"); b(-D + 1, Y, 4, "minecraft:lectern[facing=north]"); b(-D - 1, Y, 4, "minecraft:enchanting_table");
        fill(-D, Y, -1, -D + 1, Y, 1, "minecraft:blue_carpet");
        b(-D, Y + 3, -3, "minecraft:lantern[hanging=true]"); b(-D, Y + 3, 3, "minecraft:lantern[hanging=true]");
        // muelle (sale por el sur, desde la orilla)
        final int z0 = R - 3, z1 = R + 21;
        for (int z = z0; z <= z1; z++) for (int x = -1; x <= 1; x++) b(x, NIVEL, z, "minecraft:spruce_planks");
        for (int z = z0 + 1; z <= z1 - 1; z += 4) for (int sx : new int[] { -2, 2 }) { fill(sx, 58, z, sx, NIVEL + 1, z, "minecraft:spruce_log"); b(sx, NIVEL + 2, z, "minecraft:lantern[hanging=false]"); }
        fill(-2, NIVEL + 1, z1, 2, NIVEL + 1, z1, "minecraft:spruce_fence");
        // estanques y arbolado
        estanque((int) (-R * 0.42), (int) (R * 0.38), 5);
        estanque((int) (R * 0.40), (int) (R * 0.40), 4);
        int objetivo = Math.max(16, R * R / 60), plant = 0;
        for (int i = 0; i < objetivo * 30 && plant < objetivo; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, rr = PL + 5 + rnd.nextDouble() * (R - PL - 10);
            int x = (int) (Math.cos(ang) * rr), z = (int) (Math.sin(ang) * rr);
            if (Math.sqrt(x * x + z * z) > radioIsla(Math.atan2(z, x)) - 5) continue;
            if (Math.abs(x) < 6 && z > 0 || Math.abs(x) < 6 && z < 0 && -z < D - 4 || Math.abs(z) < 6 && Math.abs(x) < D - 4) continue;   // caminos
            if (Math.abs(x - D) < 9 && Math.abs(z) < 9 || Math.abs(x + D) < 9 && Math.abs(z) < 9 || Math.abs(x) < 9 && Math.abs(z + D) < 9) continue;   // casas
            if (Math.sqrt((x + R * 0.42) * (x + R * 0.42) + (z - R * 0.38) * (z - R * 0.38)) < 8 || Math.sqrt((x - R * 0.40) * (x - R * 0.40) + (z - R * 0.40) * (z - R * 0.40)) < 7) continue;
            arbol(x, z, rnd); plant++;
        }
        String[] flores = { "minecraft:poppy", "minecraft:dandelion", "minecraft:azure_bluet", "minecraft:short_grass", "minecraft:cornflower", "minecraft:short_grass", "minecraft:oxeye_daisy", "minecraft:allium" };
        for (int i = 0; i < R * R / 14; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, rr = 6 + rnd.nextDouble() * (R - 8);
            int x = (int) (Math.cos(ang) * rr), z = (int) (Math.sin(ang) * rr);
            if (Math.sqrt(x * x + z * z) > radioIsla(Math.atan2(z, x)) - 4) continue;
            if (cw.getBlockAt(x, NIVEL, z).getType() != Material.GRASS_BLOCK) continue;
            if (!cw.getBlockAt(x, NIVEL + 1, z).getType().isAir()) continue;
            b(x, NIVEL + 1, z, flores[rnd.nextInt(flores.length)]);
        }
        // spawn, NPCs, barco
        fijarSpawn(new Location(cw, 0.5, NIVEL + 1, PL + 5.5, 180f, 0f));
        pl.colocarNpc("alba", new Location(cw, 0.5, NIVEL + 1, -D + 0.5, 0f, 0f));
        pl.colocarNpc("brasa", new Location(cw, D - 0.5, NIVEL + 1, 0.5, 90f, 0f));
        pl.colocarNpc("valdor", new Location(cw, -D + 0.5, NIVEL + 1, 0.5, 270f, 0f));
        double y = 63.15, rc = R + 50;
        List<Location> r = new ArrayList<>();
        r.add(new Location(cw, 0.5, y, z1 + 2)); r.add(new Location(cw, 0.5, y, z1 + 15));
        for (int deg = 80; deg >= -40; deg -= 20) r.add(new Location(cw, rc * Math.cos(Math.toRadians(deg)), y, rc * Math.sin(Math.toRadians(deg))));
        r.add(new Location(cw, (R + 85) * Math.cos(Math.toRadians(-55)), y, (R + 85) * Math.sin(Math.toRadians(-55))));
        r.add(new Location(cw, (R + 112) * Math.cos(Math.toRadians(-65)), y, (R + 112) * Math.sin(Math.toRadians(-65))));
        guardarRuta(r);
        data.set("construido", true);
        guardar();
    }
}

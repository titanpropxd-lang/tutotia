package es.xsinex.tutorial;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * AetheriaTutorial: Guía paso a paso con 3 NPCs, flecha, barra de objetivo, diálogos con voz (AetheriaNPC) y logros.
 * Todo el contenido está en guia.yml. El progreso es POR PERSONAJE (MultiChar) o por jugador si no hay MultiChar.
 */
public final class TutorialPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    static final String DIALOGO_TAG = "aeth_dialogo";      // etiqueta que pone AetheriaNPC mientras el jugador lee un diálogo

    /** estado de un personaje en la guía */
    static final class Est {
        int i;                    // paso actual (posición global)
        boolean explicado;        // el NPC ya contó el paso
        int cont;                 // contador (matar, romper, colocar)
        boolean listo;            // objetivo hecho, falta volver con el NPC (entregar: true)
        long desde;               // cuándo empezó el paso (ESPERAR)
    }

    Guia guia;
    private YamlConfiguration prog;                         // progreso.yml
    private File progFile, npcFile;
    private YamlConfiguration npcData;                      // npcs.yml (posiciones)
    private final Map<String, Est> est = new HashMap<>();
    private final Map<UUID, BossBar> barras = new HashMap<>();
    private final Map<UUID, Integer> charlando = new HashMap<>();   // jugador -> id de tarea de diálogo en curso
    private final Map<UUID, Long> avisoNpc = new HashMap<>();
    private NamespacedKey kNpc;
    private boolean hayDialogos;
    Mundo mundo;
    private final java.util.Set<UUID> prueba = new java.util.HashSet<>();           // admins probando el tutorial
    private final Map<UUID, org.bukkit.GameMode> gmPrevio = new HashMap<>();

    // ------------------------------------------------------------------ ciclo de vida
    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "guia.yml").exists()) saveResource("guia.yml", false);
        kNpc = new NamespacedKey(this, "tutorial_npc");
        progFile = new File(getDataFolder(), "progreso.yml");
        npcFile = new File(getDataFolder(), "npcs.yml");
        cargarTodo();
        mundo = new Mundo(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(mundo, this);
        registrarMultiChar();
        Bukkit.getScheduler().runTask(this, () -> { mundo.mundo(); mundo.autoConstruir(); mantenerEspera(); });
        for (String c : new String[] { "guia", "tutorial" }) {
            org.bukkit.command.PluginCommand pc = getCommand(c);
            if (pc != null) { pc.setExecutor(this); pc.setTabCompleter(this); }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 40L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, this::guardar, 6000L, 6000L);
        Bukkit.getScheduler().runTaskTimer(this, this::mantenerEspera, 200L, 600L);   // por si alguien hace /multichar reload
        getLogger().info("Tutorial listo: " + guia.caps.size() + " capitulos, " + guia.todos.size() + " pasos. AetheriaNPC: " + (hayDialogos ? "si (cajas de dialogo + voces)" : "no (se usa el chat)"));
    }

    @Override
    public void onDisable() {
        guardar();
        try { if (mundo != null) mundo.rec.restaurarTodo(); } catch (Throwable ignored) { }
        for (BossBar b : barras.values()) b.removeAll();
        barras.clear();
    }

    private void cargarTodo() {
        reloadConfig();
        guia = Guia.cargar(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "guia.yml")));
        prog = YamlConfiguration.loadConfiguration(progFile);
        npcData = YamlConfiguration.loadConfiguration(npcFile);
        est.clear();
        for (Guia.NpcDef nd : guia.npcs.values()) { String pc = npcData.getString("npc." + nd.id + ".citizens", ""); if (!pc.isEmpty() && nd.citizens.isEmpty()) nd.citizens = pc; }
        hayDialogos = Bukkit.getPluginManager().getPlugin("AetheriaNPC") != null && getConfig().getBoolean("usar-aetherianpc", true);
    }

    private void guardar() {
        try {
            for (Map.Entry<String, Est> e : est.entrySet()) {
                prog.set("p." + e.getKey() + ".i", e.getValue().i);
                prog.set("p." + e.getKey() + ".e", e.getValue().explicado);
                prog.set("p." + e.getKey() + ".c", e.getValue().cont);
            }
            prog.save(progFile);
            npcData.save(npcFile);
        } catch (Exception ex) { getLogger().warning("guardar: " + ex); }
    }

    // ------------------------------------------------------------------ utilidades
    static Component c(String s) { return LegacyComponentSerializer.legacySection().deserialize(s.replace('&', '§')); }

    private String pfx() { return getConfig().getString("prefijo", "§6✦ §f"); }

    /** id del personaje activo (MultiChar) o UUID del jugador */
    String clave(Player p) {
        try {
            org.bukkit.plugin.Plugin mc = Bukkit.getPluginManager().getPlugin("MultiChar");
            if (mc != null) {
                Class<?> api = Class.forName("es.xsinex.multichar.MultiCharAPI", true, mc.getClass().getClassLoader());
                Object ch = api.getMethod("getActiveCharacter", Player.class).invoke(null, p);
                if (ch == null) return modoTutorial(p) ? "u" + p.getUniqueId() : null;   // sin personaje: solo dentro del tutorial
                return null;                                                                  // con personaje: la guía ya se hizo en el mundo del tutorial
            }
        } catch (Throwable ignored) { }
        return "u" + p.getUniqueId();
    }

    // ------------------------------------------------------------------ cuenta / tutorial previo al personaje
    boolean cuentaHecha(Player p) { return prog.getBoolean("cuenta." + p.getUniqueId() + ".hecha", false); }

    /** MultiChar: primera vez del jugador, esperando (el tutorial ocupa esa espera). */
    boolean enEspera(Player p) {
        try {
            NamespacedKey ini = NamespacedKey.fromString("multichar:espera_inicio"), fin = NamespacedKey.fromString("multichar:espera_hecha");
            if (ini == null || fin == null) return false;
            return p.getPersistentDataContainer().has(ini, PersistentDataType.LONG) && !p.getPersistentDataContainer().has(fin, PersistentDataType.INTEGER);
        } catch (Throwable t) { return false; }
    }

    boolean modoTutorial(Player p) {
        if (cuentaHecha(p) && !prueba.contains(p.getUniqueId())) return false;
        if (prueba.contains(p.getUniqueId())) return true;
        if (mundo != null && mundo.esDelTutorial(p.getWorld())) return true;
        return enEspera(p);
    }

    /** Sube la espera de primera vez de MultiChar para que el tutorial no se corte (solo en memoria; pon lo mismo en su config.yml). */
    /** Antes de que MultiChar lea la espera (al entrar), asegura que sea larga: asi nadie empieza solo a los minutos. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void antesDeEntrar(org.bukkit.event.player.PlayerLoginEvent ev) { mantenerEspera(); }

    void mantenerEspera() {
        try {
            org.bukkit.plugin.Plugin mc = Bukkit.getPluginManager().getPlugin("MultiChar");
            if (mc == null) return;
            int want = getConfig().getInt("espera-multichar-seg", 604800);
            if (mc.getConfig().getInt("first-join.wait-seconds", 100) < want) mc.getConfig().set("first-join.wait-seconds", want);
        } catch (Throwable t) { getLogger().warning("mantenerEspera: " + t); }
    }

    Est est(Player p) {
        String k = clave(p);
        if (k == null) return null;
        Est e = est.get(k);
        if (e == null) {
            e = new Est();
            if (prog.contains("p." + k)) {
                e.i = prog.getInt("p." + k + ".i", 0);
                e.explicado = prog.getBoolean("p." + k + ".e", false);
                e.cont = prog.getInt("p." + k + ".c", 0);
            }
            e.desde = System.currentTimeMillis();
            est.put(k, e);
        }
        return e;
    }

    Guia.Paso paso(Est e) { return e == null || e.i >= guia.todos.size() ? null : guia.todos.get(e.i); }

    private boolean hudApagado(Player p) { return prog.getStringList("hud-off").contains(p.getUniqueId().toString()); }

    private void setHudApagado(Player p, boolean off) {
        List<String> l = new ArrayList<>(prog.getStringList("hud-off"));
        String u = p.getUniqueId().toString();
        l.remove(u);
        if (off) l.add(u);
        prog.set("hud-off", l);
    }

    // ------------------------------------------------------------------ NPCs
    private String npcDeEntidad(Entity e) {
        if (e == null) return null;
        String s = e.getPersistentDataContainer().get(kNpc, PersistentDataType.STRING);
        if (s != null) return s;
        Integer cid = citizensId(e);
        if (cid != null) for (Guia.NpcDef n : guia.npcs.values()) if (!n.citizens.isEmpty() && n.citizens.equals(String.valueOf(cid))) return n.id;
        return null;
    }

    private Integer citizensId(Entity e) {
        try {
            if (Bukkit.getPluginManager().getPlugin("Citizens") == null) return null;
            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object reg = api.getMethod("getNPCRegistry").invoke(null);
            Object npc = reg.getClass().getMethod("getNPC", Entity.class).invoke(reg, e);
            if (npc == null) return null;
            return (Integer) npc.getClass().getMethod("getId").invoke(npc);
        } catch (Throwable t) { return null; }
    }

    /** dónde está un NPC de la guía (propio o de Citizens) */
    Location npcLoc(String id) {
        Guia.NpcDef d = guia.npcs.get(id);
        if (d != null && !d.citizens.isEmpty()) {
            try {
                Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
                Object reg = api.getMethod("getNPCRegistry").invoke(null);
                Object npc = reg.getClass().getMethod("getById", int.class).invoke(reg, Integer.parseInt(d.citizens));
                if (npc != null) {
                    Object ent = npc.getClass().getMethod("getEntity").invoke(npc);
                    if (ent instanceof Entity) return ((Entity) ent).getLocation();
                    Object st = npc.getClass().getMethod("getStoredLocation").invoke(npc);
                    if (st instanceof Location) return (Location) st;
                }
            } catch (Throwable ignored) { }
        }
        String w = npcData.getString("npc." + id + ".mundo");
        if (w == null || Bukkit.getWorld(w) == null) return null;
        return new Location(Bukkit.getWorld(w), npcData.getDouble("npc." + id + ".x"), npcData.getDouble("npc." + id + ".y"), npcData.getDouble("npc." + id + ".z"),
                (float) npcData.getDouble("npc." + id + ".yaw"), 0f);
    }

    private void quitarNpc(String id) {
        for (World w : Bukkit.getWorlds())
            for (Entity e : w.getEntities()) if (id.equals(e.getPersistentDataContainer().get(kNpc, PersistentDataType.STRING))) e.remove();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void crearNpc(String id, Location l) {
        Guia.NpcDef d = guia.npcs.get(id);
        if (d == null) return;
        if (getConfig().getBoolean("npcs.usar-citizens", true) && crearNpcCitizens(id, d, l)) return;
        quitarNpc(id);
        Villager v = (Villager) l.getWorld().spawnEntity(l, EntityType.VILLAGER);
        v.setAI(false);
        v.setInvulnerable(true);
        v.setSilent(true);
        v.setCollidable(false);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.customName(c(getConfig().getString("color-nombre", "§e") + d.nombre));
        v.setCustomNameVisible(true);
        try { v.setProfession((Villager.Profession) Villager.Profession.class.getField(d.profesion.toUpperCase(Locale.ROOT)).get(null)); } catch (Throwable ignored) { }
        v.getPersistentDataContainer().set(kNpc, PersistentDataType.STRING, id);
        npcData.set("npc." + id + ".mundo", l.getWorld().getName());
        npcData.set("npc." + id + ".x", l.getX());
        npcData.set("npc." + id + ".y", l.getY());
        npcData.set("npc." + id + ".z", l.getZ());
        npcData.set("npc." + id + ".yaw", (double) l.getYaw());
        guardar();
    }

    void colocarNpc(String id, Location l) { crearNpc(id, l); }

    // ------------------------------------------------------------------ NPCs de Citizens (se crean solos; sin dependencia en compilacion)
    private Object citizensReg() throws Exception {
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) return null;
        return Class.forName("net.citizensnpcs.api.CitizensAPI").getMethod("getNPCRegistry").invoke(null);
    }

    private Object citizensGet(String cid) {
        try {
            Object reg = citizensReg();
            if (reg == null || cid == null || cid.isEmpty()) return null;
            return Class.forName("net.citizensnpcs.api.npc.NPCRegistry").getMethod("getById", int.class).invoke(reg, Integer.parseInt(cid));
        } catch (Throwable ex) { return null; }
    }

    /** Crea (o recrea) el NPC de Citizens de la guia en l. Devuelve false si Citizens no esta (entonces se usa el aldeano propio). */
    private boolean crearNpcCitizens(String id, Guia.NpcDef d, Location l) {
        try {
            Object reg = citizensReg();
            if (reg == null) return false;
            Class<?> npcC = Class.forName("net.citizensnpcs.api.npc.NPC");
            Class<?> regC = Class.forName("net.citizensnpcs.api.npc.NPCRegistry");
            quitarNpc(id);
            Object old = citizensGet(npcData.getString("npc." + id + ".citizens", ""));
            if (old != null) npcC.getMethod("destroy").invoke(old);
            Object npc = regC.getMethod("createNPC", EntityType.class, String.class).invoke(reg, EntityType.PLAYER, getConfig().getString("color-nombre", "§e") + d.nombre);
            try { npcC.getMethod("setProtected", boolean.class).invoke(npc, true); } catch (Throwable ignored) { }
            try {
                Object look = npcC.getMethod("getOrAddTrait", Class.class).invoke(npc, Class.forName("net.citizensnpcs.trait.LookClose"));
                look.getClass().getMethod("lookClose", boolean.class).invoke(look, true);
            } catch (Throwable ignored) { }
            String skin = getConfig().getString("npcs.skins." + id, "");
            if (!skin.isEmpty()) {
                try {
                    Object st = npcC.getMethod("getOrAddTrait", Class.class).invoke(npc, Class.forName("net.citizensnpcs.trait.SkinTrait"));
                    st.getClass().getMethod("setSkinName", String.class).invoke(st, skin);
                } catch (Throwable ex) { getLogger().warning("skin de " + id + ": " + ex); }
            }
            npcC.getMethod("spawn", Location.class).invoke(npc, l);
            int cid = (Integer) npcC.getMethod("getId").invoke(npc);
            d.citizens = String.valueOf(cid);
            npcData.set("npc." + id + ".citizens", String.valueOf(cid));
            npcData.set("npc." + id + ".mundo", l.getWorld().getName());
            npcData.set("npc." + id + ".x", l.getX());
            npcData.set("npc." + id + ".y", l.getY());
            npcData.set("npc." + id + ".z", l.getZ());
            npcData.set("npc." + id + ".yaw", (double) l.getYaw());
            guardar();
            getLogger().info("NPC de Citizens '" + d.nombre + "' creado (id " + cid + ").");
            return true;
        } catch (Throwable ex) { getLogger().warning("Citizens: no pude crear a " + id + ": " + ex); return false; }
    }


    private void repararNpcs() {
        for (Guia.NpcDef d : guia.npcs.values()) {
            String propio = npcData.getString("npc." + d.id + ".citizens", "");
            if (!propio.isEmpty() && d.citizens.isEmpty()) d.citizens = propio;
            if (!d.citizens.isEmpty()) {
                if (propio.isEmpty() || citizensGet(propio) != null || Bukkit.getPluginManager().getPlugin("Citizens") == null) continue;   // el nuestro existe
                World cw = Bukkit.getWorld(npcData.getString("npc." + d.id + ".mundo", ""));
                if (cw == null) continue;
                Location cl = new Location(cw, npcData.getDouble("npc." + d.id + ".x"), npcData.getDouble("npc." + d.id + ".y"), npcData.getDouble("npc." + d.id + ".z"), (float) npcData.getDouble("npc." + d.id + ".yaw"), 0f);
                if (cw.isChunkLoaded(cl.getBlockX() >> 4, cl.getBlockZ() >> 4)) crearNpc(d.id, cl);
                continue;
            }
            Location l = npcLoc(d.id);
            if (l == null) continue;
            boolean hay = false;
            if (l.getWorld() != null && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4))
                for (Entity e : l.getWorld().getNearbyEntities(l, 6, 6, 6)) if (d.id.equals(e.getPersistentDataContainer().get(kNpc, PersistentDataType.STRING))) hay = true;
            if (!hay && l.getWorld() != null && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) crearNpc(d.id, l);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onNpcDamage(EntityDamageEvent ev) {
        if (ev.getEntity().getPersistentDataContainer().has(kNpc, PersistentDataType.STRING)) ev.setCancelled(true);
    }

    @EventHandler
    public void onHablar(PlayerInteractEntityEvent ev) {
        if (ev.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        String npc = npcDeEntidad(ev.getRightClicked());
        if (npc == null) return;
        ev.setCancelled(true);
        hablar(ev.getPlayer(), npc);
    }

    // ------------------------------------------------------------------ flujo de la guía
    private void hablar(Player p, String npc) {
        Est e = est(p);
        if (e == null) { p.sendMessage(c(pfx() + (cuentaHecha(p) ? "§aTu paso por la isla ya terminó. ¡Disfruta Aetheria!" : "Tu diario de viaje solo se escribe en la isla."))); return; }
        Guia.NpcDef def = guia.npcs.get(npc);
        String nombre = def == null ? npc : def.nombre;
        Guia.Paso s = paso(e);
        if (charlando.containsKey(p.getUniqueId())) return;     // ya está hablando
        if (s == null) { decir(p, nombre, getConfig().getString("despedida", "Ya lo sabes todo, viajero. Que los caminos te sean favorables."), null); return; }
        if (!s.npc.equals(npc)) {
            Guia.NpcDef otro = guia.npcs.get(s.npc);
            decir(p, nombre, getConfig().getString("lejos", "Ahora te toca hablar con {npc}. Sigue la flecha.").replace("{npc}", otro == null ? s.npc : otro.nombre), null);
            return;
        }
        if (!e.explicado) {
            reproducir(p, nombre, s.dialogo, () -> {
                Est e2 = est(p);
                Guia.Paso s2 = paso(e2);
                if (e2 == null || s2 != s) return;
                e2.explicado = true;
                e2.cont = 0;
                e2.desde = System.currentTimeMillis();
                for (String cmd : s.preparar) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{jugador}", p.getName()));
                if (s.tipo.equals("NINGUNO")) completar(p, s); else avisar(p, s);
            });
            return;
        }
        if (s.tipo.equals("CONFIRMAR")) { completar(p, s); return; }
        if (e.listo) {
            final Guia.Paso sp = s;
            decir(p, nombre, getConfig().getString("entrega", "Muy bien. Esto está bien hecho, viajero. Quédatelo, ya es tuyo."), () -> {
                Est e3 = est(p);
                if (e3 == null || paso(e3) != sp) return;
                sp.obj.set("entregar", false);
                try { completar(p, sp); } finally { sp.obj.set("entregar", true); }
            });
            return;
        }
        String rec = s.recordatorio.isEmpty() ? getConfig().getString("recordatorio", "Aún te falta: {pista}") : s.recordatorio;
        decir(p, nombre, rec.replace("{pista}", s.pista), null);
    }

    private void avisar(Player p, Guia.Paso s) {
        p.sendMessage(c(pfx() + "§eObjetivo: §f" + s.pista));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.4f);
    }

    /** Muestra una línea con la caja de AetheriaNPC (con voz) o por chat si no está. */
    private void decir(Player p, String nombre, String texto, Runnable luego) {
        if (hayDialogos) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dialogo decir " + p.getName() + " " + nombre + "|" + texto);
        } else {
            p.sendMessage(c("§e" + nombre + "§7: §f" + texto));
        }
        if (luego != null) luego.run();
    }

    /** Reproduce varias líneas una tras otra, esperando a que el jugador termine de leer cada una. */
    private void reproducir(Player p, String nombre, List<String> lineas, Runnable fin) {
        if (lineas.isEmpty()) { if (fin != null) fin.run(); return; }
        final UUID u = p.getUniqueId();
        final int[] i = { 0 };
        final long[] espera = { 0 };
        final boolean[] lanzada = { false };
        int id = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                Player pl = Bukkit.getPlayer(u);
                if (pl == null || !pl.isOnline()) { parar(u); return; }
                if (espera[0] > 0) { espera[0] -= 4; return; }
                if (lanzada[0]) {
                    boolean leyendo = hayDialogos && pl.getScoreboardTags().contains(DIALOGO_TAG);
                    if (leyendo) return;
                    lanzada[0] = false;
                    i[0]++;
                    espera[0] = 8;
                    return;
                }
                if (i[0] >= lineas.size()) { parar(u); if (fin != null) fin.run(); return; }
                decir(pl, nombre, lineas.get(i[0]), null);
                lanzada[0] = true;
                // sin AetheriaNPC: pausa fija según el largo del texto
                if (!hayDialogos) { espera[0] = Math.max(40, lineas.get(i[0]).length() * 2L); lanzada[0] = true; }
                else espera[0] = 12;           // deja que AetheriaNPC ponga su etiqueta
            }
        }, 2L, 4L).getTaskId();
        charlando.put(u, id);
    }

    private void parar(UUID u) {
        Integer t = charlando.remove(u);
        if (t != null) Bukkit.getScheduler().cancelTask(t);
    }

    private void completar(Player p, Guia.Paso s) {
        Est e = est(p);
        if (e == null || paso(e) != s) return;
        if (s.obj != null && s.obj.getBoolean("entregar", false) && !s.tipo.equals("CONFIRMAR")) {
            if (!e.listo) {
                e.listo = true;
                Guia.NpcDef nd = guia.npcs.get(s.npc);
                p.sendMessage(c(pfx() + "§a✔ ¡Lo conseguiste! §7Vuelve con §e" + (nd == null ? s.npc : nd.nombre) + "§7 para enseñárselo."));
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
            }
            return;
        }
        e.listo = false;
        e.i = s.indice + 1;
        e.explicado = false;
        e.cont = 0;
        e.desde = System.currentTimeMillis();
        for (String cmd : s.recompensa) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{jugador}", p.getName()));
        p.showTitle(Title.title(c("§a✔ " + s.titulo), c("§7Paso completado"), Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(1500), java.time.Duration.ofMillis(400))));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
        boolean finCap = s.cap.pasos.get(s.cap.pasos.size() - 1) == s;
        if (finCap) {
            p.sendMessage(c(pfx() + "§a✦ §e" + s.cap.nombre + " §7· §adominado"));
            logro(p, s.cap.logro);
            for (String cmd : getConfig().getStringList("recompensa-capitulo")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{jugador}", p.getName()).replace("{capitulo}", s.cap.id));
        }
        if (e.i >= guia.todos.size()) {
            logro(p, "maestro");
            p.sendMessage(c(pfx() + "§6Estás listo para cruzar. El barco te espera en el muelle."));
            String kk = clave(p);
            if (kk != null && kk.startsWith("u") && mundo != null && mundo.esDelTutorial(p.getWorld())) terminarTutorial(p);
            else for (String cmd : getConfig().getStringList("recompensa-final")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{jugador}", p.getName()));
        } else {
            Guia.Paso n = guia.todos.get(e.i);
            Guia.NpcDef d = guia.npcs.get(n.npc);
            p.sendMessage(c(pfx() + "§7Siguiente: §fhabla con §e" + (d == null ? n.npc : d.nombre) + "§f."));
        }
        guardar();
    }

    /** Termina el tutorial: espera a que cierre el diálogo y arranca el viaje en barco. */
    private void terminarTutorial(Player p) {
        final UUID u = p.getUniqueId();
        prog.set("cuenta." + u + ".hecha", true);
        prog.set("cuenta." + u + ".fecha", System.currentTimeMillis());
        guardar();
        BossBar bb = barras.remove(u);
        if (bb != null) bb.removeAll();
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player pl = Bukkit.getPlayer(u);
                if (pl == null || !pl.isOnline()) { this.cancel(); return; }
                t += 5;
                boolean leyendo = hayDialogos && pl.getScoreboardTags().contains(DIALOGO_TAG);
                if (t < 60 || (leyendo && t < 400)) return;
                this.cancel();
                mundo.barco(pl, () -> salidaTutorial(pl));
            }
        }.runTaskTimer(this, 20L, 5L);
    }

    // ------------------------------------------------------------------ intro: solo cuando el personaje NUEVO entra al mundo por primera vez
    @SuppressWarnings("unchecked")
    private void registrarMultiChar() {
        try {
            org.bukkit.plugin.Plugin mc = Bukkit.getPluginManager().getPlugin("MultiChar");
            if (mc == null) return;
            ClassLoader cl = mc.getClass().getClassLoader();
            Class<? extends org.bukkit.event.Event> cc = (Class<? extends org.bukkit.event.Event>) Class.forName("es.xsinex.multichar.CharacterCreateEvent", true, cl);
            Class<? extends org.bukkit.event.Event> cs = (Class<? extends org.bukkit.event.Event>) Class.forName("es.xsinex.multichar.CharacterLoadedEvent", true, cl);
            Bukkit.getPluginManager().registerEvent(cc, this, EventPriority.NORMAL, (l, e) -> alCrear(e), this);
            Bukkit.getPluginManager().registerEvent(cs, this, EventPriority.NORMAL, (l, e) -> alCargar(e), this);
            getLogger().info("Enganchado a MultiChar: la intro saltará al entrar por primera vez con un personaje nuevo.");
        } catch (Throwable t) { getLogger().warning("No pude enganchar MultiChar (la intro no se lanzará sola): " + t); }
    }

    private Integer idPersonaje(Object evento) {
        try {
            Object ch = evento.getClass().getMethod("getCharacter").invoke(evento);
            return (Integer) ch.getClass().getMethod("getId").invoke(ch);
        } catch (Throwable t) { return null; }
    }

    private void alCrear(Object ev) {
        Integer id = idPersonaje(ev);
        if (id == null) return;
        prog.set("intro.pendiente.c" + id, true);
        guardar();
    }

    private void alCargar(Object ev) {
        Integer id = idPersonaje(ev);
        if (id == null || !prog.getBoolean("intro.pendiente.c" + id, false)) return;
        prog.set("intro.pendiente.c" + id, null);
        guardar();
        Player p;
        try { p = (Player) ev.getClass().getMethod("getPlayer").invoke(ev); } catch (Throwable t) { return; }
        final UUID u = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player pl = Bukkit.getPlayer(u);
            if (pl == null || !pl.isOnline()) return;
            for (String cmd : getConfig().getStringList("recompensa-final")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{jugador}", pl.getName()));
            if (getConfig().getBoolean("intro.activa", true)) lanzarIntro(pl);
        }, getConfig().getLong("intro.retraso-ticks", 40L));
    }

    void lanzarIntro(Player p) {
        for (String cmd : getConfig().getStringList("intro.comandos")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{jugador}", p.getName()));
    }

    // ------------------------------------------------------------------ comandos dentro del tutorial (aún sin personaje)
    private boolean coincide(Guia.Paso s, String valor) {
        List<String> vs = s.obj.getStringList("valor");
        if (vs.isEmpty() && s.obj.isString("valor")) vs = java.util.Collections.singletonList(s.obj.getString("valor"));
        if (vs.isEmpty()) return true;
        for (String v : vs) { String a = v.toLowerCase(Locale.ROOT); if (valor.equals(a) || valor.startsWith(a + " ")) return true; }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCmdTutorial(PlayerCommandPreprocessEvent ev) {
        Player p = ev.getPlayer();
        if (mundo == null || !mundo.esDelTutorial(p.getWorld()) || p.hasPermission("tutorial.admin")) return;
        String k = clave(p);
        if (k == null || !k.startsWith("u")) return;
        String m = ev.getMessage().substring(1).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        int col = m.indexOf(':');
        if (col > 0 && col < m.indexOf(' ') + (m.indexOf(' ') < 0 ? 1000 : 0)) m = m.substring(col + 1);
        String base = m.contains(" ") ? m.substring(0, m.indexOf(' ')) : m;
        if (getConfig().getStringList("comandos-permitidos").contains(base)) return;
        Est e = est(p);
        Guia.Paso s = paso(e);
        if (activo(e, s) && s.tipo.equals("COMANDO") && coincide(s, m)) {
            if (!getConfig().getBoolean("comandos-reales", true)) {
                ev.setCancelled(true);
                p.sendMessage(c(getConfig().getString("demo-comando", "§7(Demostración) Así usarías §e/{cmd}§7 cuando tengas tu personaje. ¡Cuenta como hecho!").replace("{cmd}", m)));
                progreso(p, "COMANDO", m);
            }
            // con comandos-reales el comando se ejecuta de verdad (abre su menú) y lo cuenta onCmd (MONITOR)
        } else {
            ev.setCancelled(true);
            p.sendMessage(c(getConfig().getString("comando-bloqueado", "§7No sabes cómo hacer eso todavía…")));
        }
    }

    void estacionUsada(Player p, String st) { progreso(p, "ESTACION", st); }

    private final Set<UUID> esperandoMenu = new HashSet<>();

    /** El comando se ejecuta de verdad: deja que el jugador mire el menú; el paso se cierra al cerrarlo (o tras unos segundos). */
    private void completarTrasMenu(Player p, Guia.Paso s) {
        final UUID u = p.getUniqueId();
        if (!esperandoMenu.add(u)) return;
        final long max = Math.max(20, getConfig().getInt("menu.espera-maxima-seg", 25)) * 20L;
        new org.bukkit.scheduler.BukkitRunnable() {
            long t = 0; boolean abierto = false;
            @Override public void run() {
                Player q = Bukkit.getPlayer(u);
                if (q == null || !q.isOnline() || paso(est(q)) != s) { esperandoMenu.remove(u); cancel(); return; }
                t += 10;
                boolean menu = q.getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING;
                if (menu) abierto = true;
                boolean listo = abierto ? !menu : t >= getConfig().getInt("menu.espera-sin-menu-ticks", 30);
                if (listo || t >= max) {
                    esperandoMenu.remove(u); cancel();
                    Bukkit.getScheduler().runTaskLater(TutorialPlugin.this, () -> { Player r = Bukkit.getPlayer(u); if (r != null && paso(est(r)) == s) completar(r, s); },
                        getConfig().getInt("menu.pausa-al-cerrar-ticks", 15));
                }
            }
        }.runTaskTimer(this, 10L, 10L);
    }

    private void logro(Player p, String id) {
        if (!getConfig().getBoolean("logros", true)) return;
        try {
            org.bukkit.advancement.Advancement a = Bukkit.getAdvancement(NamespacedKey.fromString("aetheria_tutorial:" + id));
            if (a == null) return;
            org.bukkit.advancement.AdvancementProgress pr = p.getAdvancementProgress(a);
            for (String cr : new ArrayList<>(pr.getRemainingCriteria())) pr.awardCriteria(cr);
        } catch (Throwable t) { getLogger().warning("logro " + id + ": " + t); }
    }

    // ------------------------------------------------------------------ objetivos
    private boolean activo(Est e, Guia.Paso s) { return s != null && e != null && e.explicado && !s.tipo.equals("NINGUNO") && !s.tipo.equals("CONFIRMAR"); }

    private void progreso(Player p, String tipo, String valor) {
        Est e = est(p);
        Guia.Paso s = paso(e);
        if (!activo(e, s) || !s.tipo.equals(tipo)) return;
        List<String> vs = s.obj.getStringList("valor");
        if (vs.isEmpty() && s.obj.isString("valor")) vs = java.util.Collections.singletonList(s.obj.getString("valor"));
        boolean ok = vs.isEmpty();
        for (String v : vs) {
            String a = v.toLowerCase(Locale.ROOT), b = valor.toLowerCase(Locale.ROOT);
            if (tipo.equals("COMANDO") ? (b.equals(a) || b.startsWith(a + " ")) : b.equals(a) || b.contains(a)) ok = true;
        }
        if (!ok) return;
        e.cont++;
        if (e.cont >= Math.max(1, s.obj.getInt("cantidad", 1))) {
            if (tipo.equals("COMANDO") && getConfig().getBoolean("comandos-reales", true)) completarTrasMenu(p, s); else completar(p, s);
        }
        else p.sendActionBar(c("§e" + s.pista + " §7(" + e.cont + "/" + s.obj.getInt("cantidad", 1) + ")"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCmd(PlayerCommandPreprocessEvent ev) {
        String m = ev.getMessage().substring(1).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        int col = m.indexOf(':');
        if (col > 0 && col < m.indexOf(' ') + (m.indexOf(' ') < 0 ? 1000 : 0)) m = m.substring(col + 1);   // quita "plugin:"
        progreso(ev.getPlayer(), "COMANDO", m);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRomper(BlockBreakEvent ev) { progreso(ev.getPlayer(), "ROMPER", ev.getBlock().getType().name()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onColocar(BlockPlaceEvent ev) { progreso(ev.getPlayer(), "COLOCAR", ev.getBlockPlaced().getType().name()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatar(EntityDeathEvent ev) {
        Player k = ev.getEntity().getKiller();
        if (k != null) progreso(k, "MATAR", ev.getEntityType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUsar(PlayerInteractEvent ev) {
        if (ev.getAction() != Action.RIGHT_CLICK_BLOCK || ev.getClickedBlock() == null || ev.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        progreso(ev.getPlayer(), "USAR_BLOQUE", ev.getClickedBlock().getType().name());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent ev) {
        mantenerEspera();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p = ev.getPlayer();
            if (!p.isOnline()) return;
            if (enEspera(p) && cuentaHecha(p)) { salidaTutorial(p); return; }      // se desconectó durante el viaje: que siga a elegir personaje
            if (enEspera(p) && !cuentaHecha(p)) { entrarTutorial(p); return; }
            if (prueba.contains(p.getUniqueId())) return;
        }, 10L);
    }

    /** Primera vez: al mundo del tutorial, con la guía de Alba. */
    private void entrarTutorial(Player p) {
        if (mundo == null || mundo.mundo() == null) { getLogger().warning("No hay mundo de tutorial: " + p.getName() + " sigue al flujo normal."); salidaTutorial(p); return; }
        if (mundo.ocupado()) {   // la isla aun se esta construyendo: espera y reintenta
            p.sendMessage(c(pfx() + "§7La marea te trae hasta la orilla… un momento."));
            Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) entrarTutorial(p); }, 60L);
            return;
        }
        boolean primera = !prog.contains("p.u" + p.getUniqueId());
        if (!mundo.esDelTutorial(p.getWorld())) mundo.llevar(p);
        p.setGameMode(org.bukkit.GameMode.ADVENTURE);
        p.setHealth(Math.min(20.0, p.getMaxHealth()));
        p.setFoodLevel(20);
        if (primera) {
            p.getInventory().clear();
            p.showTitle(Title.title(c("§6Isla Alborada"), c("§7Una voz te llama desde la plaza"), Title.Times.times(java.time.Duration.ofMillis(800), java.time.Duration.ofMillis(3500), java.time.Duration.ofMillis(1000))));
        }
        p.sendMessage(c(pfx() + "§7Despiertas en la orilla de una isla desconocida. Alguien te hace señas desde la plaza…"));
    }

    /** Fin del tutorial: limpia al jugador y MultiChar lo lleva a elegir/crear personaje. */
    void salidaTutorial(Player p) {
        UUID u = p.getUniqueId();
        for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(pe.getType());
        p.resetPlayerTime();
        if (prueba.remove(u)) {                                  // un admin probando: vuelve a donde estaba
            org.bukkit.GameMode g = gmPrevio.remove(u);
            if (g != null) p.setGameMode(g);
            Location back = Bukkit.getWorlds().get(0).getSpawnLocation();
            p.teleport(back);
            p.sendMessage(c(pfx() + "§7(Prueba terminada: te devolví al spawn del mundo principal.)"));
            return;
        }
        p.getInventory().clear();
        if (enEspera(p)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "multichar espera " + p.getName());
        } else {
            getLogger().warning(p.getName() + " terminó el tutorial pero no está en la espera de MultiChar.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        parar(ev.getPlayer().getUniqueId());
        BossBar b = barras.remove(ev.getPlayer().getUniqueId());
        if (b != null) b.removeAll();
    }

    /** Cada 0,5 s: barra de objetivo, flecha y objetivos por sondeo (ITEM, AREA, CERCA_NPC, PERSONAJE, ESPERAR). */
    private int ciclo = 0;

    private void tick() {
        ciclo++;
        if (ciclo % 60 == 0) repararNpcs();
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { tickJugador(p); } catch (Throwable t) { if (ciclo % 200 == 0) getLogger().warning("tick " + p.getName() + ": " + t); }
        }
    }

    private void tickJugador(Player p) {
        Est e = est(p);
        BossBar b = barras.get(p.getUniqueId());
        if (e == null || hudApagado(p) || !getConfig().getBoolean("barra", true)) {
            if (b != null) { b.removeAll(); barras.remove(p.getUniqueId()); }
            return;
        }
        Guia.Paso s = paso(e);
        if (s == null) {
            if (b != null) { b.removeAll(); barras.remove(p.getUniqueId()); }
            return;
        }
        // sondeo de objetivos
        if (activo(e, s) && ciclo % 2 == 0) sondear(p, e, s);
        // barra
        if (b == null) {
            b = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SEGMENTED_10);
            b.addPlayer(p);
            barras.put(p.getUniqueId(), b);
        }
        Guia.NpcDef d = guia.npcs.get(s.npc);
        String txt = e.explicado ? s.pista : "Habla con " + (d == null ? s.npc : d.nombre);
        b.setTitle("§6Tu travesía §7· §f" + s.cap.nombre + " §8» §e" + txt);
        b.setProgress(Math.max(0.0, Math.min(1.0, e.i / (double) guia.todos.size())));
        // flecha
        if (p.getScoreboardTags().contains(DIALOGO_TAG) || !getConfig().getBoolean("flecha", true)) return;
        Location t = destino(e, s);
        if (t == null || t.getWorld() == null) return;
        if (!t.getWorld().equals(p.getWorld())) { p.sendActionBar(c("§e" + txt + " §7(otro mundo)")); return; }
        double dx = t.getX() - p.getLocation().getX(), dz = t.getZ() - p.getLocation().getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double yawT = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((yawT - p.getLocation().getYaw()) % 360 + 540) % 360 - 180;
        String[] fl = { "⬆", "⬈", "➡", "⬊", "⬇", "⬋", "⬅", "⬉" };
        String arrow = fl[((int) Math.round(rel / 45.0) % 8 + 8) % 8];
        p.sendActionBar(c("§6" + arrow + " §e" + (int) dist + " m §7· §f" + txt));
        if (getConfig().getBoolean("particulas", true) && ciclo % 4 == 0 && dist > 4) {
            double ux = dx / dist, uz = dz / dist;
            for (int k = 2; k <= 8; k += 2)
                p.spawnParticle(Particle.END_ROD, p.getLocation().add(ux * k, 1.0, uz * k), 1, 0, 0, 0, 0);
        }
    }

    private Location destino(Est e, Guia.Paso s) {
        if (!e.explicado || e.listo || s.tipo.equals("CONFIRMAR") || s.obj == null) return npcLoc(s.npc);
        if (s.obj.isString("zona") && mundo != null) { Location z = mundo.rec.centro(s.obj.getString("zona")); if (z != null) return z; }
        if (s.tipo.equals("CERCA_NPC")) return npcLoc(s.obj.getString("npc", s.npc));
        if (s.tipo.equals("AREA")) {
            World w = Bukkit.getWorld(s.obj.getString("mundo", "world"));
            return w == null ? null : new Location(w, s.obj.getDouble("x"), s.obj.getDouble("y"), s.obj.getDouble("z"));
        }
        return null;
    }

    private void sondear(Player p, Est e, Guia.Paso s) {
        switch (s.tipo) {
            case "ITEM": {
                String mat = s.obj.getString("material", "");
                String nombre = s.obj.getString("nombre", "").toLowerCase(Locale.ROOT);
                int need = Math.max(1, s.obj.getInt("cantidad", 1)), have = 0;
                for (ItemStack it : p.getInventory().getContents()) {
                    if (it == null || it.getType() == Material.AIR) continue;
                    if (!mat.isEmpty() && !it.getType().name().equalsIgnoreCase(mat)) continue;
                    if (!nombre.isEmpty()) {
                        ItemMeta m = it.getItemMeta();
                        String dn = m == null || !m.hasDisplayName() ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(m.displayName()).toLowerCase(Locale.ROOT);
                        if (!dn.contains(nombre)) continue;
                    }
                    have += it.getAmount();
                }
                if (have >= need) completar(p, s);
                break;
            }
            case "FORJA": {
                List<String> ids = s.obj.getStringList("ids");
                int need = Math.max(1, s.obj.getInt("cantidad", 1));
                org.bukkit.NamespacedKey kk = NamespacedKey.fromString(getConfig().getString("forja-clave", "forja:rz_item"));
                if (ids.isEmpty() || kk == null) break;
                Map<String, Integer> tiene = new HashMap<>();
                for (ItemStack it : p.getInventory().getContents()) {
                    if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) continue;
                    String idf = it.getItemMeta().getPersistentDataContainer().get(kk, org.bukkit.persistence.PersistentDataType.STRING);
                    if (idf != null) tiene.merge(idf.toLowerCase(Locale.ROOT), it.getAmount(), Integer::sum);
                }
                for (ItemStack it : p.getInventory().getArmorContents()) {
                    if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) continue;
                    String idf = it.getItemMeta().getPersistentDataContainer().get(kk, org.bukkit.persistence.PersistentDataType.STRING);
                    if (idf != null) tiene.merge(idf.toLowerCase(Locale.ROOT), it.getAmount(), Integer::sum);
                }
                int ok = 0;
                for (String id : ids) if (tiene.getOrDefault(id.toLowerCase(Locale.ROOT), 0) >= need) ok++;
                boolean todos = s.obj.getBoolean("todos", true);
                if (todos ? ok == ids.size() : ok > 0) completar(p, s);
                else if (todos && ok != e.cont) { e.cont = ok; p.sendActionBar(c("§e" + s.pista + " §7(" + ok + "/" + ids.size() + ")")); }
                break;
            }
            case "AREA":
            case "CERCA_NPC": {
                Location t = destino(e, s);
                if (t != null && t.getWorld() != null && t.getWorld().equals(p.getWorld()) && t.distanceSquared(p.getLocation()) <= Math.pow(s.obj.getDouble("radio", 5), 2)) completar(p, s);
                break;
            }
            case "ESPERAR":
                if (System.currentTimeMillis() - e.desde >= s.obj.getInt("segundos", 10) * 1000L) completar(p, s);
                break;
            case "PERSONAJE":
                completar(p, s);       // si llegó aquí ya tiene personaje activo
                break;
            default:
        }
    }

    // ------------------------------------------------------------------ menú /guia
    static final class Menu implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private void menu(Player p) {
        Est e = est(p);
        if (e == null) { p.sendMessage(c(pfx() + (cuentaHecha(p) ? "§aTu paso por la isla ya terminó. ¡Disfruta Aetheria!" : "Tu diario de viaje solo se escribe en la isla."))); return; }
        Inventory inv = Bukkit.createInventory(new Menu(), 27, c("§6Diario de viaje"));
        int slot = 10;
        for (Guia.Cap cap : guia.caps) {
            Material m;
            try { m = Material.valueOf(cap.icono.toUpperCase(Locale.ROOT)); } catch (Throwable t) { m = Material.BOOK; }
            ItemStack it = new ItemStack(m);
            ItemMeta im = it.getItemMeta();
            int hechos = 0;
            List<Component> lore = new ArrayList<>();
            if (!cap.descripcion.isEmpty()) lore.add(c("§7" + cap.descripcion));
            for (Guia.Paso s : cap.pasos) {
                boolean ok = s.indice < e.i, ya = s.indice == e.i;
                if (ok) hechos++;
                lore.add(c((ok ? "§a✔ " : ya ? "§e▶ " : "§8· ") + s.titulo));
            }
            boolean completo = hechos == cap.pasos.size();
            im.displayName(c((completo ? "§a" : "§6") + cap.nombre + " §7(" + hechos + "/" + cap.pasos.size() + ")"));
            im.lore(lore);
            it.setItemMeta(im);
            if (slot < 17 || slot > 17) inv.setItem(slot, it);
            slot++;
            if (slot == 17) slot = 19;
            if (slot > 25) break;
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent ev) {
        if (ev.getView().getTopInventory().getHolder() instanceof Menu) ev.setCancelled(true);
    }

    private void estadoChat(Player p) {
        Est e = est(p);
        Guia.Paso s = paso(e);
        if (e == null) { p.sendMessage(c(pfx() + (cuentaHecha(p) ? "§aTu paso por la isla ya terminó. ¡Disfruta Aetheria!" : "Tu diario de viaje solo se escribe en la isla."))); return; }
        if (s == null) { p.sendMessage(c(pfx() + "§aYa no queda nada pendiente en la isla.")); return; }
        Guia.NpcDef d = guia.npcs.get(s.npc);
        p.sendMessage(c(pfx() + "§e" + s.cap.nombre + " §7› §f" + s.titulo + " §7(" + (e.i + 1) + "/" + guia.todos.size() + ")"));
        p.sendMessage(c("§7" + (e.explicado ? "Objetivo: §f" + s.pista : "Habla con §e" + (d == null ? s.npc : d.nombre))));
    }

    // ------------------------------------------------------------------ comandos
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String lab, String[] a) {
        if (cmd.getName().equalsIgnoreCase("guia")) {
            if (!(s instanceof Player)) { s.sendMessage("Solo jugadores."); return true; }
            Player p = (Player) s;
            String sub = a.length == 0 ? "menu" : a[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "menu": menu(p); break;
                case "paso": case "estado": estadoChat(p); break;
                case "barra": case "flecha": {
                    boolean off = !hudApagado(p);
                    setHudApagado(p, off);
                    p.sendMessage(c(pfx() + "Barra y flecha de rumbo: " + (off ? "§cocultas" : "§aactivas")));
                    break;
                }
                case "saltar": {
                    if (!getConfig().getBoolean("jugadores-pueden-saltar", false) && !p.hasPermission("tutorial.admin")) { p.sendMessage(c(pfx() + "§cNo puedes saltar pasos.")); break; }
                    Est e = est(p);
                    Guia.Paso st = paso(e);
                    if (st != null) { e.explicado = true; completar(p, st); }
                    break;
                }
                default: p.sendMessage(c(pfx() + "/guia [paso|barra|saltar]"));
            }
            return true;
        }
        // /tutorial (admin)
        if (!s.hasPermission("tutorial.admin")) { s.sendMessage("§cSin permiso."); return true; }
        String sub = a.length == 0 ? "ayuda" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "npc": {
                if (a.length < 2) { s.sendMessage("/tutorial npc <crear|mover|borrar|lista|reparar> [alba|brasa|valdor]"); return true; }
                String op = a[1].toLowerCase(Locale.ROOT);
                if (op.equals("lista")) {
                    for (Guia.NpcDef d : guia.npcs.values()) { Location l = npcLoc(d.id); s.sendMessage("§e" + d.id + " §7(" + d.nombre + ") " + (l == null ? "§csin colocar" : "§a" + l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()) + (d.citizens.isEmpty() ? "" : " §7[Citizens " + d.citizens + "]")); }
                    return true;
                }
                if (op.equals("reparar")) { repararNpcs(); s.sendMessage("§aRevisados."); return true; }
                if (a.length < 3 || !guia.npcs.containsKey(a[2])) { s.sendMessage("§cNPC no válido. Usa: " + String.join(", ", guia.npcs.keySet())); return true; }
                if (op.equals("borrar")) { quitarNpc(a[2]); npcData.set("npc." + a[2], null); guardar(); s.sendMessage("§aBorrado."); return true; }
                if (!(s instanceof Player)) { s.sendMessage("Solo jugadores."); return true; }
                crearNpc(a[2], ((Player) s).getLocation());
                s.sendMessage("§a" + guia.npcs.get(a[2]).nombre + " colocado aquí.");
                return true;
            }
            case "construir": {
                if (!(s instanceof Player)) { s.sendMessage("Solo jugadores."); return true; }
                mundo.construir(s, ((Player) s).getLocation());
                return true;
            }
            case "mundo": {
                if (!(s instanceof Player)) { s.sendMessage("Solo jugadores."); return true; }
                Player pa = (Player) s;
                String op = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "ir";
                if (op.equals("spawn")) { mundo.fijarSpawn(pa.getLocation()); s.sendMessage("§aSpawn del tutorial fijado aquí."); }
                else if (mundo.mundo() == null) s.sendMessage("§cEl mundo del tutorial no existe/está desactivado.");
                else { mundo.llevar(pa); s.sendMessage("§aEstás en el mundo del tutorial."); }
                return true;
            }
            case "probar": {
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player ? (Player) s : null);
                if (t == null) { s.sendMessage("/tutorial probar [jugador]"); return true; }
                if (mundo.mundo() == null) { s.sendMessage("§cEl mundo del tutorial no existe/está desactivado."); return true; }
                prog.set("cuenta." + t.getUniqueId(), null);
                prog.set("p.u" + t.getUniqueId(), null);
                est.remove("u" + t.getUniqueId());
                prueba.add(t.getUniqueId());
                gmPrevio.putIfAbsent(t.getUniqueId(), t.getGameMode());
                mundo.llevar(t);
                t.setGameMode(org.bukkit.GameMode.ADVENTURE);
                t.sendMessage(c(pfx() + "§7(Modo prueba) Busca a §eAlba§7. Al terminar harás el viaje en barco y volverás al spawn principal."));
                s.sendMessage("§aPrueba iniciada para " + t.getName() + ".");
                return true;
            }
            case "barco": {
                String op = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "lista";
                Player pa0 = s instanceof Player ? (Player) s : null;
                switch (op) {
                    case "punto": case "añadir": case "anadir":
                        if (pa0 == null) return true;
                        mundo.anadirPunto(pa0.getLocation());
                        s.sendMessage("§aPunto " + mundo.ruta().size() + " añadido donde estás (si hay agua debajo se pega a la superficie). Ponlos cada ~10-20 bloques por el agua. §7/tutorial barco ver");
                        return true;
                    case "deshacer": { int n = mundo.ruta().size(); s.sendMessage(mundo.quitarPunto(n) ? "§aQuitado el último punto (" + n + ")." : "§cNo hay puntos."); return true; }
                    case "quitar": {
                        if (a.length < 3) { s.sendMessage("§c/tutorial barco quitar <n>"); return true; }
                        s.sendMessage(mundo.quitarPunto(Integer.parseInt(a[2])) ? "§aPunto quitado." : "§cEse punto no existe."); return true;
                    }
                    case "insertar": {
                        if (pa0 == null || a.length < 3) { s.sendMessage("§c/tutorial barco insertar <n>  §7(lo mete en la posición n, donde estás)"); return true; }
                        s.sendMessage(mundo.insertarPunto(Integer.parseInt(a[2]), pa0.getLocation()) ? "§aPunto insertado." : "§cPosición no válida."); return true;
                    }
                    case "mover": {
                        if (pa0 == null || a.length < 3) { s.sendMessage("§c/tutorial barco mover <n>  §7(lleva el punto n adonde estás)"); return true; }
                        s.sendMessage(mundo.moverPunto(Integer.parseInt(a[2]), pa0.getLocation()) ? "§aPunto movido." : "§cEse punto no existe."); return true;
                    }
                    case "ir": {
                        if (pa0 == null || a.length < 3) { s.sendMessage("§c/tutorial barco ir <n>"); return true; }
                        List<Location> r = mundo.ruta(); int n = Integer.parseInt(a[2]);
                        if (n < 1 || n > r.size()) { s.sendMessage("§cEse punto no existe."); return true; }
                        pa0.teleport(r.get(n - 1).clone().add(0, 1, 0)); return true;
                    }
                    case "ver": if (pa0 != null) { mundo.verRuta(pa0, 40); s.sendMessage("§aVes la ruta 40 s: palo blanco = punto, verde = tramo."); } return true;
                    case "limpiar": mundo.limpiarRuta(); s.sendMessage("§aRuta del barco vaciada. Ahora ponla tú con §e/tutorial barco punto§a (el mapa ya no la pisa)."); return true;
                    case "probar": {
                        if (pa0 == null) return true;
                        final org.bukkit.GameMode g0 = pa0.getGameMode();
                        mundo.barco(pa0, () -> { pa0.setGameMode(g0); pa0.sendMessage("§7(Fin de la prueba del barco)"); });
                        return true;
                    }
                    default: {
                        List<Location> r = mundo.ruta();
                        s.sendMessage("§eRuta del barco: §f" + r.size() + " puntos.");
                        for (int i = 0; i < r.size(); i++) s.sendMessage("§7 " + (i + 1) + ": §f" + r.get(i).getBlockX() + ", " + r.get(i).getBlockY() + ", " + r.get(i).getBlockZ());
                        s.sendMessage("§7/tutorial barco §fpunto §7| §fdeshacer §7| §fquitar <n> §7| §finsertar <n> §7| §fmover <n> §7| §fir <n> §7| §fver §7| §flimpiar §7| §fprobar");
                        return true;
                    }
                }
            }
            case "intro": {
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player ? (Player) s : null);
                if (t == null) { s.sendMessage("/tutorial intro <jugador>"); return true; }
                lanzarIntro(t);
                s.sendMessage("§aIntro lanzada para " + t.getName() + ".");
                return true;
            }
            case "recargar": mundo.recargar(); cargarTodo(); s.sendMessage("§aRecargado: " + guia.caps.size() + " capítulos, " + guia.todos.size() + " pasos."); return true;
            case "saltar": case "reiniciar": case "ir": {
                if (a.length < 2) { s.sendMessage("/tutorial " + sub + " <jugador>" + (sub.equals("ir") ? " <capitulo|paso#>" : "")); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage("§cNo está conectado."); return true; }
                Est e = est(t);
                if (e == null) { s.sendMessage("§cNo tiene personaje activo."); return true; }
                if (sub.equals("reiniciar")) { e.i = 0; e.explicado = false; e.cont = 0; }
                else if (sub.equals("saltar")) { Guia.Paso st = paso(e); if (st != null) { e.explicado = true; completar(t, st); } }
                else {
                    if (a.length < 3) { s.sendMessage("Falta capítulo o número de paso."); return true; }
                    Guia.Paso d = guia.primerPasoDe(a[2]);
                    try { if (d == null) d = guia.todos.get(Integer.parseInt(a[2]) - 1); } catch (Throwable ex) { d = null; }
                    if (d == null) { s.sendMessage("§cNo existe."); return true; }
                    e.i = d.indice; e.explicado = false; e.cont = 0;
                }
                guardar();
                s.sendMessage("§aHecho.");
                return true;
            }
            case "estado": {
                for (Player p : Bukkit.getOnlinePlayers()) { Est e = est(p); s.sendMessage("§e" + p.getName() + " §7→ " + (e == null ? "sin personaje" : "paso " + (e.i + 1) + "/" + guia.todos.size())); }
                return true;
            }
            default:
                s.sendMessage("§6/tutorial §fconstruir · mundo [ir|spawn] · barco <punto|limpiar|probar> · probar [jugador] · intro <jugador> · npc <crear|mover|borrar|lista|reparar> [id] · recargar · saltar|reiniciar <jugador> · ir <jugador> <capitulo|#paso> · estado");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String lab, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("guia")) {
            if (a.length == 1) for (String x : new String[] { "paso", "barra", "saltar" }) if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
            return out;
        }
        if (a.length == 1) {
            for (String x : new String[] { "construir", "mundo", "barco", "probar", "intro", "npc", "recargar", "saltar", "reiniciar", "ir", "estado" }) if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        } else if (a.length == 2 && a[0].equalsIgnoreCase("barco")) {
            out.addAll(java.util.Arrays.asList("punto", "limpiar", "probar", "lista"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("mundo")) {
            out.addAll(java.util.Arrays.asList("ir", "spawn"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("npc")) {
            out.addAll(java.util.Arrays.asList("crear", "mover", "borrar", "lista", "reparar"));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("npc")) {
            out.addAll(guia.npcs.keySet());
        }
        return out;
    }
}

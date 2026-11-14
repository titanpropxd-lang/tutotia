package es.xsinex.tutorial;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Modelo de la guía: NPCs, capítulos y pasos (se lee de guia.yml). */
final class Guia {
    static final class NpcDef {
        String id, nombre, profesion, citizens;   // citizens = id de Citizens (opcional, "" si no)
    }

    static final class Paso {
        String id, titulo, npc, pista, recordatorio;
        String tipo = "NINGUNO";                  // NINGUNO, CONFIRMAR, COMANDO, ITEM, AREA, CERCA_NPC, MATAR, ROMPER, COLOCAR, USAR_BLOQUE, PERSONAJE, ESPERAR
        ConfigurationSection obj;                 // parámetros del objetivo
        List<String> dialogo = new ArrayList<>();
        List<String> recompensa = new ArrayList<>();
        Cap cap;
        int indice;                               // posición global
    }

    static final class Cap {
        String id, nombre, descripcion, icono = "BOOK", logro;
        List<Paso> pasos = new ArrayList<>();
    }

    final Map<String, NpcDef> npcs = new LinkedHashMap<>();
    final List<Cap> caps = new ArrayList<>();
    final List<Paso> todos = new ArrayList<>();

    static Guia cargar(FileConfiguration y) {
        Guia g = new Guia();
        ConfigurationSection ns = y.getConfigurationSection("npcs");
        if (ns != null) for (String k : ns.getKeys(false)) {
            NpcDef n = new NpcDef();
            n.id = k;
            n.nombre = ns.getString(k + ".nombre", k);
            n.profesion = ns.getString(k + ".profesion", "LIBRARIAN");
            n.citizens = ns.getString(k + ".citizens-id", "");
            g.npcs.put(k, n);
        }
        ConfigurationSection cs = y.getConfigurationSection("capitulos");
        if (cs != null) for (String ck : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(ck);
            if (c == null) continue;
            Cap cap = new Cap();
            cap.id = ck;
            cap.nombre = c.getString("nombre", ck);
            cap.descripcion = c.getString("descripcion", "");
            cap.icono = c.getString("icono", "BOOK");
            cap.logro = c.getString("logro", ck);
            for (Object o : c.getList("pasos", new ArrayList<>())) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                Paso p = new Paso();
                p.id = str(m.get("id"), ck + "_" + (cap.pasos.size() + 1));
                p.titulo = str(m.get("titulo"), p.id);
                p.npc = str(m.get("npc"), "");
                p.pista = str(m.get("pista"), "");
                p.recordatorio = str(m.get("recordatorio"), "");
                Object d = m.get("dialogo");
                if (d instanceof List) for (Object l : (List<?>) d) p.dialogo.add(String.valueOf(l));
                else if (d != null) p.dialogo.add(String.valueOf(d));
                Object r = m.get("recompensa");
                if (r instanceof List) for (Object l : (List<?>) r) p.recompensa.add(String.valueOf(l));
                Object ob = m.get("objetivo");
                if (ob instanceof Map) {
                    org.bukkit.configuration.MemoryConfiguration mc = new org.bukkit.configuration.MemoryConfiguration();
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) ob).entrySet()) mc.set(String.valueOf(e.getKey()), e.getValue());
                    p.obj = mc;
                    p.tipo = mc.getString("tipo", "NINGUNO").toUpperCase(Locale.ROOT);
                }
                p.cap = cap;
                cap.pasos.add(p);
                p.indice = g.todos.size();
                g.todos.add(p);
            }
            g.caps.add(cap);
        }
        return g;
    }

    static String str(Object o, String def) { return o == null ? def : String.valueOf(o); }

    Paso primerPasoDe(String capId) {
        for (Cap c : caps) if (c.id.equalsIgnoreCase(capId) && !c.pasos.isEmpty()) return c.pasos.get(0);
        return null;
    }
}

package org.bukkit.configuration.file;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/** STUB for compiling only (map-backed so tests can supply values). */
public class FileConfiguration {
    public final Map<String, Object> values = new HashMap<>();
    public String getString(String path, String def) { Object o = values.get(path); return o == null ? def : String.valueOf(o); }
    public int getInt(String path, int def) { Object o = values.get(path); return o == null ? def : ((Number) o).intValue(); }
    public double getDouble(String path, double def) { Object o = values.get(path); return o == null ? def : ((Number) o).doubleValue(); }
    public long getLong(String path, long def) { Object o = values.get(path); return o == null ? def : ((Number) o).longValue(); }
    public boolean getBoolean(String path, boolean def) { Object o = values.get(path); return o == null ? def : (Boolean) o; }
    @SuppressWarnings("unchecked")
    public List<String> getStringList(String path) { Object o = values.get(path); return o == null ? new ArrayList<>() : (List<String>) o; }
}

package org.bukkit.configuration.file;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
/** STUB for compiling only. Tests never reach it: they have no config.yml. The real class comes from Paper. */
public class YamlConfiguration implements ConfigurationSection {
    public static class Options {
        public List<String> getHeader() { throw new UnsupportedOperationException(); }
        public Options setHeader(List<String> h) { throw new UnsupportedOperationException(); }
    }
    public void load(File f) throws IOException, InvalidConfigurationException { throw new UnsupportedOperationException(); }
    public void load(Reader r) throws IOException, InvalidConfigurationException { throw new UnsupportedOperationException(); }
    public String saveToString() { throw new UnsupportedOperationException(); }
    public Options options() { throw new UnsupportedOperationException(); }
    public Set<String> getKeys(boolean deep) { throw new UnsupportedOperationException(); }
    public boolean contains(String path) { throw new UnsupportedOperationException(); }
    public boolean isConfigurationSection(String path) { throw new UnsupportedOperationException(); }
    public Object get(String path) { throw new UnsupportedOperationException(); }
    public void set(String path, Object value) { throw new UnsupportedOperationException(); }
    public ConfigurationSection createSection(String path) { throw new UnsupportedOperationException(); }
    public String getString(String path, String def) { throw new UnsupportedOperationException(); }
    public List<String> getComments(String path) { throw new UnsupportedOperationException(); }
    public void setComments(String path, List<String> c) { throw new UnsupportedOperationException(); }
    public List<String> getInlineComments(String path) { throw new UnsupportedOperationException(); }
    public void setInlineComments(String path, List<String> c) { throw new UnsupportedOperationException(); }
}

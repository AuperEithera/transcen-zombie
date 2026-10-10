package com.example.transcenmod.core;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.relauncher.IFMLCallHook;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The coremod entry point, and with it TranscendenceZombieFullPower.
 *
 * The plugin itself is described where it was; the feature added here is that a config file with a single
 * line, {@code TranscendenceZombieFullPower=true}, makes a foreign coremod's injected behaviour on the
 * classes it touches stop taking effect, without removing any of those coremods.
 *
 * It is not done by emptying the transformer list. That was the first version, and it broke a coremod:
 * one that widens a field's access in the same transform() as it injects behaviour loses both when its
 * transformer is dropped, and the mod it belongs to then fails to load with an IllegalAccessError.
 *
 * The list is reached through the private field rather than through LaunchClassLoader#getTransformers,
 * because that accessor returns Collections.unmodifiableList. That is the only reflection left here.
 *
 * ORDERING is what makes the filter work, so it is not left to the default. CoreModManager#sortTweakList
 * (CoreModManager.java:650) sorts the tweak list ascending by FMLPluginWrapper#sortIndex, and
 * CoreModManager.java:565 takes that value from this annotation, defaulting to 0. A larger value is
 * therefore later, and later is what the filter needs: it can only discard a coremod's changes if that
 * coremod has already run. Integer.MAX_VALUE puts this plugin after every coremod that uses the default
 * and after FMLDeobfTweaker, which is pinned at 1000 (CoreModManager.java:633) and is what registers
 * Forge's deobfuscation and access transformers. Those therefore sit ahead of this filter and are
 * replayed into the baseline, so their work survives; anything Forge registers still later runs on what
 * the filter returns, so its work survives too.
 */
@IFMLLoadingPlugin.SortingIndex(Integer.MAX_VALUE)
public class TranscenCorePlugin implements IFMLLoadingPlugin {

    public static final String CONFIG_FILE = "TranscendenceZombieFullPower.txt";
    public static final String CONFIG_KEY = "TranscendenceZombieFullPower";

    // Spelled out rather than taken from CoreHooks.OWN_PREFIX. That field is a compile-time constant, so
    // javac inlines it and no class name would survive either way - but this file runs in the window
    // where nothing minecraft has loaded yet, and a class load started there is a class load inside a class
    // load, which is exactly how AllReturn$Transformer killed the launch. Keeping the literal makes that
    // absence something you can read off the line.
    private static final String OWN_PREFIX = "com.example.transcenmod.";

    private static volatile boolean fullPower;

    private static volatile int lastDropped = -1;

    @Override
    public String[] getASMTransformerClass() {
        // Three transformers, in the order they have to run. TranscenTransformer is the original one and
        // keeps its two injections plus the entity guards. AllReturn.Transformer carries the AllReturn
        // rewrite that AllReturnAgent also performs from the agent side. FullPowerFilter comes last within
        // this array, and the SortingIndex on the class puts the whole array last.
        return new String[] {
                TranscenTransformer.class.getName(),
                AllReturn.Transformer.class.getName(),
                FullPowerFilter.class.getName()
        };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Nullable
    @Override
    public String getSetupClass() {
        // read by injectData, which FML runs immediately before this method is asked
        return fullPower ? Hook.class.getName() : null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // Nothing else to inject: the entity injections read their state from CoreHooks, which the mod
        // pushes into once it is constructed. The transformer only ever sees the class names it is handed.
        //
        // The one exception is TranscendenceZombieFullPower, which has to be decided here: this is the only
        // callback that runs before any minecraft class is loaded and is handed the game directory.
        try {
            Object location = data == null ? null : data.get("mcLocation");
            fullPower = readConfig(location instanceof File ? (File) location : null);
        } catch (Throwable failure) {
            // a config that cannot be read leaves the feature off, which is the safe direction
            System.err.println("[transcenmod] TranscendenceZombieFullPower config failed: " + failure);
            fullPower = false;
        }
        // Mirrored to a property because FullPowerFilter runs inside class loads and must not name another
        // class of this mod to ask. Set here, which is before any minecraft class is loaded.
        try {
            System.setProperty(FullPowerFilter.PROPERTY, fullPower ? "true" : "false");
        } catch (Throwable ignored) {
            // a security manager that refuses properties leaves the filter reading its default, off
        }
    }

    @Override
    public String getAccessTransformerClass() {
        // Returning null is deliberate and does NOT mean "no access transformer". Forge reads the AT from
        // the jar itself, driven by the manifest's FMLAT attribute; a class returned here would be a
        // second, divergent source of truth for the same file.
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // the config file: one line, TranscendenceZombieFullPower=false
    // ---------------------------------------------------------------------------------------------

    private static boolean readConfig(File gameDirectory) {
        File file = configFile(gameDirectory);
        if (file == null) {
            return false;
        }
        try {
            if (!file.isFile()) {
                writeDefault(file);
                return false;
            }
            boolean requested = false;
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), Charset.forName("UTF-8")));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int equals = trimmed.indexOf('=');
                    String key = equals < 0 ? trimmed : trimmed.substring(0, equals).trim();
                    String value = equals < 0 ? "" : trimmed.substring(equals + 1).trim();
                    if (CONFIG_KEY.equalsIgnoreCase(key)) {
                        requested = "true".equalsIgnoreCase(value);
                        break;
                    }
                }
            } finally {
                reader.close();
            }
            refreshStaleHeader(file, requested);
            return requested;
        } catch (Throwable failure) {
            System.err.println("[transcenmod] could not read " + file + ": " + failure);
        }
        return false;
    }

    private static File configFile(File gameDirectory) {
        if (gameDirectory == null) {
            return null;
        }
        return new File(new File(gameDirectory, "config"), CONFIG_FILE);
    }

    private static void writeDefault(File file) {
        write(file, false);
    }

    private static void write(File file, boolean value) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            Writer writer = new OutputStreamWriter(new FileOutputStream(file), Charset.forName("UTF-8"));
            try {
                writer.write("# to release the interception power\n");
                writer.write(CONFIG_KEY + "=" + value + "\n");
            } finally {
                writer.close();
            }
        } catch (Throwable failure) {
            System.err.println("[transcenmod] could not write " + file + ": " + failure);
        }
    }

    // An older build wrote a two line explanation above the key. The file is already on disk for anyone who
    // ran that build, and rewriting it only when it is absent would leave them reading the old header for
    // ever. So the header is refreshed in place, and the value the player set is carried over untouched.
    private static void refreshStaleHeader(File file, boolean value) {
        try {
            if (file == null || !file.isFile()) {
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), Charset.forName("UTF-8")));
            boolean stale = false;
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.indexOf("LaunchWrapper's transformer list") >= 0) {
                        stale = true;
                        break;
                    }
                }
            } finally {
                reader.close();
            }
            if (stale) {
                write(file, value);
            }
        } catch (Throwable failure) {
            System.err.println("[transcenmod] could not refresh " + file + ": " + failure);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // the purge
    // ---------------------------------------------------------------------------------------------

    public static int getLastDropped() {
        return lastDropped;
    }

    /** Reports when the config asked for it. Safe more than once: it only reads. */
    public static int purgeIfEnabled() {
        return fullPower ? purge(null) : -1;
    }

    /**
     * Counts the foreign coremod transformers and reports the whole list. Nothing is replaced any more.
     *
     * The first version swapped each foreign transformer for a no-op, and that is what broke a coremod:
     * its Minecraft#renderItem widening is inlined in the same transform() as its behaviour injection, so
     * removing the transformer removed the access widening too and the renderer that reads the widened
     * field died with an IllegalAccessError before the game reached the main menu. The list is left intact
     * and {@link FullPowerFilter} discards changes per class instead.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int purge(ClassLoader launchClassLoader) {
        try {
            Object target = launchClassLoader;
            if (target == null) {
                target = net.minecraft.launchwrapper.Launch.classLoader;
            }
            if (target == null) {
                return -1;
            }
            List transformers = transformersOf(target);
            if (transformers == null) {
                return -1;
            }

            int foreign = 0;
            for (int i = 0; i < transformers.size(); i++) {
                Object transformer = transformers.get(i);
                if (transformer != null && !keep(transformer)) {
                    foreign++;
                }
            }
            reportList(transformers);
            lastDropped = foreign;
            return foreign;
        } catch (Throwable failure) {
            System.err.println("[transcenmod] TranscendenceZombieFullPower scan failed: " + failure);
            return -1;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List transformersOf(Object launchClassLoader) {
        try {
            Field field = LaunchClassLoader.class.getDeclaredField("transformers");
            field.setAccessible(true);
            return (List) field.get(launchClassLoader);
        } catch (Throwable failure) {
            return null;
        }
    }

    // Keeping is the default, and only a coremod's own transformer counts as foreign. The first
    // version did the opposite - drop everything that is not under a Forge prefix - and that took the game
    // down as soon as a coremod was installed: Forge's own services are not all under
    // net.minecraftforge., so one bad prefix test removed something Forge cannot start without.
    //
    // A coremod reaches this list through ASMTransformerWrapper as "$wrapper.<its transformer class>", so
    // the wrapper is the only thing that can be attributed to a mod. Everything else - FML's own
    // transformers, launchwrapper's, anything this build cannot attribute - is left alone.
    private static boolean keep(Object transformer) {
        String className = transformer.getClass().getName();
        if (!className.startsWith("$wrapper.")) {
            return true;
        }
        String parent = parentOf(transformer);
        if (parent == null) {
            // cannot tell whose it is: keeping it is the choice that cannot break the game
            return true;
        }
        if (isForgeOwned(parent) || parent.startsWith(OWN_PREFIX)) {
            return true;
        }
        // Anything wrapped around a class outside Forge and this mod is a coremod layer, and that layer is
        // what the feature is for. Nothing under net.minecraftforge. reaches here, so the AccessTransformer,
        // the deobfuscator and the side stripper cannot be touched whatever their names turn out to be.
        return false;
    }

    // One shot, and only when the feature is on: the exact contents of the transformer list, so a build
    // that classifies too much or too little can be diagnosed from a log instead of from the launch
    // failure. "foreign" here means what FullPowerFilter will not replay; nothing is removed.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void reportList(List transformers) {
        try {
            for (int i = 0; i < transformers.size(); i++) {
                Object transformer = transformers.get(i);
                if (transformer == null) {
                    continue;
                }
                String className = transformer.getClass().getName();
                String parent = className.startsWith("$wrapper.") ? parentOf(transformer) : null;
                System.out.println("[transcenmod] transformer " + i + ": " + className
                        + (parent == null ? "" : " parent=" + parent)
                        + (keep(transformer) ? " (replayed)" : " (foreign)"));
            }
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    private static boolean isForgeOwned(String name) {
        String[] forgePrefixes = {
                "net.minecraftforge.", "net.minecraft.launchwrapper.", "net.minecraft.",
                "cpw.mods.", "com.mojang."
        };
        for (String prefix : forgePrefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String parentOf(Object transformer) {
        try {
            Method method = transformer.getClass().getDeclaredMethod("getParentClass");
            method.setAccessible(true);
            Object parent = method.invoke(transformer);
            if (parent instanceof String) {
                return (String) parent;
            }
        } catch (Throwable ignored) {
            // fall through to the toString form below
        }
        // TransformerWrapper#toString is "TransformerWrapper(<parent class>, <coremod name>)"
        try {
            String text = String.valueOf(transformer);
            int open = text.indexOf('(');
            int comma = text.indexOf(',', open);
            if (open >= 0 && comma > open) {
                return text.substring(open + 1, comma).trim();
            }
        } catch (Throwable ignored) {
            // nothing left to try
        }
        return null;
    }

    /**
     * The whole of TranscendenceZombieFullPower, and the last transformer this mod contributes.
     *
     * It removes nothing. Removing a coremod's transformer takes its access widening with it when the two
     * live in the same transform(), which is the shape a widening coremod has: the Minecraft#renderItem
     * widening is a visitField override in the same class as the EntityLivingBase#getHealth rewrite, so
     * dropping the transformer left the renderer that reads the widened field with an IllegalAccessError
     * and the game never started.
     *
     * So a class is recomputed as if only the trustworthy transformers had run, and the one thing drawn
     * from the real chain is the field and method access flags - widening is what an access transformer
     * does and it is what keeps a widened class loadable. Bodies, added methods and added fields all come
     * from the recomputed copy, so injected behaviour is what disappears. The rule is generic: it names no
     * coremod, and every coremod shaped like this one is treated the same way.
     *
     * Only the JDK, ASM and this class are named below. This runs inside a class load, so naming another
     * class of this mod here would be a class load inside a class load - the shape that killed the launch
     * once already. The flag is read from a system property for the same reason.
     */
    public static class FullPowerFilter implements IClassTransformer {

        private static final String PROPERTY = "transcenmod.fullpower";
        private static final String OWN_PREFIX = "com.example.transcenmod.";
        private static final String OWN_INTERNAL = "com/example/transcenmod/";

        // Packages the filter never rebuilds. The JDK's own, the libraries the transformers themselves are
        // written against, and this mod: none of them can carry an access transformer for anything this
        // filter has to rebuild, and every one of them is loaded in the first seconds of the launch, where
        // the rebuild is pure cost.
        private static final String[] SKIP = {
                "java.", "javax.", "sun.", "com.sun.", "jdk.", "org.w3c.", "org.xml.",
                "org.objectweb.asm.", "com.google.", "io.netty.", "org.apache.",
                "it.unimi.", "org.lwjgl.", "gnu.trove.", "org.slf4j.", "org.h2.",
                OWN_PREFIX
        };

        private static final String[] SKIP_INTERNAL = {
                "java/", "javax/", "sun/", "com/sun/", "jdk/", "org/w3c/", "org/xml/",
                "org/objectweb/asm/", "com/google/", "io/netty/", "org/apache/",
                "it/unimi/", "org/lwjgl/", "gnu/trove/", "org/slf4j/", "org/h2/",
                OWN_INTERNAL
        };

        private volatile List<Object> replay;
        private volatile int replayBuiltFor = -1;

        // A rebuild calls the earlier transformers, and a transformer is free to load a class while it
        // works. That class load re-enters this method on the same thread and starts a rebuild of its own,
        // which calls the same transformers again: unbounded recursion, which is what produced the
        // StackOverflowError right after TerminalTweaker, with log4j's throwable formatter recursing on top
        // of the real stack. The guard is per thread rather than a plain flag because Forge loads classes on
        // more than one thread, and a shared flag would make those loads come back unfiltered.
        private static final ThreadLocal<Boolean> INSIDE = new ThreadLocal<Boolean>();

        @Override
        public byte[] transform(String name, String transformedName, byte[] basicClass) {
            // First statement on purpose. This class is loaded by the same class loader it filters for, so
            // anything that runs before this test can run while this class is still being defined.
            if (name != null && (name.startsWith(OWN_PREFIX) || name.startsWith(OWN_INTERNAL))) {
                return basicClass;
            }
            if (basicClass == null) {
                return null;
            }
            if (Boolean.TRUE.equals(INSIDE.get())) {
                // already rebuilding further down this thread's stack: hand the class back untouched rather
                // than recurse. A class loaded from inside a rebuild is one of the transformers' own
                // dependencies, not a target of the filter.
                return basicClass;
            }
            if (!Boolean.parseBoolean(System.getProperty(PROPERTY, "false"))) {
                return basicClass;
            }
            if (isSkipped(name) || isSkipped(transformedName)) {
                return basicClass;
            }

            watching = System.getProperty("transcenmod.probe") != null
                    && (isProbeTarget(name) || isProbeTarget(transformedName));
            probe("name=" + name + " transformedName=" + transformedName
                    + " incoming=" + basicClass.length);

            // Set before chain(), not after: chain() reflects into LaunchClassLoader, and a class load out
            // of that reflection would re-enter here without the guard.
            INSIDE.set(Boolean.TRUE);
            try {
                List<Object> chain = chain();
                probe("replay chain size=" + chain.size());
                if (chain.isEmpty()) {
                    // nothing foreign is in the list, so nothing can have been injected
                    return basicClass;
                }

                byte[] raw = rawBytes(name, transformedName);
                if (raw == null) {
                    // without the untouched bytes there is nothing to compare against, and guessing would be
                    // worse than leaving the class alone
                    return basicClass;
                }

                byte[] baseline = raw;
                for (int i = 0; i < chain.size(); i++) {
                    baseline = ((IClassTransformer) chain.get(i)).transform(name, transformedName, baseline);
                    if (baseline == null) {
                        return basicClass;
                    }
                }
                return merge(baseline, basicClass);
            } catch (Throwable failure) {
                // a rebuild that fails leaves the class as every other transformer made it, which is the
                // state the game expects; throwing from here would fail the class load itself
                return basicClass;
            } finally {
                INSIDE.remove();
            }
        }

        // The transformers to replay: everything ahead of this one in LaunchClassLoader.transformers that is
        // not a coremod layer. Forge's deobfuscation and access transformers sit after this one in the
        // real list, so they are not replayed - they run on what this returns, which is why their work is
        // never discarded.
        @SuppressWarnings({"unchecked", "rawtypes"})
        private List<Object> chain() {
            List<Object> all;
            try {
                Object loader = net.minecraft.launchwrapper.Launch.classLoader;
                if (loader == null) {
                    return java.util.Collections.emptyList();
                }
                Field field = net.minecraft.launchwrapper.LaunchClassLoader.class
                        .getDeclaredField("transformers");
                field.setAccessible(true);
                all = (List<Object>) field.get(loader);
            } catch (Throwable failure) {
                return java.util.Collections.emptyList();
            }
            if (all == null) {
                return java.util.Collections.emptyList();
            }
            int size = all.size();
            if (size == replayBuiltFor && replay != null) {
                return replay;
            }

            int self = -1;
            for (int i = 0; i < size; i++) {
                if (all.get(i) == this) {
                    self = i;
                    break;
                }
            }
            int limit = self < 0 ? size : self;

            List<Object> built = new ArrayList<Object>();
            for (int i = 0; i < limit; i++) {
                Object transformer = all.get(i);
                if (transformer != null && trustworthy(transformer)) {
                    built.add(transformer);
                }
            }
            replay = built;
            replayBuiltFor = size;
            return built;
        }

        private static boolean trustworthy(Object transformer) {
            String className = transformer.getClass().getName();
            if (!className.startsWith("$wrapper.")) {
                return true;
            }
            String parent = wrapperParent(transformer);
            if (parent == null) {
                return true;
            }
            return forgeOwned(parent) || parent.startsWith(OWN_PREFIX);
        }

        // A copy of the two helpers above, deliberately. Calling the outer class's private statics from
        // here makes javac emit synthetic access$000/access$100 methods on the outer class and call them
        // from this class's bytecode, which puts another class of this mod on the class load
        // path - the shape that killed the launch with ClassCircularityError. The outer pair cannot be
        // reused without that; the copies are kept short so drift is visible, and the harness asserts this
        // file names no class of this mod.
        private static String wrapperParent(Object transformer) {
            try {
                Method method = transformer.getClass().getDeclaredMethod("getParentClass");
                method.setAccessible(true);
                Object parent = method.invoke(transformer);
                if (parent instanceof String) {
                    return (String) parent;
                }
            } catch (Throwable ignored) {
                // fall through to the toString form below
            }
            try {
                String text = String.valueOf(transformer);
                int open = text.indexOf('(');
                int comma = text.indexOf(',', open);
                if (open >= 0 && comma > open) {
                    return text.substring(open + 1, comma).trim();
                }
            } catch (Throwable ignored) {
                // nothing left to try
            }
            return null;
        }

        private static boolean forgeOwned(String name) {
            String[] prefixes = {
                    "net.minecraftforge.", "net.minecraft.launchwrapper.", "net.minecraft.",
                    "cpw.mods.", "com.mojang."
            };
            for (int i = 0; i < prefixes.length; i++) {
                if (name.startsWith(prefixes[i])) {
                    return true;
                }
            }
            return false;
        }

        // The untouched class file. The jar on disk holds the obfuscated name, so the obfuscated name is
        // tried first and the transformed one second for the development workspace, where they are equal.
        private static byte[] rawBytes(String name, String transformedName) {
            ClassLoader loader = FullPowerFilter.class.getClassLoader();
            if (loader == null) {
                return null;
            }
            byte[] bytes = read(loader, name);
            if (bytes != null) {
                probe("raw from name " + name + " -> " + bytes.length);
                return bytes;
            }
            probe("name " + name + " gave nothing");
            if (transformedName != null && !transformedName.equals(name)) {
                bytes = read(loader, transformedName);
                if (bytes != null) {
                    probe("raw from transformedName " + transformedName + " -> " + bytes.length);
                    return bytes;
                }
            }
            probe("no raw bytes for " + name + " / " + transformedName);
            return null;
        }

        // One shot, only when -Dtranscenmod.probe is set, and only for the class named here. A crash from
        // inside the server tick loop arrives with the real exception masked - CrashReportCategory throws its
        // own NPE while the report is being assembled - so the only way to see what the rebuild was handed
        // is to print it on the way past.
        private static final String[] PROBE_TARGETS = {
                "net.minecraft.world.World",
                "net.minecraft.entity.Entity",
                "net.minecraft.entity.EntityLivingBase"
        };

        private static boolean isProbeTarget(String className) {
            for (int i = 0; i < PROBE_TARGETS.length; i++) {
                if (PROBE_TARGETS[i].equals(className)) {
                    return true;
                }
            }
            return false;
        }

        private static volatile boolean watching;
        private static volatile boolean probed;

        private static void probe(String line) {
            if (!watching || probed) {
                return;
            }
            probed = true;
            System.out.println("[transcenmod] probe: " + line);
        }

        private static byte[] read(ClassLoader loader, String className) {
            if (className == null) {
                return null;
            }
            String resource = className.replace('.', '/') + ".class";
            InputStream stream = loader.getResourceAsStream(resource);
            if (stream == null) {
                return null;
            }
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) > 0) {
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            } catch (Throwable failure) {
                return null;
            } finally {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
            }
        }

        // Everything from the baseline except the access flags, and those only where the real chain widened
        // them. Taking them wholesale would let a coremod narrow something on its way past; ignoring them
        // would drop the widening and leave the widened class unable to load. Only flags change here, so
        // the frames and maxs already in the class file stay valid and ClassWriter needs no COMPUTE_* flag.
        private static byte[] merge(byte[] baseline, byte[] incoming) {
            try {
                ClassNode base = node(baseline);
                ClassNode real = node(incoming);
                if (base == null || real == null) {
                    return incoming;
                }

                Map<String, Integer> fields = new HashMap<String, Integer>();
                for (int i = 0; i < real.fields.size(); i++) {
                    FieldNode field = real.fields.get(i);
                    fields.put(field.name + field.desc, Integer.valueOf(field.access));
                }
                Map<String, Integer> methods = new HashMap<String, Integer>();
                for (int i = 0; i < real.methods.size(); i++) {
                    MethodNode method = real.methods.get(i);
                    methods.put(method.name + method.desc, Integer.valueOf(method.access));
                }

                for (int i = 0; i < base.fields.size(); i++) {
                    FieldNode field = base.fields.get(i);
                    Integer access = fields.get(field.name + field.desc);
                    if (access != null && visibility(access.intValue()) > visibility(field.access)) {
                        field.access = access.intValue();
                    }
                }
                int widenedMethods = 0;
                for (int i = 0; i < base.methods.size(); i++) {
                    MethodNode method = base.methods.get(i);
                    Integer access = methods.get(method.name + method.desc);
                    if (access != null && visibility(access.intValue()) > visibility(method.access)) {
                        method.access = access.intValue();
                        widenedMethods++;
                    }
                }

                // The counts that say whether the rebuild is doing anything sensible. onlyInReal counts the
                // methods the real chain carries that the baseline does not: those are the injections the
                // merge is dropping, which is what the feature is for. onlyInBaseline should be zero - a
                // non-zero value would mean a replayed transformer removed a method, which none of them do.
                // Methods and fields the real chain carries and the baseline does not were added by a
                // foreign coremod, and they stay. Taking the body away is what neutralises a behaviour;
                // taking the signature away breaks the code that calls it. One coremod adds a getHealth2
                // and a getMaxHealth2 next to the vanilla pair and calls them from its own code, so a merge
                // that kept only the baseline's signatures turned its health override into a
                // NoSuchMethodError thrown from inside the server tick loop - which arrives as an NPE out
                // of CrashReportCategory, because the crash report builder is what fails second.
                java.util.Set<String> baseFields = new java.util.HashSet<String>();
                for (int i = 0; i < base.fields.size(); i++) {
                    FieldNode field = base.fields.get(i);
                    baseFields.add(field.name + field.desc);
                }
                java.util.Set<String> baseMethods = methodKeys(base);
                for (int i = 0; i < real.fields.size(); i++) {
                    FieldNode field = real.fields.get(i);
                    if (!baseFields.contains(field.name + field.desc)) {
                        base.fields.add(field);
                    }
                }
                for (int i = 0; i < real.methods.size(); i++) {
                    MethodNode method = real.methods.get(i);
                    if (!baseMethods.contains(method.name + method.desc)) {
                        base.methods.add(method);
                    }
                }

                // The counts that say whether the rebuild is doing anything sensible, plus the names. A count
                // alone cannot show which method's existence was judged wrongly, and the failure being chased
                // is exactly that: a NoSuchMethodError for an inherited method means the superclass lost it.
                StringBuilder onlyInReal = new StringBuilder();
                int onlyInRealCount = 0;
                for (int i = 0; i < real.methods.size(); i++) {
                    MethodNode method = real.methods.get(i);
                    if (!baseMethods.contains(method.name + method.desc)) {
                        onlyInRealCount++;
                        if (onlyInRealCount <= 10) {
                            onlyInReal.append(' ').append(method.name);
                        }
                    }
                }
                StringBuilder onlyInBaseline = new StringBuilder();
                int onlyInBaselineCount = 0;
                for (int i = 0; i < base.methods.size(); i++) {
                    MethodNode method = base.methods.get(i);
                    if (!methods.containsKey(method.name + method.desc)) {
                        onlyInBaselineCount++;
                        if (onlyInBaselineCount <= 10) {
                            onlyInBaseline.append(' ').append(method.name);
                        }
                    }
                }
                probe("baseline methods=" + base.methods.size() + " real methods=" + real.methods.size()
                        + " widened=" + widenedMethods + " onlyInReal=" + onlyInRealCount
                        + " onlyInBaseline=" + onlyInBaselineCount);
                probe("onlyInReal names:" + onlyInReal);
                probe("onlyInBaseline names:" + onlyInBaseline);

                ClassWriter writer = new ClassWriter(0);
                base.accept(writer);
                return writer.toByteArray();
            } catch (Throwable failure) {
                return incoming;
            }
        }

        private static java.util.Set<String> methodKeys(ClassNode node) {
            java.util.Set<String> keys = new java.util.HashSet<String>();
            for (int i = 0; i < node.methods.size(); i++) {
                MethodNode method = node.methods.get(i);
                keys.add(method.name + method.desc);
            }
            return keys;
        }

        private static ClassNode node(byte[] bytes) {
            try {
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
                return node;
            } catch (Throwable failure) {
                return null;
            }
        }

        private static int visibility(int access) {
            if ((access & Opcodes.ACC_PUBLIC) != 0) {
                return 3;
            }
            if ((access & Opcodes.ACC_PROTECTED) != 0) {
                return 2;
            }
            if ((access & Opcodes.ACC_PRIVATE) != 0) {
                return 0;
            }
            return 1;
        }

        private static boolean isSkipped(String className) {
            if (className == null) {
                return true;
            }
            String[] table = className.indexOf('/') >= 0 ? SKIP_INTERNAL : SKIP;
            for (int i = 0; i < table.length; i++) {
                if (className.startsWith(table[i])) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The coremod side pass, run from IFMLCallHook#call. It reports through stdout rather than through a
     * logger because at this point in the launch the mod's own logging has not been set up.
     */
    public static class Hook implements IFMLCallHook {

        private static volatile ClassLoader launchClassLoader;

        @Override
        public void injectData(Map<String, Object> data) {
            Object loader = data == null ? null : data.get("classLoader");
            if (loader instanceof ClassLoader) {
                launchClassLoader = (ClassLoader) loader;
            }
        }

        @Override
        public Void call() {
            try {
                int foreign = purge(launchClassLoader);
                System.out.println("[transcenmod] TranscendenceZombieFullPower coremod pass: "
                        + foreign + " foreign transformer(s) will not be replayed");
            } catch (Throwable failure) {
                // best effort at this point; preInit runs the whole thing again
                System.err.println("[transcenmod] TranscendenceZombieFullPower coremod pass failed: "
                        + failure);
            }
            return null;
        }
    }
}

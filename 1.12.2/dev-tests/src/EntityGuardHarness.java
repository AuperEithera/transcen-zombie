import com.example.transcenmod.core.CoreHooks;
import com.example.transcenmod.core.TranscenTransformer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Standalone harness for TranscenTransformer, the transformer behind the entity removal and addition
 * guards.
 *
 * <p>Runs the real transformer over stand-in copies of the target classes, loads the results through a
 * fresh classloader - which is what forces the JVM verifier to accept or reject the rewritten bytecode -
 * and then actually calls the guarded methods and checks what happened.
 *
 * <p>Two name families are exercised:
 *
 * <ul>
 *   <li>the plain names, which is what a development workspace and the post-deobfuscation state look like;
 *   <li>the obfuscated names, read out of Forge's own deobfuscation_data-1.12.2.lzma, which is what a
 *       coremod transformer actually sees in production because it runs before Forge's
 *       DeobfuscationTransformer.
 * </ul>
 *
 * <p>Both matter. A table that only matches one of them compiles, builds, and silently does nothing.
 *
 * <p>Run with: gradlew verifyEntityGuard
 */
public final class EntityGuardHarness {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        File classesDir = new File(args[0]);
        File outDir = new File(args[1]);
        outDir.mkdirs();

        TranscenTransformer transformer = new TranscenTransformer();

        verifyPlainNames(transformer, classesDir, outDir);
        verifyObfuscatedNames(transformer, classesDir, outDir);
        verifyWiperFieldNames();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL ENTITY GUARD CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // EntityWiper's reflective reads
    //
    // These were wrong for several rounds and nothing noticed, because every other check here looks at
    // bytecode and this failure is in the Java half: wipe() asked WorldServer for entitiesById, which is
    // declared on World, so the lookup threw NoSuchFieldException and every removal this mod performs
    // quietly did nothing - the client copy of the entity was never detached, which is exactly the
    // symptom reported over and over. Bytecode assertions cannot see that, so the fields are resolved
    // here against the real classes, from the class that is actually passed at run time.
    // ---------------------------------------------------------------------------------------------

    private static void verifyWiperFieldNames() throws Exception {
        // (the class wipe() passes, the names it tries, what it is after)
        Class<?> world = Class.forName("net.minecraft.world.World");
        Class<?> worldServer = Class.forName("net.minecraft.world.WorldServer");
        Class<?> sections = Class.forName("net.minecraft.util.ClassInheritanceMultiMap");
        Class<?> entity = Class.forName("net.minecraft.entity.Entity");

        check("World resolves eventListeners", resolves(world, "eventListeners", "field_73021_x"));
        check("World resolves entitiesById from the class wipe passes",
                resolves(worldServer, "entitiesById", "field_175729_l"));
        check("World resolves entitiesByUuid from the class wipe passes",
                resolves(worldServer, "entitiesByUuid", "field_175741_N"));
        check("ClassInheritanceMultiMap resolves knownKeys", resolves(sections, "knownKeys", "field_180216_b"));
        check("ClassInheritanceMultiMap resolves map", resolves(sections, "map", "field_180218_a"));
        check("Entity resolves isAddedToWorld", resolves(entity, "isAddedToWorld"));

        // Positive control. If entitiesById were declared on WorldServer, the walk would not be needed and
        // the checks above would pass for the broken version too. It is declared on World, so asking the
        // exact class - which is what the old lookup effectively did - has to fail. Without this the whole
        // method could be asserting nothing.
        check("entitiesById is really NOT declared on WorldServer (so the walk is load-bearing)",
                !declaredExactly(worldServer, "entitiesById"));
    }

    private static boolean declaredExactly(Class<?> owner, String name) {
        try {
            owner.getDeclaredField(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The lookup EntityWiper uses: either name, walking up from the class it was handed.
     *
     * A field declared on a superclass has to be found from the subclass, because that is what the caller
     * passes - and in the reobfuscated game the name is the SRG one, so both have to be tried.
     */
    private static boolean resolves(Class<?> from, String... names) {
        for (Class<?> current = from; current != null; current = current.getSuperclass()) {
            for (String name : names) {
                try {
                    current.getDeclaredField(name);
                    return true;
                } catch (Throwable ignored) {
                    // next candidate
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // The development / post-deobfuscation naming
    // ---------------------------------------------------------------------------------------------

    private static void verifyPlainNames(TranscenTransformer transformer, File classesDir, File outDir)
            throws Exception {
        System.out.println("== plain names (development workspace and post-deobfuscation) ==");
        rewrite(transformer, classesDir, outDir, "net.minecraft.world.World", "net.minecraft.world.World");
        rewrite(transformer, classesDir, outDir, "net.minecraft.world.chunk.Chunk", "net.minecraft.world.chunk.Chunk");
        rewrite(transformer, classesDir, outDir, "net.minecraft.util.ClassInheritanceMultiMap",
                "net.minecraft.util.ClassInheritanceMultiMap");

        Loader loader = new Loader(outDir, classesDir);
        Class<?> entityClass = loader.loadClass("net.minecraft.entity.Entity");
        Class<?> bannedClass = loader.loadClass("net.minecraft.entity.EntityZombie");
        Class<?> zombieClass = loader.loadClass("com.example.transcenmod.entity.TranscenZombie");
        Class<?> worldClass = loader.loadClass("net.minecraft.world.World");
        Class<?> chunkClass = loader.loadClass("net.minecraft.world.chunk.Chunk");
        Class<?> mapClass = loader.loadClass("net.minecraft.util.ClassInheritanceMultiMap");

        CoreHooks.setBanPredicate(null);
        CoreHooks.setProtectionEnabled(true);

        // --- A. nothing banned, nothing protected: the vanilla behaviour has to survive the rewrite ---
        Object world = newInstance(worldClass);
        List<Object> loaded = listField(worldClass, world, "loadedEntityList");
        Object chunk = worldClass.getField("chunk").get(world);

        Object plain = newInstance(entityClass);
        check("a plain entity still spawns",
                Boolean.TRUE.equals(worldClass.getMethod("spawnEntity", entityClass).invoke(world, plain))
                        && loaded.contains(plain));
        worldClass.getMethod("removeEntity", entityClass).invoke(world, plain);
        check("a plain entity is still removable", isDead(entityClass, plain) && !loaded.contains(plain));

        check("spawnEntity still returns true for a plain entity",
                Boolean.TRUE.equals(worldClass.getMethod("spawnEntity", entityClass).invoke(world, plain)));

        // --- B. the protected entity, on a world of its own so no earlier call can mask a failure ---
        world = newInstance(worldClass);
        loaded = listField(worldClass, world, "loadedEntityList");
        chunk = worldClass.getField("chunk").get(world);
        Object zombie = newInstance(zombieClass);

        worldClass.getMethod("spawnEntity", entityClass).invoke(world, zombie);
        check("the protected entity itself still spawns", loaded.contains(zombie));
        check("the protected entity reached its chunk",
                Boolean.TRUE.equals(chunkClass.getMethod("holds", entityClass).invoke(chunk, zombie)));

        worldClass.getMethod("removeEntity", entityClass).invoke(world, zombie);
        check("removeEntity is refused for the protected entity",
                !isDead(entityClass, zombie) && loaded.contains(zombie));

        worldClass.getMethod("removeEntityDangerously", entityClass).invoke(world, zombie);
        check("removeEntityDangerously is refused for the protected entity",
                !isDead(entityClass, zombie) && loaded.contains(zombie));
        check("the chunk still holds the protected entity",
                Boolean.TRUE.equals(chunkClass.getMethod("holds", entityClass).invoke(chunk, zombie)));

        chunkClass.getMethod("removeEntity", entityClass).invoke(chunk, zombie);
        chunkClass.getMethod("removeEntityAtIndex", entityClass, int.class).invoke(chunk, zombie, 0);
        check("Chunk.removeEntity and removeEntityAtIndex are refused for the protected entity",
                Boolean.TRUE.equals(chunkClass.getMethod("holds", entityClass).invoke(chunk, zombie)));

        check("ClassInheritanceMultiMap.remove is refused for the protected entity",
                Boolean.FALSE.equals(mapClass.getMethod("remove", Object.class).invoke(section(mapClass, zombie), zombie)));

        worldClass.getMethod("unloadEntities", Collection.class)
                .invoke(world, java.util.Collections.singletonList(zombie));
        // unloadEntities only queues; what matters is that nothing downstream can act on the queue. The
        // queue is drained by updateEntities itself, so it is empty again at the end of the pass.
        worldClass.getMethod("updateEntities").invoke(world);
        check("unloadEntities + updateEntities cannot drop the protected entity",
                loaded.contains(zombie)
                        && chunkHolds(chunkClass, chunk, entityClass, zombie)
                        && listField(worldClass, world, "unloadedEntityList").isEmpty());

        // --- the updateEntities dead branch: how a foreign setDead reaches the list ---
        entityClass.getField("isDead").setBoolean(zombie, true);
        worldClass.getMethod("updateEntities").invoke(world);
        check("the dead branch does not take the protected entity out of loadedEntityList",
                loaded.contains(zombie));
        check("the dead branch does not take the protected entity out of its chunk",
                Boolean.TRUE.equals(chunkClass.getMethod("holds", entityClass).invoke(chunk, zombie)));
        check("the dead branch cleared the flag it was refused on (otherwise the loop never ends)",
                !isDead(entityClass, zombie));

        // --- C. the ban list, again on a fresh world ---
        Set<Class<?>> ban = new HashSet<Class<?>>();
        CoreHooks.setBanPredicate(new FixedBan(ban));
        ban.add(bannedClass);

        world = newInstance(worldClass);
        loaded = listField(worldClass, world, "loadedEntityList");
        chunk = worldClass.getField("chunk").get(world);
        Object banned = newInstance(bannedClass);

        check("spawnEntity refuses a banned class",
                Boolean.FALSE.equals(worldClass.getMethod("spawnEntity", entityClass).invoke(world, banned))
                        && !loaded.contains(banned) && !chunkHolds(chunkClass, chunk, entityClass, banned));
        worldClass.getMethod("joinEntityInSurroundings", entityClass).invoke(world, banned);
        check("joinEntityInSurroundings refuses a banned class", !loaded.contains(banned));
        check("addWeatherEffect refuses a banned class",
                Boolean.FALSE.equals(worldClass.getMethod("addWeatherEffect", entityClass).invoke(world, banned))
                        && !loaded.contains(banned));
        worldClass.getMethod("onEntityAdded", entityClass).invoke(world, banned);
        check("onEntityAdded does not throw for a banned class", true);
        chunkClass.getMethod("addEntity", entityClass).invoke(chunk, banned);
        check("Chunk.addEntity refuses a banned class",
                Boolean.FALSE.equals(chunkClass.getMethod("holds", entityClass).invoke(chunk, banned)));
        check("ClassInheritanceMultiMap.add refuses a banned class",
                Boolean.FALSE.equals(mapClass.getMethod("add", Object.class).invoke(newInstance(mapClass), banned)));
        worldClass.getMethod("loadEntities", Collection.class)
                .invoke(world, java.util.Collections.singletonList(banned));
        check("loadEntities filters a banned class out of the collection", !loaded.contains(banned));

        // --- and the ban must never be able to ban this mod's own entity ---
        ban.add(zombieClass);
        check("a Transcendence Zombie still spawns even with its own class banned",
                Boolean.TRUE.equals(worldClass.getMethod("spawnEntity", entityClass).invoke(world, zombie)));
        check("the restricted class is still refused alongside it",
                Boolean.FALSE.equals(worldClass.getMethod("spawnEntity", entityClass).invoke(world, banned)));
        check("CoreHooks.isOwnType answers for the mod's own package",
                CoreHooks.isOwnType(zombieClass) && !CoreHooks.isOwnType(bannedClass));

        // --- D. protection off is what lets the mod's own wipe through ---
        CoreHooks.setBanPredicate(null);
        world = newInstance(worldClass);
        loaded = listField(worldClass, world, "loadedEntityList");
        Object wipeMe = newInstance(zombieClass);
        worldClass.getMethod("spawnEntity", entityClass).invoke(world, wipeMe);

        CoreHooks.setProtectionEnabled(false);
        worldClass.getMethod("removeEntityDangerously", entityClass).invoke(world, wipeMe);
        check("with protection off the protected entity is removable again (the wipe path)",
                isDead(entityClass, wipeMe) && !loaded.contains(wipeMe));
        CoreHooks.setProtectionEnabled(true);
    }

    private static Object section(Class<?> mapClass, Object value) throws Exception {
        Object section = newInstance(mapClass);
        mapClass.getMethod("add", Object.class).invoke(section, value);
        return section;
    }

    private static boolean chunkHolds(Class<?> chunkClass, Object chunk, Class<?> entityClass, Object entity)
            throws Exception {
        return Boolean.TRUE.equals(chunkClass.getMethod("holds", entityClass).invoke(chunk, entity));
    }

    // ---------------------------------------------------------------------------------------------
    // The obfuscated naming, which is what production actually looks like
    // ---------------------------------------------------------------------------------------------

    private static void verifyObfuscatedNames(TranscenTransformer transformer, File classesDir, File outDir)
            throws Exception {
        System.out.println();
        System.out.println("== obfuscated names (production, before Forge deobfuscates) ==");
        File lzmaData = findDeobfuscationData();
        if (lzmaData == null) {
            System.out.println("skip deobfuscation_data-1.12.2.lzma is not on the classpath");

            return;
        }
        if (!installRemapper(lzmaData)) {
            System.out.println("skip FMLDeobfuscatingRemapper could not be loaded");
            return;
        }
        System.out.println("   remapper loaded from " + lzmaData);
        // three separate checks, because these are the three things the table depends on and a combined
        // one only says "something is wrong"
        check("the remapper unmaps an SRG class name to its obfuscated one",
                "amu".equals(unmap("net/minecraft/world/World")), String.valueOf(unmap("net/minecraft/world/World")));
        check("the remapper maps an obfuscated member to its SRG name",
                "func_72900_e".equals(mapMethodName("amu", "e", "(Lvg;)V")),
                String.valueOf(mapMethodName("amu", "e", "(Lvg;)V")));
        check("the remapper maps an obfuscated descriptor to its SRG form",
                "(Lnet/minecraft/entity/Entity;)V".equals(mapMethodDesc("(Lvg;)V")),
                String.valueOf(mapMethodDesc("(Lvg;)V")));

        rewrite(transformer, classesDir, outDir, "amu", "net.minecraft.world.World");
        rewrite(transformer, classesDir, outDir, "axw", "net.minecraft.world.chunk.Chunk");
        rewrite(transformer, classesDir, outDir, "qx", "net.minecraft.util.ClassInheritanceMultiMap");

        Loader loader = new Loader(outDir, classesDir);
        Class<?> entityClass = loader.loadClass("vg");
        Class<?> worldClass = loader.loadClass("amu");
        Class<?> chunkClass = loader.loadClass("axw");
        Class<?> mapClass = loader.loadClass("qx");

        Object world = newInstance(worldClass);
        Object chunk = worldClass.getField("chunk").get(world);
        Object section = newInstance(mapClass);
        List<Object> loaded = listField(worldClass, world, "loadedEntityList");
        Set<Class<?>> ban = new HashSet<Class<?>>();
        CoreHooks.setBanPredicate(new FixedBan(ban));

        Object entity = newInstance(entityClass);
        check("an unbanned vg still spawns through the obfuscated entry point",
                Boolean.TRUE.equals(worldClass.getMethod("a", entityClass).invoke(world, entity))
                        && loaded.contains(entity));

        // a fresh instance for everything after the ban: the one above is legitimately in the world already,
        // and reusing it would make every check below report the earlier successful add
        ban.add(entityClass);
        Object banned = newInstance(entityClass);
        check("amu.a(Lvg;)Z (spawnEntity) refuses once vg is banned",
                Boolean.FALSE.equals(worldClass.getMethod("a", entityClass).invoke(world, banned)));
        check("amu.d(Lvg;)Z (addWeatherEffect) refuses once vg is banned",
                Boolean.FALSE.equals(worldClass.getMethod("d", entityClass).invoke(world, banned)));
        worldClass.getMethod("i", entityClass).invoke(world, banned);
        check("amu.i(Lvg;)V (joinEntityInSurroundings) refuses once vg is banned", !loaded.contains(banned));
        chunkClass.getMethod("a", entityClass).invoke(chunk, banned);
        check("axw.a(Lvg;)V (addEntity) refuses once vg is banned",
                Boolean.FALSE.equals(chunkClass.getMethod("holds", entityClass).invoke(chunk, banned)));
        check("qx.add(Object) refuses once vg is banned",
                Boolean.FALSE.equals(mapClass.getMethod("add", Object.class).invoke(section, banned)));
        worldClass.getMethod("a", Collection.class).invoke(world, java.util.Collections.singletonList(banned));
        check("amu.a(Collection) (loadEntities) filters a banned class out", !loaded.contains(banned));

        CoreHooks.setBanPredicate(null);
        CoreHooks.setProtectionEnabled(true);
    }

    // ---------------------------------------------------------------------------------------------
    // plumbing
    // ---------------------------------------------------------------------------------------------

    private static void rewrite(TranscenTransformer transformer, File classesDir, File outDir,
                                String internalName, String transformedName) throws Exception {
        String path = internalName.replace('.', '/');
        byte[] original = Files.readAllBytes(new File(classesDir, path + ".class").toPath());
        byte[] transformed = transformer.transform(internalName.replace('/', '.'),
                transformedName, original);
        check(internalName + " (" + transformedName + ") bytecode changed", changed(original, transformed),
                "identical output - the name/descriptor match found nothing");
        System.out.println("   " + internalName + ": " + original.length + " -> " + transformed.length);
        File target = new File(outDir, path + ".class");
        target.getParentFile().mkdirs();
        OutputStream out = new FileOutputStream(target);
        try {
            out.write(transformed);
        } finally {
            out.close();
        }
    }

    private static boolean changed(byte[] before, byte[] after) {
        if (before.length != after.length) {
            return true;
        }
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                return true;
            }
        }
        return false;
    }

    private static Object newInstance(Class<?> type) throws Exception {
        return type.getDeclaredConstructor().newInstance();
    }

    private static boolean isDead(Class<?> entityClass, Object entity) throws Exception {
        return entityClass.getField("isDead").getBoolean(entity);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listField(Class<?> owner, Object instance, String name) throws Exception {
        Field field = owner.getField(name);
        return (List<Object>) field.get(instance);
    }

    private static void check(String label, boolean ok) {
        check(label, ok, "false");
    }

    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            System.out.println("ok   " + label);
        } else {
            System.out.println("FAIL " + label + " (" + detail + ")");
            failures++;
        }
    }

    /** The live predicate the mod installs over TranscenMod.BANNED_CLASSES. */
    private static final class FixedBan implements java.util.function.Predicate<Class<?>> {
        private final Set<Class<?>> banned;

        FixedBan(Set<Class<?>> banned) {
            this.banned = banned;
        }

        @Override
        public boolean test(Class<?> type) {
            return this.banned.contains(type);
        }
    }

    private static File findDeobfuscationData() {
        String[] entries = System.getProperty("java.class.path", "").split(File.pathSeparator);
        for (String entry : entries) {
            if (!entry.endsWith(".jar")) {
                continue;
            }
            File jar = new File(entry);
            if (!jar.isFile()) {
                continue;
            }
            ZipFile zip = null;
            try {
                zip = new ZipFile(jar);
                ZipEntry found = zip.getEntry("deobfuscation_data-1.12.2.lzma");
                if (found == null) {
                    continue;
                }
                File target = File.createTempFile("transcenmod-deobf", ".lzma");
                target.deleteOnExit();
                InputStream in = zip.getInputStream(found);
                try {
                    OutputStream out = new FileOutputStream(target);
                    try {
                        byte[] chunk = new byte[8192];
                        int read;
                        while ((read = in.read(chunk)) > 0) {
                            out.write(chunk, 0, read);
                        }
                    } finally {
                        out.close();
                    }
                } finally {
                    in.close();
                }
                return target;
            } catch (IOException ignored) {
                // not a readable zip, or no such entry
            } finally {
                if (zip != null) {
                    try {
                        zip.close();
                    } catch (IOException ignored) {
                        // nothing useful to do
                    }
                }
            }
        }
        return null;
    }

    private static boolean installRemapper(File data) {
        try {
            Class<?> remapper = Class.forName(
                    "net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper");
            Object instance = remapper.getField("INSTANCE").get(null);
            // setupLoadOnly parses the field lines too, and those go through ClassPatchManager, which needs
            // the remapper's classloader to be set. A loader with no sources answers "not found" for
            // everything, which is all this needs.
            java.lang.reflect.Field loaderField = remapper.getDeclaredField("classLoader");
            loaderField.setAccessible(true);
            if (loaderField.get(instance) == null) {
                loaderField.set(instance, new net.minecraft.launchwrapper.LaunchClassLoader(new java.net.URL[0]));
            }
            remapper.getMethod("setupLoadOnly", String.class, boolean.class)
                    .invoke(instance, data.getAbsolutePath(), Boolean.TRUE);
            // setupLoadOnly fills rawMethodMaps; the per-class method map is normally built by
            // FMLDeobfuscatingRemapper#mergeSuperMaps, which returns early when it cannot read the class's
            // superclass off the launch classloader. The stand-ins are not the real obfuscated classes, so
            // the merge is driven directly here - with the same raw maps the data file just produced.
            for (String owner : new String[] { "amu", "axw", "qx" }) {
                remapper.getMethod("mergeSuperMaps", String.class, String.class, String[].class)
                        .invoke(instance, owner, "java/lang/Object", new String[0]);
            }
            return true;
        } catch (Throwable failure) {
            System.out.println("   remapper setup failed: " + failure);
            if (failure.getCause() != null) {
                failure.getCause().printStackTrace(System.out);
            }
            return false;
        }
    }

    private static Object remapper() throws Exception {
        return Class.forName("net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper")
                .getField("INSTANCE").get(null);
    }

    private static String unmap(String name) throws Exception {
        return (String) remapper().getClass().getMethod("unmap", String.class).invoke(remapper(), name);
    }

    private static String mapMethodName(String owner, String name, String desc) throws Exception {
        Object instance = remapper();
        Method method = instance.getClass().getMethod("mapMethodName", String.class, String.class, String.class);
        return (String) method.invoke(instance, owner, name, desc);
    }

    private static String mapMethodDesc(String desc) throws Exception {
        Object instance = remapper();
        Method method = instance.getClass().getMethod("mapMethodDesc", String.class);
        return (String) method.invoke(instance, desc);
    }

    /**
     * Loads the rewritten classes first, so the JVM verifier has to accept exactly the bytecode the
     * transformer produced. The stand-ins are also on the application classpath, and default parent-first
     * delegation would hand back the untransformed copies - the test would then pass while proving nothing.
     */
    private static final class Loader extends ClassLoader {
        private final File[] roots;

        Loader(File... roots) {
            super(Loader.class.getClassLoader());
            this.roots = roots;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    for (File root : roots) {
                        File file = new File(root, name.replace('.', '/') + ".class");
                        if (!file.isFile()) {
                            continue;
                        }
                        try {
                            byte[] bytes = Files.readAllBytes(file.toPath());
                            found = defineClass(name, bytes, 0, bytes.length);
                            break;
                        } catch (Exception failure) {
                            throw new ClassNotFoundException(name, failure);
                        }
                    }
                    if (found == null) {
                        return super.loadClass(name, resolve);
                    }
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }
    }
}


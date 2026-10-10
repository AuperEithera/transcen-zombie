package com.example.transcenmod.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A single static flag, and the thing that is switched on when the player uses the item while sneaking.
 *
 * The class is kept tiny on purpose: the injected guard does one GETSTATIC on it per method. The pieces the
 * attached agent needs live in AllReturnAgent, a separate file only because the JVM has to load those out of
 * a generated jar, where nothing else from this mod exists.
 *
 * TWO READINGS OF THE SAME FLAG, ON PURPOSE.
 *
 *  - the guard injected into a target class reads AllReturn.allReturn with GETSTATIC. That read happens at
 *    run time, long after everything is loaded, so it is safe.
 *  - Transformer#transform reads System.getProperty instead. It may not touch a class of this mod at all:
 *    it runs inside LaunchClassLoader#runTransformers, i.e. in the middle of defining some class, and any
 *    class load it starts is a class load inside a class load.
 *
 * The second point is not theoretical. The first version read AllReturn.allReturn here and the game died on
 * the very first class Forge loaded:
 *
 *     java.lang.ClassCircularityError: com/example/transcenmod/core/AllReturn
 *         at com.example.transcenmod.core.AllReturn$Transformer.transform(AllReturn.java:58)
 *
 *     loading Loader -> transform -> needs AllReturn -> defining AllReturn -> transform -> needs AllReturn
 *
 * So Transformer#transform below names nothing but the JDK, ASM and launchwrapper's own interface, and it
 * carries its own copy of the rewrite instead of calling AllReturnAgent.rewrite for the same reason:
 * sharing that method would put a second class of this mod on the transform chain.
 */
public final class AllReturn {

    /** Read by the guard inserted into every method of every non-exempt class. */
    public static volatile boolean allReturn;

    /** Mirrored here so Transformer, which may not touch a class of this mod, can still see the flag. */
    public static final String PROPERTY = "transcenmod.allReturn";

    private AllReturn() {}

    public static boolean isActive() {
        return allReturn;
    }

    public static void set(boolean active) {
        allReturn = active;
        try {
            System.setProperty(PROPERTY, Boolean.toString(active));
        } catch (Throwable failure) {
            // a security manager refusing the property still leaves the in-process flag correct
        }
        // the mirror may only ever record off: a persisted on is what made the next launch start with
        // every mod's methods already zeroed, which is a state the game cannot even construct mods in
        if (!active) {
            writeMirror(false);
        }
        if (active) {
            // best effort and asynchronous: attaching needs tools.jar, which a JRE does not have, and the
            // transformer below covers that case
            AllReturnAgent.loadAsync();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The way out of the switch that does not go through the game at all.
    //
    // Every in-game gesture is a path through code that a coremod is free to have replaced, and that is
    // exactly how this feature became impossible to switch off: Entity#rayTrace had been replaced by a
    // delegate into a foreign class, the guard zeroed it, the mouse-over came back null, and neither mouse
    // button did anything any more. The value is therefore mirrored to a file next to the config, and our
    // own client tick reads it back. Editing that file needs no input, no screen and no renderer.
    // ---------------------------------------------------------------------------------------------

    private static volatile File mirror;
    private static volatile long mirrorStamp = Long.MIN_VALUE;

    /** Called once, with the directory Forge hands every mod for its configuration. */
    public static void watch(File configDirectory) {
        try {
            mirror = new File(configDirectory, "TranscendenceAllReturn.txt");
        } catch (Throwable failure) {
            mirror = null;
        }
    }

    private static void writeMirror(boolean active) {
        File file = mirror;
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                mirror = null;
                return;
            }
            Files.write(file.toPath(), (active ? "true" : "false").getBytes(StandardCharsets.UTF_8));
            mirrorStamp = file.lastModified();
        } catch (Throwable failure) {
            // a directory that cannot be written is not worth an exception on every single toggle
            mirror = null;
        }
    }

    /** Reads the file back. Called from the client tick, and it does nothing unless the file changed. */
    public static void followFile() {
        File file = mirror;
        if (file == null) {
            return;
        }
        try {
            if (!file.isFile()) {
                writeMirror(allReturn);
                return;
            }
            long stamp = file.lastModified();
            if (stamp == mirrorStamp) {
                return;
            }
            mirrorStamp = stamp;
            // one way only: this file can turn the feature off and can never turn it on
            if (allReturn && !Boolean.parseBoolean(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim())) {
                set(false);
            }
        } catch (Throwable failure) {
            // unreadable leaves the switch where it is, which is what the running game is already using
        }
    }

    /**
     * The coremod half of the feature.
     *
     * AllReturnAgent carries the same rewrite, but attaching it needs com.sun.tools.attach from tools.jar -
     * present on a JDK, absent on a JRE - so the work is also done here, in the transformer stack this mod
     * already owns. The guard inserted is identical and is inert until allReturn is set.
     */
    public static class Transformer implements IClassTransformer {

        private static final String FLAG_OWNER = "com/example/transcenmod/core/AllReturn";
        private static final String FLAG_NAME = "allReturn";
        private static final String FLAG_DESC = "Z";
        private static final String OWN_PREFIX = "com/example/transcenmod/";

        // Not a literal "everything except minecraft, forge, this mod and java". log4j, guava, fastutil and
        // ASM are what the transformer stack, the logger and the crash reporter are built from, and returning
        // null from LogManager.getLogger does not degrade the game, it ends it. Mod jars from elsewhere are
        // NOT in this list and are still rewritten, which is the point of the feature.
        //
        // This list is the second question, not the first: what a class may be guarded at all is decided by
        // where its bytes come from (isForeignJarClass below), which is what the fourth line of this list was
        // trying to say and could not, because "net/minecraft/" is a name and the name changes.
        private static final String[] EXCLUDED = {
                "java/", "javax/", "jdk/", "sun/", "com/sun/", "org/w3c/", "org/xml/",
                "net/minecraft/", "net/minecraftforge/", "com/mojang/",
                "com/example/transcenmod/",
                "org/objectweb/asm/", "org/ow2/", "cpw/mods/",
                "org/apache/", "com/google/", "it/unimi/", "io/netty/",
                "org/lwjgl/", "org/newdawn/", "paulscode/", "scala/", "org/jline/", "com/typesafe/",
                "lzma/", "java3d/", "net/sf/", "org/scala-lang/"
        };

        // Never guarded, whatever the owner and whatever the source. These are the methods whose value the
        // world reads to decide whether something is alive, and a coremod is free to have replaced the body
        // with a delegate into its own class: an EntityLivingBase#getHealth that landed on a coremod method
        // made every entity in the game, the player included, report zero health - the death screen, with
        // the guard never having touched a vanilla class. Excluding by source cannot see that shape, so it
        // is excluded by name instead.
        // Each entry is the mcp name and the srg name, because the same method is either one depending on
        // whether the class was deobfuscated before this transformer ran.
        private static final String[] NEVER = {
                "getHealth", "func_110143_aJ",
                "getMaxHealth", "func_110138_aP",
                "onUpdate", "func_70071_h_", "func_189213_a",
                "onLivingUpdate", "func_70636_d",
                "onDeath", "func_70645_a",
                "updateEntities", "func_72939_s",
                "updateEntity", "func_72870_g",
                // aiming: the ray trace is how the player says what he is pointing at, and launcher-side
                // mouse handling reads it every frame. it returns an object, so a default is null.
                "rayTrace", "func_174822_a"
        };

        // A class that is a renderer or a model: whatever its methods do is the picture, and the picture is
        // what has to survive. This is a category and not a list of mods, so a mod nobody has heard of is
        // covered as well: every renderer and model class of one extends or implements one of these types,
        // whatever it is called. Zeroing doRender is what turned the mobs into nothing at all.
        //
        // The class rule is the load-bearing half. Inside a renderer the member names are srg names in
        // production - doRender ships as func_76986_a - so a test on the method name alone would never
        // match there. The name list below only catches the shapes that are not a whole class.
        private static final String[] PICTURE_PACKAGES = {
                "/renderer/", "/model/", "/client/render", "/client/model"
        };

        private static final String[] PICTURE_TYPES = { "Render", "Model", "Layer" };

        private static final String[] PICTURE_METHODS = {
                "doRender", "func_76986_a", "render", "renderName", "renderNameTag", "getEntityTexture",
                "func_110775_a", "getTexture", "getRenderType", "shouldRender", "preRenderCallback",
                "getColorMultiplier", "setRotationAngles", "func_78087_a", "setLivingAnimations",
                "applyRotations", "rotateCorpse", "doRenderLayer", "renderTileEntityAt", "getSwingProgress"
        };

        // The members a coremod leaves behind that the game's own code calls directly. A coremod replaces a
        // vanilla body with a delegate into its own class, so from then on the game's code is what consumes
        // the return value, and zeroing the delegate breaks the game rather than the coremod.
        // Entity#rayTrace became a call on a class of its own; the guard zeroed it, the mouse-over came back
        // null and no button in the game did anything any more - which also took the way to switch this
        // feature off with it.
        //
        // The shape is visible in the bytecode, so it is read from there instead of being listed: any member
        // of a foreign class that a class outside the mods calls is never guarded. One pass over the game's
        // own classes, which are the only ones that carry injected delegates, and the answer then covers
        // every coremod rather than the ones that were known when this was written.
        private static final Set<String> CALLED_FROM_GAME = ConcurrentHashMap.newKeySet();
        private static final Map<String, Boolean> MOD_CLASS = new ConcurrentHashMap<String, Boolean>();

        private static boolean isGameNamespace(String internalName) {
            return internalName.startsWith("net/minecraft/") || internalName.startsWith("net/minecraftforge/")
                    || internalName.startsWith("paulscode/") || internalName.startsWith("com/jcraft/");
        }

        private static void noteVanillaFacing(String internalName, byte[] basicClass) {
            try {
                new ClassReader(basicClass).accept(new ClassVisitor(Opcodes.ASM5) {
                    @Override
                    public MethodVisitor visitMethod(int access, String methodName, String desc,
                                                     String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM5) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String callName,
                                                        String callDesc, boolean isInterface) {
                                if (isModClass(owner)) {
                                    CALLED_FROM_GAME.add(owner + "." + callName + callDesc);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (Throwable failure) {
                // a class that cannot be read records nothing; the name list is what covers the rest
            }
        }

        private static boolean isModClass(String internalName) {
            if (internalName == null) {
                return false;
            }
            // the cheap prefix list first: almost every call target is the jdk, the game or a library
            if (isExcluded(internalName)) {
                return false;
            }
            Boolean known = MOD_CLASS.get(internalName);
            if (known != null) {
                return known.booleanValue();
            }
            boolean foreign = isForeignJarClass(internalName);
            MOD_CLASS.put(internalName, Boolean.valueOf(foreign));
            return foreign;
        }

        private static boolean isPicture(String internalName) {
            if (internalName == null) {
                return false;
            }
            String lower = internalName.toLowerCase(Locale.ENGLISH);
            for (String part : PICTURE_PACKAGES) {
                if (lower.contains(part)) {
                    return true;
                }
            }
            int slash = internalName.lastIndexOf('/');
            String simple = slash < 0 ? internalName : internalName.substring(slash + 1);
            for (String type : PICTURE_TYPES) {
                if (simple.startsWith(type)) {
                    return true;
                }
            }
            return simple.endsWith("Renderer");
        }

        @Override
        public byte[] transform(String name, String transformedName, byte[] basicClass) {
            if (basicClass == null) {
                return null;
            }
            // string comparison only, and it comes first: for this mod's own classes nothing further in
            // this method runs, so no constant pool entry on that path is ever resolved
            String internal = transformedName != null
                    ? transformedName.replace('.', '/')
                    : (name == null ? null : name.replace('.', '/'));
            if (internal == null || internal.startsWith(OWN_PREFIX)) {
                return basicClass;
            }
            // A class loaded while the switch is already on is left alone. The guard is injected while
            // the switch is off so that flipping it reaches everything already loaded; injecting it into
            // the classes that load afterwards is what froze the game, because by then everything left to
            // load - renderers, gui, sound, chunks - is something the running game still needs.
            if (Boolean.parseBoolean(System.getProperty(PROPERTY, "false"))) {
                return basicClass;
            }
            if (isExcluded(internal)) {
                return basicClass;
            }
            if (!isForeignJarClass(internal)) {
                return basicClass;
            }
            // No "is the flag on yet" test here, and that test is what made this feature do nothing.
            //
            // Waiting for the flag meant only classes that first loaded AFTER the player pressed the key
            // got a guard, and by then almost every class that could have been guarded is already loaded -
            // so there was nothing left to guard. The flag cannot be read here anyway: the class is being
            // loaded long before anyone can press a key.
            //
            // So the guard goes in unconditionally, from the first class of the session. It is inert
            // until the flag flips - it is one GETSTATIC on a boolean - and once it does flip, every
            // class loaded since startup is already carrying it and turns off in the same instant.
            // That is the whole mechanism, and it needs no agent, no retransformation and no JDK.
            try {
                byte[] rewritten = rewrite(internal, basicClass);
                return rewritten == null ? basicClass : rewritten;
            } catch (Throwable failure) {
                // a transformer that throws fails the class load and with it the game. leaving the class
                // alone degrades to "this one class was not neutralised".
                return basicClass;
            }
        }

        static boolean isExcluded(String internalName) {
            for (String prefix : EXCLUDED) {
                if (internalName.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        private static final Map<String, Boolean> SOURCE_VERDICTS = new ConcurrentHashMap<String, Boolean>();

        /**
         * True when the class's bytes come out of a jar that is not this mod's.
         *
         * The question is asked of the loader that is loading the class, with the same resource name it
         * uses, so the answer is where the bytes came from and not what the class is called. java,
         * minecraft, forge, the libraries the game ships and this mod are all somewhere else on the
         * classpath and are let through. Anything that cannot be named - the jdk's own classes, a directory
         * on the classpath, a resource the loader does not report - is let through as well: missing one
         * leaves it working, and guarding one class too many is what took the game down.
         */
        public static boolean isForeignJarClass(String internalName) {
            try {
                ClassLoader loader = net.minecraft.launchwrapper.Launch.classLoader;
                if (loader == null) {
                    return false;
                }
                String path = internalName + ".class";
                // findResource is the loader's own search, which is what found the bytes
                URL resource = loader instanceof URLClassLoader
                        ? ((URLClassLoader) loader).findResource(path)
                        : null;
                if (resource == null) {
                    // the parent loader supplies this one: the jdk, or something on the app classpath
                    resource = loader.getResource(path);
                }
                return resource != null && isModJar(resource.toString());
            } catch (Throwable failure) {
                return false;
            }
        }

        private static boolean isModJar(String resource) {
            String text = resource.replace('\\', '/');
            int separator = text.indexOf('!');
            String jar = (separator < 0 ? text : text.substring(0, separator)).toLowerCase(Locale.ENGLISH);
            if (!jar.endsWith(".jar")) {
                // a directory on the classpath: the development workspace, or the jdk
                return false;
            }
            Boolean known = SOURCE_VERDICTS.get(jar);
            if (known != null) {
                return known.booleanValue();
            }
            // a mod lives in a mods folder. that is the whole test, and it is a physical one
            boolean foreign = jar.contains("/mods/") || jar.contains("/coremods/");
            if (jar.contains("transcenmod")) {
                foreign = false;
            }
            SOURCE_VERDICTS.put(jar, Boolean.valueOf(foreign));
            return foreign;
        }

        /**
         * Inserts {@code if (AllReturn.allReturn) return <default>;} at the head of every method.
         *
         * Deliberately a second copy of AllReturnAgent#rewrite - see the class comment.
         */
        static byte[] rewrite(String internalName, byte[] basicClass) {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            // flag 0, not EXPAND_FRAMES: the existing stack map frames stay compressed, which is what the
            // F_SAME this adds is. Reading with EXPAND_FRAMES turns them all into full frames, and the two
            // forms do not mix - ASM then writes a stack map table whose offsets run past the code.
            reader.accept(node, 0);

            boolean picture = isPicture(node.superName) || isPicture(node.name);
            for (String each : node.interfaces) {
                picture = picture || isPicture(each);
            }

            boolean changed = false;
            List<MethodNode> methods = new ArrayList<MethodNode>(node.methods);
            for (MethodNode method : methods) {
                if (guard(internalName, method, node.version, picture)) {
                    changed = true;
                }
            }
            if (!changed) {
                return null;
            }

            // COMPUTE_MAXS, not COMPUTE_FRAMES: the existing frames are kept and only shifted, so ASM never
            // has to resolve a supertype through a classloader that is busy loading this very class.
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        }

        private static boolean guard(String owner, MethodNode method, int version, boolean picture) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return false;
            }
            // the picture first: a renderer or a model is data for the screen, and the screen has to work
            if (picture) {
                return false;
            }
            for (String only : PICTURE_METHODS) {
                if (only.equals(method.name)) {
                    return false;
                }
            }
            // a member the game's own code calls: the game consumes its value, and a default is not a value
            if (CALLED_FROM_GAME.contains(owner + "." + method.name + method.desc)) {
                return false;
            }
            if (method.instructions == null || method.instructions.size() == 0) {
                return false;
            }
            // a constructor cannot return before the super call, and a class initialiser returning early
            // would leave the static fields null
            if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) {
                return false;
            }
            for (String never : NEVER) {
                if (never.equals(method.name)) {
                    return false;
                }
            }
            // Idempotent, and it has to be: one class can pass through this twice, because the LaunchWrapper
            // chain and the Instrumentation transformer the agent installs both see it. A second guard on
            // top of the first is unreachable code the class verifier rejects. The test is the guard's own
            // first real instruction - labels and frames have no opcode and are skipped.
            for (AbstractInsnNode cursor = method.instructions.getFirst(); cursor != null;
                    cursor = cursor.getNext()) {
                if (cursor.getOpcode() < 0) {
                    continue;
                }
                if (cursor instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) cursor;
                    if (field.getOpcode() == Opcodes.GETSTATIC
                            && FLAG_OWNER.equals(field.owner) && FLAG_NAME.equals(field.name)) {
                        return false;
                    }
                }
                break;
            }

            // Objects and arrays are never given a default. null is not a value any caller can use, and the
            // classes that hand values out - models, textures, gui data, ActionResult - are exactly the ones
            // the running game still needs while the switch is on; zeroing them is what froze the game the
            // moment the switch was flipped. Primitives below are still zeroed, which is where the
            // behaviour that has to be neutralised actually lives.
            int sort = Type.getReturnType(method.desc).getSort();
            if (sort == Type.OBJECT || sort == Type.ARRAY) {
                return false;
            }
            Type returnType = Type.getReturnType(method.desc);
            InsnList injected = new InsnList();
            // a label, the flag test, the default return, the join point, a frame
            injected.add(new LabelNode());
            injected.add(new FieldInsnNode(Opcodes.GETSTATIC, FLAG_OWNER, FLAG_NAME, FLAG_DESC));
            LabelNode join = new LabelNode();
            injected.add(new JumpInsnNode(Opcodes.IFEQ, join));

            switch (returnType.getSort()) {
                case Type.VOID:
                    injected.add(new InsnNode(Opcodes.RETURN));
                    break;
                case Type.BOOLEAN:
                case Type.CHAR:
                case Type.BYTE:
                case Type.SHORT:
                case Type.INT:
                    injected.add(new InsnNode(Opcodes.ICONST_0));
                    injected.add(new InsnNode(Opcodes.IRETURN));
                    break;
                case Type.FLOAT:
                    injected.add(new InsnNode(Opcodes.FCONST_0));
                    injected.add(new InsnNode(Opcodes.FRETURN));
                    break;
                case Type.LONG:
                    injected.add(new InsnNode(Opcodes.LCONST_0));
                    injected.add(new InsnNode(Opcodes.LRETURN));
                    break;
                case Type.DOUBLE:
                    injected.add(new InsnNode(Opcodes.DCONST_0));
                    injected.add(new InsnNode(Opcodes.DRETURN));
                    break;
                case Type.ARRAY:
                case Type.OBJECT:
                    injected.add(new InsnNode(Opcodes.ACONST_NULL));
                    injected.add(new InsnNode(Opcodes.ARETURN));
                    break;
                default:
                    // Type.METHOD never reaches a class file as a return type
                    return false;
            }

            injected.add(join);
            // The join arrives with the method's own entry locals and an empty stack, which is what a
            // previous frame of "the same" describes - but only for a class file that has a stack map table
            // to describe it in. Below version 50 there is none and the verifier infers the frames, so
            // asking ASM to invent one produces a table ASM itself cannot read back: com.jcraft.jogg.Page
            // (version 49, out of codecjorbis) came out that way, and the next transformer to parse the
            // class - forge's TerminalTransformer, which parses everything - died with an
            // ArrayIndexOutOfBoundsException from ClassReader#readFrameType. LaunchClassLoader turns that
            // into a ClassNotFoundException, which is how the ogg codec and with it all the music vanished,
            // with the guard switched off. Thirty three of the thirty three classes in that jar were
            // rewritten into that shape; the check below is what makes them readable again.
            if ((version & 0xFFFF) >= Opcodes.V1_6) {
                injected.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            }

            method.instructions.insert(injected);
            return true;
        }
    }
}

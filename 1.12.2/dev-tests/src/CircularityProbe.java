import com.example.transcenmod.core.AllReturn;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * A deliberately broken transformer, used only as the positive control for the circularity test in
 * {@code verifyAllReturn}.
 *
 * <p>It reproduces the one mistake that killed the launch: reading a class of this mod from inside
 * {@code transform()}. That method runs inside the class loader's transformer loop, so the read starts a
 * class load while the loader is already busy defining a class, and if the class it asks for is the one
 * being defined the JVM throws ClassCircularityError.
 *
 * <p>If the circularity test ever stops throwing when this is registered, the test has lost its teeth and
 * the real transformer's result means nothing.
 */
public class CircularityProbe implements IClassTransformer {

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (AllReturn.allReturn) {
            return basicClass;
        }
        return basicClass;
    }
}

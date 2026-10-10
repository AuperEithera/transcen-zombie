/**
 * Minimal stand-in for "vg", the obfuscated name of net.minecraft.entity.Entity in the 1.12.2 client.
 *
 * The real pairs were read out of Forge's own deobfuscation_data-1.12.2.lzma, which is the mapping
 * Forge's DeobfuscationTransformer applies at run time:
 *
 *   MD: amu/e (Lvg;)V net/minecraft/world/World/func_72900_e (Lnet/minecraft/entity/Entity;)V
 *
 * A coremod transformer runs before that deobfuscation step, so these are the names it actually sees in
 * production. Building the stand-ins under them is what lets the verifier prove the remapped match
 * works, rather than only the development-workspace one.
 */
public class vg {

    public boolean isDead;

    public void X() {
        this.isDead = true;
    }
}

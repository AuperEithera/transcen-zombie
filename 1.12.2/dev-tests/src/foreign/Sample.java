package foreign;

/**
 * Stand-in for a foreign class, the thing the AllReturn rewrite is aimed at. It is outside every
 * excluded prefix, so AllReturnAgent#rewrite has to rewrite it.
 *
 * The return types are all different on purpose: the rewrite picks ICONST_0/FCONST_0/LCONST_0/DCONST_0/
 * ACONST_NULL and a matching return opcode per sort, and getting one wrong is a VerifyError rather than a
 * wrong value. intMethod and longMethod also carry a branch and a two-slot local so the shifted stack map
 * frames are actually exercised - a method body with no frames would not catch a broken frame table.
 */
public class Sample {

    public static int calls = 0;

    public Sample() {
        // <init> must never be guarded: returning before the super call does not verify
    }

    public void voidMethod() {
        calls++;
    }

    public int intMethod() {
        calls++;
        return calls > 0 ? 7 : 8;
    }

    public long longMethod(long seed) {
        calls++;
        return seed + 9L;
    }

    public double doubleMethod() {
        calls++;
        return 1.5D;
    }

    public float floatMethod() {
        calls++;
        return 2.5F;
    }

    public boolean boolMethod() {
        calls++;
        return true;
    }

    public String objectMethod() {
        calls++;
        return "original";
    }

    public static int staticMethod() {
        calls++;
        return 3;
    }
}

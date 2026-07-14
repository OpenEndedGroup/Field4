package fieldfdpass;

// Deliberately outside the afield.* Kotlin multiplatform module and compiled as a standalone jar
// (see native/field-fdpass/CMakeLists.txt), loaded at runtime via a fresh URLClassLoader from
// afield.four.texture.UnixFdPass — never placed on Field4's normal jvmMain classpath.
//
// Why: a class declaring a JNI `native` method, if referenced (even just loaded, not called)
// from any code path Field4's box/REPL execution touches, breaks that execution with a confusing
// NoClassDefFoundError("ReplState") — see the "field4-jni-repl-bug" memory note for the full
// isolation writeup. Loading this class through an isolated classloader that Field's own
// classpath scanning never walks avoids the interaction entirely.
public class UnixFdPassNative {
    // HotSpot scopes a loaded native library's symbols to the classloader that was active when
    // System.load() ran, and resolves a `native` method's implementation only against libraries
    // registered under ITS OWN declaring class's classloader — an isolation feature, not a bug,
    // so two classloaders can each load the same-named class against different .so builds without
    // colliding. Calling System.load() from Kotlin (the app's main classloader) while this class
    // loads via a separate URLClassLoader binds the library to the *wrong* bucket and every call
    // fails with UnsatisfiedLinkError even though the symbol is right there in the process. Fix:
    // load it from a static method *on this class*, so the load runs under this class's own
    // classloader — call this once, immediately after Class.forName, before touching sendFds.
    public static void loadLibrary(String path) {
        System.load(path);
    }

    public static native boolean sendFds(String socketPath, int[] fds);
}

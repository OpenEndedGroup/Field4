// Cross-platform conformance test for the ken_ceres_jvm C ABI.
//
// This is deliberately a single file with no dependencies beyond the JDK, run with Java's
// single-file source launcher:
//
//     java --enable-native-access=ALL-UNNAMED tools/ceres_abi/CeresAbiConformance.java <library>
//
// Why not `./gradlew smokeCeres`? Because that needs jvmMain's runtime classpath, which means
// compiling ~470k lines of Kotlin against a snapshot compiler fork before a single native symbol is
// touched. What varies between macOS, Linux and Windows is the *native build and its ABI*, not
// Field's Kotlin, so this harness tests exactly that and nothing else. It runs in seconds on a cold
// CI runner where the Gradle path takes tens of minutes, and it needs no LFS content.
//
// The rules this file follows, because they are what makes it portable:
//
//   * Assert invariants, never coordinates. Most of these programs leave a gauge freedom (a
//     rigid motion the constraints do not fix), so the individual point positions Ceres lands on are
//     allowed to differ between platforms and Ceres versions. The distance between two points, or
//     the coplanarity of four, is not.
//   * Look every export up explicitly. On Windows a DLL exports nothing unless it was told to, so
//     "the symbol is present" is a real test there even though it is trivially true elsewhere.
//   * Encode programs by hand from the opcode table in ken_ceres.h. That duplicates a little of what
//     the Kotlin encoder does, on purpose: the duplicate is the ABI contract restated independently,
//     so a change to one side that forgets the other fails here.

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CeresAbiConformance {

    // ---------------------------------------------------------------- opcodes (see ken_ceres.h)

    private static final double PLANAR = 0;
    private static final double DISTANCE = 2;
    private static final double LOCK_Z = 22;
    private static final double LOCK_XY = 23;
    private static final double EXPR = 68;
    private static final double VARIABLE = 69;

    // expr:: register-machine ops
    private static final double E_CONST = 0;
    private static final double E_SUB = 3;
    private static final double E_VAR = 20;

    /** The ABI this harness is written against. A mismatch is a real failure, not a warning. */
    private static final String EXPECTED_ABI = "ken_ceres_jvm_abi_v3";

    /** Every symbol the JVM side binds. Absent ones are the classic Windows dllexport failure. */
    private static final String[] EXPORTS = {
        "ken_ceres_jvm_abi_version",
        "ken_ceres_solver_create",
        "ken_ceres_solver_destroy",
        "ken_ceres_solver_last_error",
        "ken_ceres_solver_variables",
        "ken_ceres_solver_solve",
        "ken_ceres_solver_solve2",
        "solve_constraints",
        "solve_constraints_ex",
        "solve_constraints_metrics",
    };

    private static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

    private final Linker linker = Linker.nativeLinker();
    private final SymbolLookup lookup;
    private final Arena arena;

    private final MethodHandle abiVersion;
    private final MethodHandle create;
    private final MethodHandle destroy;
    private final MethodHandle lastError;
    private final MethodHandle variables;
    private final MethodHandle solve;
    private final MethodHandle solve2;
    private final MethodHandle solveConstraints;

    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> notes = new LinkedHashMap<>();
    private int passed;

    CeresAbiConformance(Path library, Arena arena) {
        this.arena = arena;
        this.lookup = SymbolLookup.libraryLookup(library.toAbsolutePath(), arena);

        abiVersion = bind("ken_ceres_jvm_abi_version", FunctionDescriptor.of(ValueLayout.ADDRESS));
        create = bind("ken_ceres_solver_create", FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, I, ValueLayout.ADDRESS, I, I));
        destroy = bind("ken_ceres_solver_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        lastError = bind("ken_ceres_solver_last_error", FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        variables = bind("ken_ceres_solver_variables", FunctionDescriptor.of(
            I, ValueLayout.ADDRESS, ValueLayout.ADDRESS, I));
        solve = bind("ken_ceres_solver_solve", FunctionDescriptor.of(
            I, ValueLayout.ADDRESS, ValueLayout.ADDRESS, I, I, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, I, ValueLayout.ADDRESS));
        solve2 = bind("ken_ceres_solver_solve2", FunctionDescriptor.of(
            I, ValueLayout.ADDRESS, ValueLayout.ADDRESS, I, ValueLayout.ADDRESS, I,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, I,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, I));
        solveConstraints = bind("solve_constraints", FunctionDescriptor.of(
            I, ValueLayout.ADDRESS, I, ValueLayout.ADDRESS, I, I, I, ValueLayout.ADDRESS));
    }

    private MethodHandle bind(String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError(
            "the library does not export " + name
                + " — on Windows that usually means KEN_CERES_API/dllexport is missing"));
        return linker.downcallHandle(symbol, descriptor);
    }

    // ---------------------------------------------------------------- tiny test framework

    private interface Case { void run() throws Throwable; }

    private void check(String name, Case body) {
        try {
            body.run();
            passed++;
            System.out.printf("  pass  %s%s%n", name, notes.containsKey(name) ? "  (" + notes.get(name) + ")" : "");
        } catch (Throwable t) {
            failures.add(name + ": " + t);
            System.out.printf("  FAIL  %s%n          %s%n", name, t);
        }
    }

    private void note(String name, String note) {
        notes.put(name, note);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void near(double actual, double expected, double tolerance, String what) {
        if (Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(String.format(
                "%s: expected %.9f +/- %g, got %.9f (off by %g)",
                what, expected, tolerance, actual, Math.abs(actual - expected)));
        }
    }

    // ---------------------------------------------------------------- helpers

    private MemorySegment doubles(double... values) {
        MemorySegment segment = arena.allocate(D, values.length);
        for (int i = 0; i < values.length; i++) segment.setAtIndex(D, i, values[i]);
        return segment;
    }

    private MemorySegment doubleBuffer(int count) {
        return arena.allocate(D, Math.max(1, count));
    }

    private static double[] read(MemorySegment segment, int count) {
        double[] out = new double[count];
        for (int i = 0; i < count; i++) out[i] = segment.getAtIndex(D, i);
        return out;
    }

    private static String cString(MemorySegment address) {
        if (address == null || address.equals(MemorySegment.NULL)) return "";
        return address.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static double distance(double[] points, int a, int b) {
        double dx = points[3 * a] - points[3 * b];
        double dy = points[3 * a + 1] - points[3 * b + 1];
        double dz = points[3 * a + 2] - points[3 * b + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Six times the signed volume of the tetrahedron on four points — zero exactly when they are
     * coplanar. This is the gauge-invariant way to ask "did PLANAR do its job", as opposed to
     * comparing coordinates against a golden file that a different Ceres version would fail.
     */
    private static double tetraVolume(double[] p, int a, int b, int c, int d) {
        double[] u = {p[3 * b] - p[3 * a], p[3 * b + 1] - p[3 * a + 1], p[3 * b + 2] - p[3 * a + 2]};
        double[] v = {p[3 * c] - p[3 * a], p[3 * c + 1] - p[3 * a + 1], p[3 * c + 2] - p[3 * a + 2]};
        double[] w = {p[3 * d] - p[3 * a], p[3 * d + 1] - p[3 * a + 1], p[3 * d + 2] - p[3 * a + 2]};
        return u[0] * (v[1] * w[2] - v[2] * w[1])
            - u[1] * (v[0] * w[2] - v[2] * w[0])
            + u[2] * (v[0] * w[1] - v[1] * w[0]);
    }

    /**
     * Three points pinned to their build-time pose, and a fourth that starts half a unit off their
     * plane with nothing but PLANAR acting on it. Used by two cases.
     *
     * The obvious fixture — PLANAR against a PRIOR_3 holding the original pose — is wrong, and
     * instructively so: the two terms are both soft, so Ceres correctly converges to a weighted
     * compromise that is not coplanar, and the test then reads as a solver failure when it is a
     * badly posed problem. Locking three points removes the compromise: the plane is fixed, the
     * free point has one place to be, and where it lands *within* the plane is a gauge freedom the
     * volume assertion does not care about.
     */
    private double[] planarFixture() {
        return new double[] {
            LOCK_XY, 3, 0, 1, 2, 1e4,
            LOCK_Z, 3, 0, 1, 2, 1e4,
            PLANAR, 4, 0, 1, 2, 3, 1.0,
        };
    }

    /** Build a handle, or fail with whatever the native side says went wrong. */
    private MemorySegment build(MemorySegment points, int pointCount, double[] program, int hash) throws Throwable {
        MemorySegment handle = (MemorySegment) create.invokeExact(
            points, pointCount, doubles(program), program.length, hash);
        expect(!handle.equals(MemorySegment.NULL), "ken_ceres_solver_create returned NULL");
        String error = cString((MemorySegment) lastError.invokeExact(handle));
        expect(error.isEmpty(), "the program did not build: " + error);
        return handle;
    }

    private double[] solveOnce(MemorySegment handle, MemorySegment pointsIn, int pointCount, int maxIters)
        throws Throwable {
        MemorySegment out = doubleBuffer(3 * pointCount);
        int rc = (int) solve.invokeExact(
            handle, pointsIn, pointCount, maxIters,
            MemorySegment.NULL, out, MemorySegment.NULL, MemorySegment.NULL, 0, MemorySegment.NULL);
        expect(rc >= 0, "ken_ceres_solver_solve returned " + rc);
        return read(out, 3 * pointCount);
    }

    // ---------------------------------------------------------------- the cases

    void runAll() {
        check("every export is present", () -> {
            List<String> missing = new ArrayList<>();
            for (String name : EXPORTS) if (lookup.find(name).isEmpty()) missing.add(name);
            expect(missing.isEmpty(), "missing exports: " + missing);
            note("every export is present", EXPORTS.length + " symbols");
        });

        check("ABI version is " + EXPECTED_ABI, () -> {
            String actual = cString((MemorySegment) abiVersion.invokeExact());
            expect(EXPECTED_ABI.equals(actual), "the library reports " + actual);
        });

        check("DISTANCE reaches its target length", () -> {
            MemorySegment points = doubles(0, 0, 0, 1, 0, 0);
            double[] program = {DISTANCE, 2.0, 0, 1, 1.0};
            MemorySegment handle = build(points, 2, program, 0x1001);
            try {
                double[] solved = solveOnce(handle, points, 2, 200);
                near(distance(solved, 0, 1), 2.0, 1e-6, "|p0 p1| after solve");
            } finally {
                destroy.invokeExact(handle);
            }
        });

        check("LOCK holds a point at its build-time pose", () -> {
            MemorySegment points = doubles(0, 0, 0, 1, 0, 0);
            double[] program = {
                LOCK_XY, 1, 0, 1e4,
                LOCK_Z, 1, 0, 1e4,
                DISTANCE, 2.0, 0, 1, 1.0,
            };
            MemorySegment handle = build(points, 2, program, 0x1002);
            try {
                double[] solved = solveOnce(handle, points, 2, 200);
                near(distance(solved, 0, 1), 2.0, 1e-5, "|p0 p1| after solve");
                near(solved[0], 0.0, 1e-3, "locked p0.x");
                near(solved[1], 0.0, 1e-3, "locked p0.y");
                near(solved[2], 0.0, 1e-3, "locked p0.z");
            } finally {
                destroy.invokeExact(handle);
            }
        });

        check("PLANAR makes four points coplanar", () -> {
            // The fourth point starts half a unit off the plane of the first three.
            MemorySegment points = doubles(0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0.5);
            MemorySegment handle = build(points, 4, planarFixture(), 0x1003);
            try {
                double[] solved = solveOnce(handle, points, 4, 400);
                double before = Math.abs(tetraVolume(read(points, 12), 0, 1, 2, 3));
                double after = Math.abs(tetraVolume(solved, 0, 1, 2, 3));
                expect(before > 0.1, "the fixture should start non-coplanar, volume was " + before);
                near(after, 0.0, 1e-6, "tetrahedron volume after solve");
                note("PLANAR makes four points coplanar",
                    String.format("6V %.3f -> %.2e", before, after));
            } finally {
                destroy.invokeExact(handle);
            }
        });

        check("a malformed program is reported, not silently truncated", () -> {
            MemorySegment points = doubles(0, 0, 0, 1, 0, 0);
            // 999 is not an opcode. The wrapper's contract is to say so rather than solve a
            // smaller problem and report success.
            MemorySegment handle = (MemorySegment) create.invokeExact(
                points, 2, doubles(DISTANCE, 2.0, 0, 1, 1.0, 999.0), 6, 0x1004);
            expect(!handle.equals(MemorySegment.NULL), "create returned NULL");
            try {
                String error = cString((MemorySegment) lastError.invokeExact(handle));
                expect(!error.isEmpty(), "last_error was empty for a program with a bad opcode");
                expect(error.contains("999"), "the error should name the offending opcode: " + error);
                note("a malformed program is reported, not silently truncated", error);
            } finally {
                destroy.invokeExact(handle);
            }
        });

        check("SPARSE_NORMAL_CHOLESKY is actually available", () -> {
            // The reason this test exists: when Ceres is built without a sparse backend it does not
            // refuse a sparse solve, it silently substitutes a dense one. That is invisible from the
            // outside except as a performance and conditioning difference, and it is exactly the kind
            // of thing that differs between a Homebrew Ceres, an apt Ceres and our pinned static
            // build. summary_out[11] is the solver Ceres actually used, so ask it.
            MemorySegment points = doubles(0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0.5);
            MemorySegment handle = build(points, 4, planarFixture(), 0x1005);
            try {
                MemorySegment out = doubleBuffer(12);
                MemorySegment summary = doubleBuffer(12);
                // options: [0] max_iterations, [1] linear solver (2 = SPARSE_NORMAL_CHOLESKY)
                MemorySegment options = doubles(400, 2);
                int rc = (int) solve2.invokeExact(
                    handle, points, 4, options, 2, MemorySegment.NULL, out,
                    MemorySegment.NULL, MemorySegment.NULL, 0, MemorySegment.NULL, summary, 12);
                expect(rc >= 0, "ken_ceres_solver_solve2 returned " + rc);
                double[] s = read(summary, 12);
                int used = (int) s[11];
                expect(used == 2, "asked for SPARSE_NORMAL_CHOLESKY (2) and Ceres used " + used
                    + " — this build has no sparse linear algebra backend, so sparse solves are"
                    + " silently falling back to a dense one");
                near(Math.abs(tetraVolume(read(out, 12), 0, 1, 2, 3)), 0.0, 1e-6,
                    "tetrahedron volume after a sparse solve");
                note("SPARSE_NORMAL_CHOLESKY is actually available",
                    String.format("%d iterations, final cost %.3e", (int) s[4], s[1]));
            } finally {
                destroy.invokeExact(handle);
            }
        });

        check("EXPR and VARIABLE solve for a free variable", () -> {
            // The newest and most template-heavy path in the wrapper: a caller-supplied residual
            // evaluated on ceres::Jet through DynamicAutoDiffCostFunction. If any platform's
            // compiler is going to disagree with this codebase, it will be here.
            //
            // One free scalar v, initialised to 0, with residual (v - 3). The minimum is v = 3.
            MemorySegment points = doubles(0, 0, 0);
            double[] program = {
                VARIABLE, 1, 0, 0.0, 0,          // dim 1, manifold NONE, init 0, no bounds
                EXPR,
                0,                                // no point blocks
                1, 0,                             // one free variable: index 0
                1, 2,                             // one residual, read from slot 2
                3,                                // three slots
                1, 3.0,                           // one constant: 3.0
                3,                                // three instructions
                E_VAR, 0, 0, 0,                   // slot0 = v[0]
                E_CONST, 1, 0, 0,                 // slot1 = 3.0
                E_SUB, 2, 0, 1,                   // slot2 = slot0 - slot1
                1.0,                              // weight
            };
            MemorySegment handle = build(points, 1, program, 0x1006);
            try {
                int total = (int) variables.invokeExact(handle, MemorySegment.NULL, 0);
                expect(total == 1, "expected one free-variable component, got " + total);

                solveOnce(handle, points, 1, 200);

                MemorySegment values = doubleBuffer(1);
                int written = (int) variables.invokeExact(handle, values, 1);
                expect(written == 1, "variables reported " + written + " components");
                near(values.getAtIndex(D, 0), 3.0, 1e-6, "solved free variable");
            } finally {
                destroy.invokeExact(handle);
            }
        });

        check("the non-handle solve_constraints entry point agrees", () -> {
            // Kept because it is a separate export with a separate signature; on Windows a missing
            // dllexport on just this one would not show up anywhere else.
            MemorySegment points = doubles(0, 0, 0, 1, 0, 0);
            MemorySegment out = doubleBuffer(6);
            double[] program = {DISTANCE, 2.0, 0, 1, 1.0};
            int rc = (int) solveConstraints.invokeExact(
                points, 2, doubles(program), program.length, 0x1007, 200, out);
            expect(rc >= 0, "solve_constraints returned " + rc);
            near(distance(read(out, 6), 0, 1), 2.0, 1e-6, "|p0 p1| after solve");
        });
    }

    // ---------------------------------------------------------------- entry point

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: CeresAbiConformance <path to ken_ceres_jvm library>");
            System.exit(2);
        }
        Path library = Path.of(args[0]);
        if (!Files.isRegularFile(library)) {
            System.err.println("no library at " + library.toAbsolutePath());
            System.exit(2);
        }

        System.out.printf("ken_ceres_jvm ABI conformance%n");
        System.out.printf("  platform      %s %s%n",
            System.getProperty("os.name"), System.getProperty("os.arch"));
        System.out.printf("  java          %s%n", System.getProperty("java.version"));
        System.out.printf("  library       %s (%d bytes)%n",
            library.toAbsolutePath(), Files.size(library));
        System.out.println();

        int failed;
        try (Arena arena = Arena.ofConfined()) {
            CeresAbiConformance harness = new CeresAbiConformance(library, arena);
            harness.runAll();
            failed = harness.failures.size();
            System.out.println();
            System.out.printf("%d passed, %d failed%n", harness.passed, failed);
            if (failed > 0) {
                System.out.println();
                System.out.println("failures:");
                for (String failure : harness.failures) System.out.println("  " + failure);
            }
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}

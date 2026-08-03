// solver_kernel.cpp
// Integrated 3D constraint solver for Emscripten/Ceres
// - See exported C API at bottom.

#include <ceres/ceres.h>
#if defined(KEN_CERES_HAVE_GLOG)
#include <glog/logging.h>
#endif
#include <ceres/loss_function.h>
#include <ceres/sphere_manifold.h>
#include <Eigen/Dense>
#include <cstdint>
#include <vector>
#include <memory>
#include <cmath>
#include <limits>
#include <string>
#include <algorithm>

// ============================================================
// Opcodes (must match your JS helpers)
// ============================================================
namespace op
{
    enum : int
    {
        PLANAR = 0,
        ON_LINE = 1,
        DISTANCE = 2,
        SAME_X = 3,
        SAME_Y = 4,
        SAME_Z = 5,
        SAME_XY = 6,
        SAME_XZ = 7,
        SAME_YZ = 8,
        PERP_AT_ORIGIN = 9,

        // Locks
        LOCK_X = 20,
        LOCK_Y = 21,
        LOCK_Z = 22,
        LOCK_XY = 23,
        LOCK_XZ = 24,
        LOCK_YZ = 25,

        // Priors (install residuals once, weights dynamic per-solve)
        PRIOR_3 = 30,
        PRIOR_X = 31,
        PRIOR_Y = 32,
        PRIOR_Z = 33,

        // Dist variants
        MIN_DISTANCE = 34,   // [Dmin, i, j, w]
        EQUAL_DISTANCE = 35, // [a,b,c,d,w]
        MAX_DISTANCE = 36,   // [Dmax, i, j, w]

        // Inequality
        NO_Z_NEG = 37, // [n, idx..., w] (or [-1], w)

        // JVM/native extensions
        DOT_AT_ORIGIN = 40,       // [o,a,b,target,normalized,w]
        VECTOR_DOT = 41,          // [a0,a1,b0,b1,target,normalized,w]
        POINT_ON_PLANE = 42,      // [p,a,b,c,offset,w] signed distance to plane ABC
        LINE_PARALLEL = 43,       // [a0,a1,b0,b1,w]
        LINE_ANGLE = 44,          // [a0,a1,b0,b1,target_cos,w]
        DIHEDRAL_ANGLE = 45,      // [a,b,c,d,target_cos,w] normals ABC and ABD
        EQUAL_EDGE_VECTOR = 46,   // [a,b,c,d,w] (b-a) == (d-c)
        MIRROR_ACROSS_PLANE = 47, // [p,q,a,b,c,w] P mirrors Q across plane ABC
        PANEL_AREA = 48,          // [a,b,c,d,target_area,w] area(ABC)+area(ACD)
        POINT_IN_HALFSPACE = 49,  // [p,a,b,c,min_signed_distance,w]
        LINE_DIRECTION = 50,      // [a,b,dx,dy,dz,w]
        PLANE_NORMAL_DIRECTION = 51, // [a,b,c,nx,ny,nz,target_cos,w]
        LINE_TOWARD_POINT = 52,   // [a,b,vp,w]

        // Solver-directed pseudo-ops and global (all-point) constraints. Added for Field's
        // polyhedron realizer, but nothing about them is polyhedron-specific.
        SET_LOSS = 60,              // [kind, scale]  kind 0=none(L2) 1=Huber 2=SoftLOne 3=Cauchy
        EDGE_TANGENCY = 61,         // [a, b, radius, w]  |closest point of line AB to origin| - r
        TANGENCY_CENTROID = 62,     // [nEdges, a0,b0, a1,b1, ..., w]  mean tangency point == origin
        CENTROID = 63,              // [n, i0..., tx,ty,tz, w]  mean of listed points == target
        RADIUS_ABOUT_CENTROID = 64, // [n, i0..., radius, w]  |p_i - mean| == radius

        // Two better-conditioned alternatives to PLANAR, and a rotation gauge. See the notes on
        // PlanarHesse and PlanarFit below for why PLANAR alone is not enough.
        PLANAR_HESSE = 65,     // [n, i0..., w]  plane as unit normal + scalar offset
        PLANAR_FIT = 66,       // [n, i0..., w]  best-fit plane inside the residual, no plane vars
        GAUGE_ROTATION = 67,   // [n, i0..., w]  kill the infinitesimal-rotation null space

        // A caller-supplied residual, as a little register machine evaluated in T. See ExprVm.
        EXPR = 68
    };
}

// ============================================================
// Residual expression VM
// ============================================================
//
// Every opcode above is a residual someone wrote in C++, which means every new kind of residual
// costs a native rebuild and a matching Kotlin change. EXPR breaks that: the caller ships a small
// program, this evaluates it, and because the evaluator is templated on the scalar type Ceres
// autodiffs straight through it. No new C++ for a new residual.
//
// It is a register machine, not a stack machine, for one reason: the caller compiles an expression
// DAG, and with named destination slots common subexpressions are shared by construction. A stack
// machine would have to recompute them or juggle DUP/SWAP.
//
// Instructions are four doubles: [op, dst, a, b]. `a` and `b` are slot indices except where noted;
// unary operators ignore `b`.
namespace expr
{
    enum : int
    {
        CONST = 0,  // a = index into the constant pool
        PARAM = 1,  // a = parameter-block index, b = component 0..2
        ADD = 2,
        SUB = 3,
        MUL = 4,
        DIV = 5,
        NEG = 6,
        SQRT = 7,
        ABS = 8,
        SIN = 9,
        COS = 10,
        TAN = 11,
        EXP = 12,
        LOG = 13,
        POW = 14,
        MIN = 15,
        MAX = 16,
        ATAN2 = 17,
        SQUARE = 18,
        RECIP = 19,
    };
}

// ============================================================
// Operand layout
// ============================================================
//
// The program is parsed three times (pre-count PLANAR, add residuals, add locks) and every pass
// has to agree on how many doubles each opcode consumes. Historically each pass carried its own
// copy of that switch, so adding an opcode meant editing three tables and forgetting one desynced
// the stream silently. This is the single table; the skipping passes defer to it, so a new opcode
// needs one entry here plus its case in the build pass.
//
// Returns the number of doubles the operands occupy (not counting the opcode itself), or -1 if
// the opcode is unknown or its operands run off the end of the program.
static int operand_span(int opcode, const double *program, int ip, int prog_len)
{
    auto counted = [&](int extra) -> int
    {
        // [n, <n indices>, <extra>]
        if (ip >= prog_len)
            return -1;
        int n = (int)program[ip];
        if (n < 0)
            return -1;
        int span = 1 + n + extra;
        return (ip + span <= prog_len) ? span : -1;
    };
    auto fixed = [&](int span) -> int
    { return (ip + span <= prog_len) ? span : -1; };

    switch (opcode)
    {
    case op::PLANAR:
        return counted(1);
    case op::ON_LINE:
        return counted(1);
    case op::DISTANCE:
        return fixed(4);
    case op::SAME_X:
    case op::SAME_Y:
    case op::SAME_Z:
    case op::SAME_XY:
    case op::SAME_XZ:
    case op::SAME_YZ:
        return counted(1);
    case op::PERP_AT_ORIGIN:
        return fixed(4);
    case op::LOCK_X:
    case op::LOCK_Y:
    case op::LOCK_Z:
    case op::LOCK_XY:
    case op::LOCK_XZ:
    case op::LOCK_YZ:
        return counted(1);
    case op::PRIOR_3:
    case op::PRIOR_X:
    case op::PRIOR_Y:
    case op::PRIOR_Z:
    case op::NO_Z_NEG:
    {
        // These accept the -1 "all points" sentinel in the count slot.
        if (ip >= prog_len)
            return -1;
        int n = (int)program[ip];
        int span = (n < 0) ? 2 : (1 + n + 1);
        return (ip + span <= prog_len) ? span : -1;
    }
    case op::MIN_DISTANCE:
    case op::MAX_DISTANCE:
        return fixed(4);
    case op::EQUAL_DISTANCE:
        return fixed(5);
    case op::DOT_AT_ORIGIN:
        return fixed(6);
    case op::VECTOR_DOT:
        return fixed(7);
    case op::POINT_ON_PLANE:
        return fixed(6);
    case op::LINE_PARALLEL:
        return fixed(5);
    case op::LINE_ANGLE:
        return fixed(6);
    case op::DIHEDRAL_ANGLE:
        return fixed(6);
    case op::EQUAL_EDGE_VECTOR:
        return fixed(5);
    case op::MIRROR_ACROSS_PLANE:
        return fixed(6);
    case op::PANEL_AREA:
        return fixed(6);
    case op::POINT_IN_HALFSPACE:
        return fixed(6);
    case op::LINE_DIRECTION:
        return fixed(6);
    case op::PLANE_NORMAL_DIRECTION:
        return fixed(8);
    case op::LINE_TOWARD_POINT:
        return fixed(4);
    case op::SET_LOSS:
        return fixed(2);
    case op::EDGE_TANGENCY:
        return fixed(4);
    case op::TANGENCY_CENTROID:
    {
        // [nEdges, <2*nEdges indices>, w]
        if (ip >= prog_len)
            return -1;
        int n = (int)program[ip];
        if (n < 0)
            return -1;
        int span = 1 + 2 * n + 1;
        return (ip + span <= prog_len) ? span : -1;
    }
    case op::CENTROID:
    {
        // [n, <n indices | none if n<0>, tx, ty, tz, w]
        if (ip >= prog_len)
            return -1;
        int n = (int)program[ip];
        int span = (n < 0) ? 5 : (1 + n + 4);
        return (ip + span <= prog_len) ? span : -1;
    }
    case op::RADIUS_ABOUT_CENTROID:
    {
        // [n, <n indices | none if n<0>, radius, w]
        if (ip >= prog_len)
            return -1;
        int n = (int)program[ip];
        int span = (n < 0) ? 3 : (1 + n + 2);
        return (ip + span <= prog_len) ? span : -1;
    }
    case op::PLANAR_HESSE:
    case op::PLANAR_FIT:
        return counted(1);
    case op::GAUGE_ROTATION:
    {
        // [n, <n indices | none if n<0>, w]
        if (ip >= prog_len)
            return -1;
        int n = (int)program[ip];
        int span = (n < 0) ? 2 : (1 + n + 1);
        return (ip + span <= prog_len) ? span : -1;
    }
    case op::EXPR:
    {
        // [nParams, p..., nResiduals, r..., nSlots, nConsts, c..., nInstr, (op,dst,a,b)..., w]
        int at = ip;
        auto take = [&](int count) -> bool
        {
            if (at + count > prog_len)
                return false;
            at += count;
            return true;
        };
        auto counted_run = [&]() -> bool
        {
            if (at >= prog_len)
                return false;
            int n = (int)program[at];
            return n >= 0 && take(1 + n);
        };
        if (!counted_run())      // params
            return -1;
        if (!counted_run())      // residual slots
            return -1;
        if (!take(1))            // nSlots
            return -1;
        if (!counted_run())      // constants
            return -1;
        if (at >= prog_len)
            return -1;
        int instructions = (int)program[at];
        if (instructions < 0 || !take(1 + 4 * instructions))
            return -1;
        if (!take(1))            // weight
            return -1;
        return at - ip;
    }
    default:
        return -1;
    }
}

// ============================================================
// Robust loss (global)
// ============================================================
namespace robust
{
    // Huber(1e-3) was hardcoded, which is a reasonable default for interactive architectural
    // constraint editing (where a single badly-placed point should not drag the whole model) and
    // exactly wrong for a least-squares problem whose residuals are *meant* to be driven to zero
    // together — a robust loss silently caps the influence of the largest residual and changes the
    // objective. So it is per-program now: emit SET_LOSS at the head of a program to choose, and
    // the historic default stands if you do not.
    enum Kind : int
    {
        NONE = 0,
        HUBER = 1,
        SOFT_L_ONE = 2,
        CAUCHY = 3,
    };

    struct Choice
    {
        int kind = HUBER;
        double scale = 1e-3;

        ceres::LossFunction *make() const
        {
            switch (kind)
            {
            case NONE:
                return nullptr;
            case SOFT_L_ONE:
                return new ceres::SoftLOneLoss(scale);
            case CAUCHY:
                return new ceres::CauchyLoss(scale);
            case HUBER:
            default:
                return new ceres::HuberLoss(scale);
            }
        }
    };
}

// ============================================================
// Small helpers
// ============================================================
static inline double sqr(double x) { return x * x; }

template <typename T>
static inline void cross3(T ax, T ay, T az,
                          T bx, T by, T bz,
                          T &cx, T &cy, T &cz)
{
    cx = ay * bz - az * by;
    cy = az * bx - ax * bz;
    cz = ax * by - ay * bx;
}

template <typename T>
static inline T dot3(T ax, T ay, T az, T bx, T by, T bz)
{
    return ax * bx + ay * by + az * bz;
}

template <typename T>
static inline void unit_between(const T *const A, const T *const B, T &x, T &y, T &z)
{
    x = B[0] - A[0];
    y = B[1] - A[1];
    z = B[2] - A[2];
    T n = ceres::sqrt(x * x + y * y + z * z) + T(1e-20);
    x /= n;
    y /= n;
    z /= n;
}

template <typename T>
static inline void unit_plane_normal(const T *const A, const T *const B, const T *const C,
                                     T &nx, T &ny, T &nz)
{
    T ux = B[0] - A[0], uy = B[1] - A[1], uz = B[2] - A[2];
    T vx = C[0] - A[0], vy = C[1] - A[1], vz = C[2] - A[2];
    cross3(ux, uy, uz, vx, vy, vz, nx, ny, nz);
    T n = ceres::sqrt(nx * nx + ny * ny + nz * nz) + T(1e-20);
    nx /= n;
    ny /= n;
    nz /= n;
}

// ============================================================
// Residual functors
// ============================================================

// Unit-length for plane normal (fallback if SphereManifold unavailable)
struct UnitSphere3
{
    template <typename T>
    bool operator()(const T *const n, T *r) const
    {
        r[0] = n[0] * n[0] + n[1] * n[1] + n[2] * n[2] - T(1);
        return true;
    }
};

// Stabilize plane origin near seed O0
struct OriginPriorW
{
    double O0[3];
    double s;
    OriginPriorW(const double *T, double w) : O0{T[0], T[1], T[2]}, s(std::sqrt(std::max(0.0, w))) {}
    template <typename S>
    bool operator()(const S *const O, S *r) const
    {
        r[0] = S(s) * (O[0] - S(O0[0]));
        r[1] = S(s) * (O[1] - S(O0[1]));
        r[2] = S(s) * (O[2] - S(O0[2]));
        return true;
    }
};

// Stabilize plane normal to align with seed n0 (n·n0→1)
struct NormalAlignW
{
    double n0[3];
    double s;
    NormalAlignW(const double *N0, double w) : n0{N0[0], N0[1], N0[2]}, s(std::sqrt(std::max(0.0, w))) {}
    template <typename S>
    bool operator()(const S *const n, S *r) const
    {
        r[0] = S(s) * (n[0] * S(n0[0]) + n[1] * S(n0[1]) + n[2] * S(n0[2]) - S(1));
        return true;
    }
};

// Point on plane: (P - O) · n = 0
struct PointOnPlaneW
{
    double s;
    explicit PointOnPlaneW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const P,
                    const T *const O,
                    const T *const n,
                    T *r) const
    {
        r[0] = T(s) * ((P[0] - O[0]) * n[0] + (P[1] - O[1]) * n[1] + (P[2] - O[2]) * n[2]);
        return true;
    }
};
// Distance equality: ||Pi - Pj|| = D
struct DistanceW
{
    double D, s;
    DistanceW(double D_, double w) : D(D_), s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B, T *r) const
    {
        T dx = A[0] - B[0], dy = A[1] - B[1], dz = A[2] - B[2];
        T d = ceres::sqrt(dx * dx + dy * dy + dz * dz) + T(1e-20);
        r[0] = T(s) * (d - T(D));
        return true;
    }
};

// The evaluator for op::EXPR. One instance per residual block; `code`, `constants` and
// `residualSlots` are fixed at build time and only the scalar type varies.
//
// Templated on T so the same code runs on double for evaluation and on ceres::Jet for
// differentiation — which is the whole point: a residual defined at runtime still gets exact
// derivatives, not finite differences.
struct ExprVm
{
    std::vector<int> code;          // 4 ints per instruction: op, dst, a, b
    std::vector<double> constants;
    std::vector<int> residualSlots;
    int slotCount = 0;
    double s = 1.0;

    int instructionCount() const { return (int)(code.size() / 4); }

    template <typename T>
    bool operator()(T const *const *p, T *residuals) const
    {
        // One scratch slot vector per evaluation. Small and short-lived; if this ever shows up in a
        // profile the fix is a fixed-size buffer for the common case, not a redesign.
        std::vector<T> slot(slotCount, T(0));

        const int n = instructionCount();
        for (int i = 0; i < n; ++i)
        {
            const int op = code[4 * i];
            const int dst = code[4 * i + 1];
            const int a = code[4 * i + 2];
            const int b = code[4 * i + 3];
            switch (op)
            {
            case expr::CONST:
                slot[dst] = T(constants[a]);
                break;
            case expr::PARAM:
                slot[dst] = p[a][b];
                break;
            case expr::ADD:
                slot[dst] = slot[a] + slot[b];
                break;
            case expr::SUB:
                slot[dst] = slot[a] - slot[b];
                break;
            case expr::MUL:
                slot[dst] = slot[a] * slot[b];
                break;
            case expr::DIV:
                slot[dst] = slot[a] / slot[b];
                break;
            case expr::NEG:
                slot[dst] = -slot[a];
                break;
            case expr::SQRT:
                slot[dst] = ceres::sqrt(slot[a]);
                break;
            case expr::ABS:
                slot[dst] = ceres::abs(slot[a]);
                break;
            case expr::SIN:
                slot[dst] = ceres::sin(slot[a]);
                break;
            case expr::COS:
                slot[dst] = ceres::cos(slot[a]);
                break;
            case expr::TAN:
                slot[dst] = ceres::tan(slot[a]);
                break;
            case expr::EXP:
                slot[dst] = ceres::exp(slot[a]);
                break;
            case expr::LOG:
                slot[dst] = ceres::log(slot[a]);
                break;
            case expr::POW:
                slot[dst] = ceres::pow(slot[a], slot[b]);
                break;
            case expr::MIN:
                slot[dst] = ceres::fmin(slot[a], slot[b]);
                break;
            case expr::MAX:
                slot[dst] = ceres::fmax(slot[a], slot[b]);
                break;
            case expr::ATAN2:
                slot[dst] = ceres::atan2(slot[a], slot[b]);
                break;
            case expr::SQUARE:
                slot[dst] = slot[a] * slot[a];
                break;
            case expr::RECIP:
                slot[dst] = T(1.0) / slot[a];
                break;
            default:
                return false; // validated at build time; a bad opcode here means memory corruption
            }
        }

        for (size_t i = 0; i < residualSlots.size(); ++i)
            residuals[i] = T(s) * slot[residualSlots[i]];
        return true;
    }
};

// ------------------------------------------------------------
// Better-conditioned planarity, and a rotation gauge.
//
// PLANAR parameterises each face's plane as an origin O and a unit normal n, and its residual is
// (P - O) . n. That expression depends on O only through O . n, so the two components of O
// tangential to the plane are an EXACT null direction — two per face, held up by nothing but the
// 1e-6 OriginPriorW stabiliser. On a solid with more faces than vertices (any kis-like operator)
// that dominates: a 72-face polyhedron carries 144 near-null directions against 114 real ones, and
// Levenberg-Marquardt spends its whole iteration budget crawling along them.
//
// PLANAR_HESSE fixes the parameterisation: a plane is { x : x . n == d } with n on a sphere
// manifold and d a scalar, which is three effective parameters per face and no null space.
//
// PLANAR_FIT removes the plane variables entirely by computing the Newell best-fit plane inside the
// residual. It is exactly what afield.stdlib.polytope.FacePlanarityConstraint measures, so it makes
// the Kotlin and Ceres realizers the same objective rather than merely equivalent ones.
// ------------------------------------------------------------

// Point on plane in Hesse normal form: P . n - d.
struct PointOnPlaneHesseW
{
    double s;
    explicit PointOnPlaneHesseW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const P, const T *const n, const T *const d, T *r) const
    {
        r[0] = T(s) * (P[0] * n[0] + P[1] * n[1] + P[2] * n[2] - d[0]);
        return true;
    }
};

// Every corner's signed distance to the face's own Newell best-fit plane. One residual per corner,
// no auxiliary variables. Mirrors facePlane() + signedPlaneDistance() in Constraints.kt, including
// the 1e-10 floor on the normal length that keeps a degenerate face from dividing by zero.
struct PlanarFitW
{
    int n;
    double s;
    PlanarFitW(int n_, double w) : n(n_), s(std::sqrt(std::max(0.0, w))) {}

    template <typename T>
    bool operator()(T const *const *p, T *r) const
    {
        T cx = T(0), cy = T(0), cz = T(0);
        T nx = T(0), ny = T(0), nz = T(0);
        for (int i = 0; i < n; ++i)
        {
            const T *a = p[i];
            const T *b = p[(i + 1) % n];
            cx += a[0];
            cy += a[1];
            cz += a[2];
            nx += (a[1] - b[1]) * (a[2] + b[2]);
            ny += (a[2] - b[2]) * (a[0] + b[0]);
            nz += (a[0] - b[0]) * (a[1] + b[1]);
        }
        T inv = T(1.0 / (double)std::max(1, n));
        cx *= inv;
        cy *= inv;
        cz *= inv;

        T len = ceres::sqrt(nx * nx + ny * ny + nz * nz + T(1e-20));
        if (len < T(1e-10))
            len = T(1e-10);
        for (int i = 0; i < n; ++i)
        {
            r[i] = T(s) * ((p[i][0] - cx) * nx + (p[i][1] - cy) * ny + (p[i][2] - cz) * nz) / len;
        }
        return true;
    }
};

// Three residuals: sum_i (ref_i x p_i), the linearisation of "the cloud has not rotated relative to
// where it started".
//
// The canonical conditions pin translation (TANGENCY_CENTROID) and scale (EDGE_TANGENCY) but say
// nothing about orientation, so the optimum is a one-parameter-per-axis continuum and the minimiser
// is free to wander along it. Removing the null space explicitly is cheaper than damping it, and it
// makes the answer reproducible instead of dependent on the path taken to it.
struct GaugeRotationW
{
    std::vector<double> ref; // 3 per point, in parameter-block order
    double s;
    GaugeRotationW(std::vector<double> ref_, double w)
        : ref(std::move(ref_)), s(std::sqrt(std::max(0.0, w))) {}

    template <typename T>
    bool operator()(T const *const *p, T *r) const
    {
        T sx = T(0), sy = T(0), sz = T(0);
        const int n = (int)(ref.size() / 3);
        for (int i = 0; i < n; ++i)
        {
            T ax = T(ref[3 * i]), ay = T(ref[3 * i + 1]), az = T(ref[3 * i + 2]);
            sx += ay * p[i][2] - az * p[i][1];
            sy += az * p[i][0] - ax * p[i][2];
            sz += ax * p[i][1] - ay * p[i][0];
        }
        T inv = T(1.0 / (double)std::max(1, n));
        r[0] = T(s) * sx * inv;
        r[1] = T(s) * sy * inv;
        r[2] = T(s) * sz * inv;
        return true;
    }
};

// ------------------------------------------------------------
// Global / midsphere constraints.
//
// The first is per-edge and touches two points, so it stays sparse. The other three are genuine
// all-points constraints — a mean or a fit over every vertex — and are dynamic cost functions with
// one parameter block per point. Ceres forbids a parameter block appearing twice in one residual
// block, so callers pass *distinct* point ids and the functor indexes into that list.
// ------------------------------------------------------------

// The point on the infinite line AB nearest the origin. Shared by the two tangency constraints so
// they cannot drift apart. Mirrors afield.stdlib.polytope.edgeTangentPoint.
template <typename T>
static inline void tangent_point(const T *const A, const T *const B, T &px, T &py, T &pz)
{
    T dx = B[0] - A[0], dy = B[1] - A[1], dz = B[2] - A[2];
    T len2 = dx * dx + dy * dy + dz * dz + T(1e-20);
    T t = -(A[0] * dx + A[1] * dy + A[2] * dz) / len2;
    px = A[0] + t * dx;
    py = A[1] + t * dy;
    pz = A[2] + t * dz;
}

// EDGE_TANGENCY: residual = |tangent point| - radius. Every edge tangent to one sphere about the
// origin is the midsphere half of the canonical form.
struct EdgeTangencyW
{
    double r, s;
    EdgeTangencyW(double r_, double w) : r(r_), s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B, T *res) const
    {
        T px, py, pz;
        tangent_point(A, B, px, py, pz);
        T d = ceres::sqrt(px * px + py * py + pz * pz + T(1e-20));
        res[0] = T(s) * (d - T(r));
        return true;
    }
};

// TANGENCY_CENTROID: three residuals, the mean tangency point over all edges. The centring half of
// the canonical form — deliberately about the tangency points, not the vertex centroid.
struct TangencyCentroidW
{
    std::vector<int> ea, eb; // indices into the parameter-block list
    double s;
    TangencyCentroidW(std::vector<int> ea_, std::vector<int> eb_, double w)
        : ea(std::move(ea_)), eb(std::move(eb_)), s(std::sqrt(std::max(0.0, w))) {}

    template <typename T>
    bool operator()(T const *const *p, T *res) const
    {
        T sx = T(0), sy = T(0), sz = T(0);
        for (size_t e = 0; e < ea.size(); ++e)
        {
            T px, py, pz;
            tangent_point(p[ea[e]], p[eb[e]], px, py, pz);
            sx += px;
            sy += py;
            sz += pz;
        }
        T inv = T(1.0 / (double)std::max<size_t>(1, ea.size()));
        res[0] = T(s) * sx * inv;
        res[1] = T(s) * sy * inv;
        res[2] = T(s) * sz * inv;
        return true;
    }
};

// CENTROID: three residuals pinning the mean of the listed points to a target.
struct CentroidW
{
    double tx, ty, tz, s;
    int n;
    CentroidW(double tx_, double ty_, double tz_, int n_, double w)
        : tx(tx_), ty(ty_), tz(tz_), s(std::sqrt(std::max(0.0, w))), n(n_) {}

    template <typename T>
    bool operator()(T const *const *p, T *res) const
    {
        T sx = T(0), sy = T(0), sz = T(0);
        for (int i = 0; i < n; ++i)
        {
            sx += p[i][0];
            sy += p[i][1];
            sz += p[i][2];
        }
        T inv = T(1.0 / (double)std::max(1, n));
        res[0] = T(s) * (sx * inv - T(tx));
        res[1] = T(s) * (sy * inv - T(ty));
        res[2] = T(s) * (sz * inv - T(tz));
        return true;
    }
};

// RADIUS_ABOUT_CENTROID: one residual per listed point, its distance from the running mean of the
// same set minus a target radius — a circumsphere whose centre is not a free variable.
struct RadiusAboutCentroidW
{
    double r, s;
    int n;
    RadiusAboutCentroidW(double r_, int n_, double w)
        : r(r_), s(std::sqrt(std::max(0.0, w))), n(n_) {}

    template <typename T>
    bool operator()(T const *const *p, T *res) const
    {
        T cx = T(0), cy = T(0), cz = T(0);
        for (int i = 0; i < n; ++i)
        {
            cx += p[i][0];
            cy += p[i][1];
            cz += p[i][2];
        }
        T inv = T(1.0 / (double)std::max(1, n));
        cx *= inv;
        cy *= inv;
        cz *= inv;
        for (int i = 0; i < n; ++i)
        {
            T dx = p[i][0] - cx, dy = p[i][1] - cy, dz = p[i][2] - cz;
            T d = ceres::sqrt(dx * dx + dy * dy + dz * dz + T(1e-20));
            res[i] = T(s) * (d - T(r));
        }
        return true;
    }
};

// MIN_DISTANCE: residual = max(0, Dmin - ||A-B||)
struct MinDistanceW
{
    double Dmin, s;
    MinDistanceW(double Dmin_, double w) : Dmin(Dmin_), s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B, T *r) const
    {
        T dx = A[0] - B[0], dy = A[1] - B[1], dz = A[2] - B[2];
        T d = ceres::sqrt(dx * dx + dy * dy + dz * dz) + T(1e-20);
        T gap = T(Dmin) - d;
        r[0] = T(s) * fmax(T(0), gap);
        return true;
    }
};

// MAX_DISTANCE: residual = max(0, ||A-B|| - Dmax)
struct MaxDistanceW
{
    double Dmax, s;
    MaxDistanceW(double Dmax_, double w) : Dmax(Dmax_), s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B, T *r) const
    {
        T dx = A[0] - B[0], dy = A[1] - B[1], dz = A[2] - B[2];
        T d = ceres::sqrt(dx * dx + dy * dy + dz * dz) + T(1e-20);
        T gap = d - T(Dmax);
        r[0] = T(s) * fmax(T(0), gap);
        return true;
    }
};

// EQUAL_DISTANCE: ||A-B|| - ||C-D|| = 0
struct EqualDistanceW
{
    double s;
    EqualDistanceW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B, const T *const C, const T *const D, T *r) const
    {
        T dx1 = A[0] - B[0], dy1 = A[1] - B[1], dz1 = A[2] - B[2];
        T dx2 = C[0] - D[0], dy2 = C[1] - D[1], dz2 = C[2] - D[2];
        T d1 = ceres::sqrt(dx1 * dx1 + dy1 * dy1 + dz1 * dz1) + T(1e-20);
        T d2 = ceres::sqrt(dx2 * dx2 + dy2 * dy2 + dz2 * dz2) + T(1e-20);
        r[0] = T(s) * (d1 - d2);
        return true;
    }
};

// SAME coordinate(s)
struct SameXW
{
    double s;
    SameXW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B, T *r) const
    {
        r[0] = T(s) * (A[0] - B[0]);
        return true;
    }
};
struct SameYW
{
    double s;
    SameYW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B, T *r) const
    {
        r[0] = T(s) * (A[1] - B[1]);
        return true;
    }
};
struct SameZW
{
    double s;
    SameZW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B, T *r) const
    {
        r[0] = T(s) * (A[2] - B[2]);
        return true;
    }
};
struct SameXYW
{
    double s;
    SameXYW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B, T *r) const
    {
        r[0] = T(s) * (A[0] - B[0]);
        r[1] = T(s) * (A[1] - B[1]);
        return true;
    }
};
struct SameXZW
{
    double s;
    SameXZW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B, T *r) const
    {
        r[0] = T(s) * (A[0] - B[0]);
        r[1] = T(s) * (A[2] - B[2]);
        return true;
    }
};
struct SameYZW
{
    double s;
    SameYZW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B, T *r) const
    {
        r[0] = T(s) * (A[1] - B[1]);
        r[1] = T(s) * (A[2] - B[2]);
        return true;
    }
};

// Point on line AB (returns 3 residuals = cross(u, v), scale-invariant)
struct PointOnLineW
{
    double s;
    PointOnLineW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const P, const T *const A, const T *const B, T *r) const
    {
        T dx = B[0] - A[0], dy = B[1] - A[1], dz = B[2] - A[2];
        T n = ceres::sqrt(dx * dx + dy * dy + dz * dz) + T(1e-20);
        T ux = dx / n, uy = dy / n, uz = dz / n;
        T vx = P[0] - A[0], vy = P[1] - A[1], vz = P[2] - A[2];
        // cross(u, v) -> zero iff v is colinear with u
        T cx = uy * vz - uz * vy;
        T cy = uz * vx - ux * vz;
        T cz = ux * vy - uy * vx;
        r[0] = T(s) * cx;
        r[1] = T(s) * cy;
        r[2] = T(s) * cz;
        return true;
    }
};

// Perpendicular at origin o: (a-o) ⟂ (b-o)
struct PerpAtOriginW
{
    double s;
    PerpAtOriginW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const O, const T *const A, const T *const B, T *r) const
    {
        T ax = A[0] - O[0], ay = A[1] - O[1], az = A[2] - O[2];
        T bx = B[0] - O[0], by = B[1] - O[1], bz = B[2] - O[2];
        T na = ceres::sqrt(ax * ax + ay * ay + az * az) + T(1e-20);
        T nb = ceres::sqrt(bx * bx + by * by + bz * bz) + T(1e-20);
        ax /= na;
        ay /= na;
        az /= na;
        bx /= nb;
        by /= nb;
        bz /= nb;
        r[0] = T(s) * (ax * bx + ay * by + az * bz);
        return true;
    }
};

struct DotAtOriginW
{
    double target, normalized, s;
    DotAtOriginW(double target_, double normalized_, double w)
        : target(target_), normalized(normalized_), s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const O, const T *const A, const T *const B, T *r) const
    {
        T ax = A[0] - O[0], ay = A[1] - O[1], az = A[2] - O[2];
        T bx = B[0] - O[0], by = B[1] - O[1], bz = B[2] - O[2];
        if (normalized != 0.0)
        {
            T na = ceres::sqrt(ax * ax + ay * ay + az * az) + T(1e-20);
            T nb = ceres::sqrt(bx * bx + by * by + bz * bz) + T(1e-20);
            ax /= na;
            ay /= na;
            az /= na;
            bx /= nb;
            by /= nb;
            bz /= nb;
        }
        r[0] = T(s) * (ax * bx + ay * by + az * bz - T(target));
        return true;
    }
};

struct VectorDotW
{
    double target, normalized, s;
    VectorDotW(double target_, double normalized_, double w)
        : target(target_), normalized(normalized_), s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A0, const T *const A1,
                    const T *const B0, const T *const B1, T *r) const
    {
        T ax = A1[0] - A0[0], ay = A1[1] - A0[1], az = A1[2] - A0[2];
        T bx = B1[0] - B0[0], by = B1[1] - B0[1], bz = B1[2] - B0[2];
        if (normalized != 0.0)
        {
            T na = ceres::sqrt(ax * ax + ay * ay + az * az) + T(1e-20);
            T nb = ceres::sqrt(bx * bx + by * by + bz * bz) + T(1e-20);
            ax /= na;
            ay /= na;
            az /= na;
            bx /= nb;
            by /= nb;
            bz /= nb;
        }
        r[0] = T(s) * (ax * bx + ay * by + az * bz - T(target));
        return true;
    }
};

struct PointOnPlane3W
{
    double offset, s;
    PointOnPlane3W(double offset_, double w) : offset(offset_), s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const P, const T *const A, const T *const B, const T *const C, T *r) const
    {
        T nx, ny, nz;
        unit_plane_normal(A, B, C, nx, ny, nz);
        r[0] = T(s) * (dot3(P[0] - A[0], P[1] - A[1], P[2] - A[2], nx, ny, nz) - T(offset));
        return true;
    }
};

struct LineParallelW
{
    double s;
    explicit LineParallelW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A0, const T *const A1,
                    const T *const B0, const T *const B1, T *r) const
    {
        T ax, ay, az, bx, by, bz;
        unit_between(A0, A1, ax, ay, az);
        unit_between(B0, B1, bx, by, bz);
        cross3(ax, ay, az, bx, by, bz, r[0], r[1], r[2]);
        r[0] *= T(s);
        r[1] *= T(s);
        r[2] *= T(s);
        return true;
    }
};

struct LineAngleW
{
    double target, s;
    LineAngleW(double target_, double w) : target(target_), s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A0, const T *const A1,
                    const T *const B0, const T *const B1, T *r) const
    {
        T ax, ay, az, bx, by, bz;
        unit_between(A0, A1, ax, ay, az);
        unit_between(B0, B1, bx, by, bz);
        r[0] = T(s) * (dot3(ax, ay, az, bx, by, bz) - T(target));
        return true;
    }
};

struct DihedralAngleW
{
    double target, s;
    DihedralAngleW(double target_, double w) : target(target_), s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B,
                    const T *const C, const T *const D, T *r) const
    {
        T n1x, n1y, n1z, n2x, n2y, n2z;
        unit_plane_normal(A, B, C, n1x, n1y, n1z);
        unit_plane_normal(A, B, D, n2x, n2y, n2z);
        r[0] = T(s) * (dot3(n1x, n1y, n1z, n2x, n2y, n2z) - T(target));
        return true;
    }
};

struct EqualEdgeVectorW
{
    double s;
    explicit EqualEdgeVectorW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B,
                    const T *const C, const T *const D, T *r) const
    {
        r[0] = T(s) * ((B[0] - A[0]) - (D[0] - C[0]));
        r[1] = T(s) * ((B[1] - A[1]) - (D[1] - C[1]));
        r[2] = T(s) * ((B[2] - A[2]) - (D[2] - C[2]));
        return true;
    }
};

struct MirrorAcrossPlaneW
{
    double s;
    explicit MirrorAcrossPlaneW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const P, const T *const Q,
                    const T *const A, const T *const B, const T *const C, T *r) const
    {
        T nx, ny, nz;
        unit_plane_normal(A, B, C, nx, ny, nz);
        T mx = (P[0] + Q[0]) * T(0.5);
        T my = (P[1] + Q[1]) * T(0.5);
        T mz = (P[2] + Q[2]) * T(0.5);
        r[0] = T(s) * dot3(mx - A[0], my - A[1], mz - A[2], nx, ny, nz);

        T vx = Q[0] - P[0], vy = Q[1] - P[1], vz = Q[2] - P[2];
        cross3(nx, ny, nz, vx, vy, vz, r[1], r[2], r[3]);
        r[1] *= T(s);
        r[2] *= T(s);
        r[3] *= T(s);
        return true;
    }
};

struct PanelAreaW
{
    double target, s;
    PanelAreaW(double target_, double w) : target(target_), s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const A, const T *const B,
                    const T *const C, const T *const D, T *r) const
    {
        T abx = B[0] - A[0], aby = B[1] - A[1], abz = B[2] - A[2];
        T acx = C[0] - A[0], acy = C[1] - A[1], acz = C[2] - A[2];
        T adx = D[0] - A[0], ady = D[1] - A[1], adz = D[2] - A[2];
        T c1x, c1y, c1z, c2x, c2y, c2z;
        cross3(abx, aby, abz, acx, acy, acz, c1x, c1y, c1z);
        cross3(acx, acy, acz, adx, ady, adz, c2x, c2y, c2z);
        T area = T(0.5) * (ceres::sqrt(dot3(c1x, c1y, c1z, c1x, c1y, c1z)) +
                           ceres::sqrt(dot3(c2x, c2y, c2z, c2x, c2y, c2z)));
        r[0] = T(s) * (area - T(target));
        return true;
    }
};

struct PointInHalfspaceW
{
    double min_signed_distance, s;
    PointInHalfspaceW(double min_signed_distance_, double w)
        : min_signed_distance(min_signed_distance_), s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const P, const T *const A, const T *const B, const T *const C, T *r) const
    {
        T nx, ny, nz;
        unit_plane_normal(A, B, C, nx, ny, nz);
        T signed_distance = dot3(P[0] - A[0], P[1] - A[1], P[2] - A[2], nx, ny, nz);
        r[0] = T(s) * fmax(T(0), T(min_signed_distance) - signed_distance);
        return true;
    }
};

struct LineDirectionW
{
    double dx, dy, dz, s;
    LineDirectionW(double dx_, double dy_, double dz_, double w) : dx(dx_), dy(dy_), dz(dz_), s(std::sqrt(std::max(0.0, w)))
    {
        double n = std::sqrt(dx * dx + dy * dy + dz * dz);
        if (n > 1e-20)
        {
            dx /= n;
            dy /= n;
            dz /= n;
        }
    }
    template <typename T>
    bool operator()(const T *const A, const T *const B, T *r) const
    {
        T ux, uy, uz;
        unit_between(A, B, ux, uy, uz);
        cross3(ux, uy, uz, T(dx), T(dy), T(dz), r[0], r[1], r[2]);
        r[0] *= T(s);
        r[1] *= T(s);
        r[2] *= T(s);
        return true;
    }
};

struct PlaneNormalDirectionW
{
    double nx, ny, nz, target, s;
    PlaneNormalDirectionW(double nx_, double ny_, double nz_, double target_, double w)
        : nx(nx_), ny(ny_), nz(nz_), target(target_), s(std::sqrt(std::max(0.0, w)))
    {
        double n = std::sqrt(nx * nx + ny * ny + nz * nz);
        if (n > 1e-20)
        {
            nx /= n;
            ny /= n;
            nz /= n;
        }
    }
    template <typename T>
    bool operator()(const T *const A, const T *const B, const T *const C, T *r) const
    {
        T ux, uy, uz;
        unit_plane_normal(A, B, C, ux, uy, uz);
        r[0] = T(s) * (dot3(ux, uy, uz, T(nx), T(ny), T(nz)) - T(target));
        return true;
    }
};

// Locks (to build-time coordinates)
struct LockXW
{
    const double *T;
    double s;
    LockXW(const double *tx, double w) : T(tx), s(std::sqrt(std::max(0.0, w))) {}
    template <typename S>
    bool operator()(const S *const P, S *r) const
    {
        r[0] = S(s) * (P[0] - S(*T));
        return true;
    }
};
struct LockYW
{
    const double *T;
    double s;
    LockYW(const double *ty, double w) : T(ty), s(std::sqrt(std::max(0.0, w))) {}
    template <typename S>
    bool operator()(const S *const P, S *r) const
    {
        r[0] = S(s) * (P[1] - S(*T));
        return true;
    }
};
struct LockZW
{
    const double *T;
    double s;
    LockZW(const double *tz, double w) : T(tz), s(std::sqrt(std::max(0.0, w))) {}
    template <typename S>
    bool operator()(const S *const P, S *r) const
    {
        r[0] = S(s) * (P[2] - S(*T));
        return true;
    }
};
struct LockXYW
{
    const double *Tx;
    const double *Ty;
    double s;
    LockXYW(const double *tx, const double *ty, double w) : Tx(tx), Ty(ty), s(std::sqrt(std::max(0.0, w))) {}
    template <typename S>
    bool operator()(const S *const P, S *r) const
    {
        r[0] = S(s) * (P[0] - S(*Tx));
        r[1] = S(s) * (P[1] - S(*Ty));
        return true;
    }
};
struct LockXZW
{
    const double *Tx;
    const double *Tz;
    double s;
    LockXZW(const double *tx, const double *tz, double w) : Tx(tx), Tz(tz), s(std::sqrt(std::max(0.0, w))) {}
    template <typename S>
    bool operator()(const S *const P, S *r) const
    {
        r[0] = S(s) * (P[0] - S(*Tx));
        r[1] = S(s) * (P[2] - S(*Tz));
        return true;
    }
};
struct LockYZW
{
    const double *Ty;
    const double *Tz;
    double s;
    LockYZW(const double *ty, const double *tz, double w) : Ty(ty), Tz(tz), s(std::sqrt(std::max(0.0, w))) {}
    template <typename S>
    bool operator()(const S *const P, S *r) const
    {
        r[0] = S(s) * (P[1] - S(*Ty));
        r[1] = S(s) * (P[2] - S(*Tz));
        return true;
    }
};

// Priors (dynamic weights by pointer, targets updated every solve)
struct Prior3Dyn
{
    const double *T;
    const double *wx, *wy, *wz;
    Prior3Dyn(const double *T_, const double *wx_, const double *wy_, const double *wz_)
        : T(T_), wx(wx_), wy(wy_), wz(wz_) {}
    template <typename S>
    bool operator()(const S *const P, S *r) const
    {
        S sx = S(std::sqrt(std::max(0.0, *wx)));
        S sy = S(std::sqrt(std::max(0.0, *wy)));
        S sz = S(std::sqrt(std::max(0.0, *wz)));
        r[0] = sx * (P[0] - S(T[0]));
        r[1] = sy * (P[1] - S(T[1]));
        r[2] = sz * (P[2] - S(T[2]));
        return true;
    }
};
struct Prior1Dyn
{
    const double *T;
    const double *w;
    int axis;
    Prior1Dyn(const double *T_, const double *w_, int axis_) : T(T_), w(w_), axis(axis_) {}
    template <typename S>
    bool operator()(const S *const P, S *r) const
    {
        S sw = S(std::sqrt(std::max(0.0, *w)));
        r[0] = sw * (P[axis] - S(T[axis]));
        return true;
    }
};

// No z < 0 (hinge)
struct NoZNegativeW
{
    double s;
    explicit NoZNegativeW(double w) : s(std::sqrt(std::max(0.0, w))) {}
    template <typename T>
    bool operator()(const T *const P, T *r) const
    {
        // residual = s * max(0, -z)
        r[0] = T(s) * fmax(T(0), -P[2]);
        return true;
    }
};

// ============================================================
// Context / caching
// ============================================================
struct PlaneVar
{
    double O[3];
    double n[3];
    double d = 0.0; // PLANAR_HESSE offset: the plane is { x : x . n == d }
};

struct GroupCost
{
    int opcode = -1;
    std::vector<ceres::ResidualBlockId> blocks;
};

struct SolverContext
{
    std::unique_ptr<ceres::Problem> problem;
    int num_pts = 0;
    uint32_t hash = 0;

    std::vector<double> pts;          // 3*N, parameter blocks
    std::vector<double> lock_target;  // 3*N, captured on build()
    std::vector<double> prior_target; // 3*N, refreshed each solve()

    std::vector<double> prior_wx, prior_wy, prior_wz; // N
    std::vector<uint8_t> prior_mask;                  // N bitmask X=1,Y=2,Z=4

    std::vector<PlaneVar> planes;  // for PLANAR constraints
    std::vector<GroupCost> groups; // for per-group metrics

    robust::Choice loss;      // set by SET_LOSS; Huber(1e-3) if the program does not say
    std::string build_error;  // empty when the whole program parsed

    SolverContext() = default;

    void build(int Npts, const double *points_in,
               const double *program, int prog_len);

    ~SolverContext() = default;
};

static std::unique_ptr<SolverContext> CTX;

// ============================================================
// Build: parse program and create Ceres Problem
// ============================================================
void SolverContext::build(int Npts, const double *points_in,
                          const double *program, int prog_len)
{
    problem = std::make_unique<ceres::Problem>();
    num_pts = Npts;
    groups.clear();
    build_error.clear();
    loss = robust::Choice{};

    pts.assign(3 * Npts, 0.0);
    lock_target.assign(3 * Npts, 0.0);
    prior_target.assign(3 * Npts, 0.0);
    prior_wx.assign(Npts, 1e-6);
    prior_wy.assign(Npts, 1e-6);
    prior_wz.assign(Npts, 1e-6);
    prior_mask.assign(Npts, 0);
    planes.clear();

    for (int i = 0; i < 3 * Npts; ++i)
    {
        pts[i] = points_in[i];
        lock_target[i] = points_in[i];
        prior_target[i] = points_in[i];
    }

    for (int i = 0; i < Npts; ++i)
        problem->AddParameterBlock(&pts[3 * i], 3);

    // Pre-count PLANAR to keep PlaneVar addresses stable
    int ip = 0, planar_count = 0;
    while (ip < prog_len)
    {
        int opcode = (int)program[ip++];
        switch (opcode)
        {
        case op::SET_LOSS:
        {
            // Applied here, in the counting pass, so it governs every residual the build pass adds
            // no matter where in the program it appears. Program-level, not positional.
            if (ip + 2 > prog_len)
            {
                ip = prog_len;
                break;
            }
            loss.kind = (int)program[ip++];
            loss.scale = program[ip++];
            if (!(loss.scale > 0.0))
                loss.scale = 1e-3;
        }
        break;
        case op::PLANAR:
        case op::PLANAR_HESSE:
        {
            // Both allocate a PlaneVar, and planes.reserve() below is what keeps those addresses
            // stable while the build pass hands pointers into the vector to Ceres.
            if (ip >= prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            ip += n + 1; // idx + weight
            ++planar_count;
        }
        break;
        case op::ON_LINE:
        {
            if (ip >= prog_len)
            {
                ip = prog_len;
                break;
            }
            int m = (int)program[ip++];
            ip += m + 1;
        }
        break;
        case op::DISTANCE:
            ip += 4;
            break; // D,i,j,w
        case op::SAME_X:
        case op::SAME_Y:
        case op::SAME_Z:
        case op::SAME_XY:
        case op::SAME_XZ:
        case op::SAME_YZ:
        {
            if (ip >= prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            ip += n + 1;
        }
        break;
        case op::PERP_AT_ORIGIN:
            ip += 4;
            break; // o,a,b,w
        case op::LOCK_X:
        case op::LOCK_Y:
        case op::LOCK_Z:
        case op::LOCK_XY:
        case op::LOCK_XZ:
        case op::LOCK_YZ:
        {
            if (ip >= prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            ip += n + 1;
        }
        break;
        case op::PRIOR_3:
        case op::PRIOR_X:
        case op::PRIOR_Y:
        case op::PRIOR_Z:
        {
            if (ip >= prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            ip += n + 1;
        }
        break;
        case op::MIN_DISTANCE:
        case op::MAX_DISTANCE:
            ip += 4;
            break; // D, i, j, w
        case op::EQUAL_DISTANCE:
            ip += 5;
            break; // a,b,c,d,w
        case op::NO_Z_NEG:
        {
            if (ip >= prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            ip += n + 1;
        }
        break;
        case op::DOT_AT_ORIGIN:
            ip += 6;
            break;
        case op::VECTOR_DOT:
            ip += 7;
            break;
        case op::POINT_ON_PLANE:
            ip += 6;
            break;
        case op::LINE_PARALLEL:
            ip += 5;
            break;
        case op::LINE_ANGLE:
            ip += 6;
            break;
        case op::DIHEDRAL_ANGLE:
            ip += 6;
            break;
        case op::EQUAL_EDGE_VECTOR:
            ip += 5;
            break;
        case op::MIRROR_ACROSS_PLANE:
            ip += 6;
            break;
        case op::PANEL_AREA:
            ip += 6;
            break;
        case op::POINT_IN_HALFSPACE:
            ip += 6;
            break;
        case op::LINE_DIRECTION:
            ip += 6;
            break;
        case op::PLANE_NORMAL_DIRECTION:
            ip += 8;
            break;
        case op::LINE_TOWARD_POINT:
            ip += 4;
            break;
        default:
            // Not interesting to this pass, but its operands still have to be stepped over.
            {
                int span = operand_span(opcode, program, ip, prog_len);
                ip = (span < 0) ? prog_len : ip + span;
            }
            break;
        }
    }
    planes.reserve(planar_count);

    // --- First pass: add geometric & prior residuals ---
    ip = 0;
    while (ip < prog_len)
    {
        int opcode = (int)program[ip++];
        switch (opcode)
        {

        case op::PLANAR:
        {
            if (ip + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            if (n < 3 || ip + n + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            std::vector<int> idx(n);
            for (int k = 0; k < n; ++k)
                idx[k] = (int)program[ip++];
            double w = program[ip++];

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::PLANAR;

            planes.push_back(PlaneVar{});
            PlaneVar &pv = planes.back();

            // Regress plane from current points
            Eigen::Vector3d c(0, 0, 0);
            for (int id : idx)
                if (id >= 0 && id < Npts)
                    c += Eigen::Vector3d(pts[3 * id + 0], pts[3 * id + 1], pts[3 * id + 2]);
            if (n > 0)
                c /= double(n);

            Eigen::Matrix3d C = Eigen::Matrix3d::Zero();
            for (int id : idx)
                if (id >= 0 && id < Npts)
                {
                    Eigen::Vector3d v(pts[3 * id + 0] - c.x(), pts[3 * id + 1] - c.y(), pts[3 * id + 2] - c.z());
                    C += v * v.transpose();
                }
            Eigen::SelfAdjointEigenSolver<Eigen::Matrix3d> es(C);
            Eigen::Vector3d nml(1, 0, 0);
            if (es.info() == Eigen::Success)
                nml = es.eigenvectors().col(0);
            if (nml.norm() < 1e-12)
                nml = Eigen::Vector3d(1, 0, 0);
            nml.normalize();

            pv.O[0] = c.x();
            pv.O[1] = c.y();
            pv.O[2] = c.z();
            pv.n[0] = nml.x();
            pv.n[1] = nml.y();
            pv.n[2] = nml.z();

            // register plane parameters
            problem->AddParameterBlock(pv.n, 3, new ceres::SphereManifold<3>());
            problem->AddParameterBlock(pv.O, 3);

            // point-on-plane residuals with robust loss
            for (int id : idx)
                if (id >= 0 && id < Npts)
                {
                    auto *cost =
                        new ceres::AutoDiffCostFunction<PointOnPlaneW, 1, 3, 3, 3>(
                            new PointOnPlaneW(w));
                    ceres::LossFunction *L = loss.make();
                    ceres::ResidualBlockId rid =
                        problem->AddResidualBlock(cost, L, &pts[3 * id], pv.O, pv.n);
                    G.blocks.push_back(rid);
                }

            // tiny stabilizers on plane -- marc: not 100% sure about this

            static const double wO = 1e-6, wN = 1e-6;
            if (wO > 0)
            {
                double O0[3] = {pv.O[0], pv.O[1], pv.O[2]};
                problem->AddResidualBlock(
                    new ceres::AutoDiffCostFunction<OriginPriorW, 3, 3>(new OriginPriorW(O0, wO)),
                    nullptr, pv.O);
            }
            if (wN > 0)
            {
                double N0[3] = {pv.n[0], pv.n[1], pv.n[2]};
                problem->AddResidualBlock(
                    new ceres::AutoDiffCostFunction<NormalAlignW, 1, 3>(new NormalAlignW(N0, wN)),
                    nullptr, pv.n);
            }
        }
        break;

        case op::ON_LINE:
        {
            if (ip + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int m = (int)program[ip++];
            if (m < 2 || ip + m + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int a = (int)program[ip++], b = (int)program[ip++];
            std::vector<int> rest;
            for (int k = 2; k < m; ++k)
                rest.push_back((int)program[ip++]);
            double w = program[ip++];

            if (a >= 0 && a < Npts && b >= 0 && b < Npts)
            {
                groups.push_back(GroupCost{});
                GroupCost &G = groups.back();
                G.opcode = op::ON_LINE;
                for (int p : rest)
                    if (p >= 0 && p < Npts)
                    {
                        auto *cost = new ceres::AutoDiffCostFunction<PointOnLineW, 3, 3, 3, 3>(new PointOnLineW(w));
                        ceres::LossFunction *L = loss.make();
                        ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * p], &pts[3 * a], &pts[3 * b]);
                        G.blocks.push_back(rid);
                    }
            }
        }
        break;

        case op::DISTANCE:
        {
            if (ip + 4 > prog_len)
            {
                ip = prog_len;
                break;
            }
            double D = program[ip++];
            int i0 = (int)program[ip++], i1 = (int)program[ip++];
            double w = program[ip++];
            if (i0 >= 0 && i0 < Npts && i1 >= 0 && i1 < Npts)
            {
                groups.push_back(GroupCost{});
                GroupCost &G = groups.back();
                G.opcode = op::DISTANCE;
                auto *cost = new ceres::AutoDiffCostFunction<DistanceW, 1, 3, 3>(new DistanceW(D, w));
                ceres::LossFunction *L = loss.make();
                ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * i0], &pts[3 * i1]);
                G.blocks.push_back(rid);
            }
        }
        break;

        case op::SAME_X:
        case op::SAME_Y:
        case op::SAME_Z:
        case op::SAME_XY:
        case op::SAME_XZ:
        case op::SAME_YZ:
        {
            if (ip + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            if (n < 2 || ip + n + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            std::vector<int> g(n);
            for (int k = 0; k < n; ++k)
                g[k] = (int)program[ip++];
            double w = program[ip++];

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = opcode;

            for (int k = 1; k < n; ++k)
            {
                int a = g[k], b = g[0];
                if (a < 0 || a >= Npts || b < 0 || b >= Npts)
                    continue;
                ceres::CostFunction *cost = nullptr;
                int resdim = 1;
                switch (opcode)
                {
                case op::SAME_X:
                    cost = new ceres::AutoDiffCostFunction<SameXW, 1, 3, 3>(new SameXW(w));
                    break;
                case op::SAME_Y:
                    cost = new ceres::AutoDiffCostFunction<SameYW, 1, 3, 3>(new SameYW(w));
                    break;
                case op::SAME_Z:
                    cost = new ceres::AutoDiffCostFunction<SameZW, 1, 3, 3>(new SameZW(w));
                    break;
                case op::SAME_XY:
                    cost = new ceres::AutoDiffCostFunction<SameXYW, 2, 3, 3>(new SameXYW(w));
                    resdim = 2;
                    break;
                case op::SAME_XZ:
                    cost = new ceres::AutoDiffCostFunction<SameXZW, 2, 3, 3>(new SameXZW(w));
                    resdim = 2;
                    break;
                case op::SAME_YZ:
                    cost = new ceres::AutoDiffCostFunction<SameYZW, 2, 3, 3>(new SameYZW(w));
                    resdim = 2;
                    break;
                }
                ceres::LossFunction *L = loss.make();
                ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * a], &pts[3 * b]);
                G.blocks.push_back(rid);
            }
        }
        break;

        case op::PERP_AT_ORIGIN:
        {
            if (ip + 4 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int o = (int)program[ip++], a = (int)program[ip++], b = (int)program[ip++];
            double w = program[ip++];
            auto in_range = [&](int p)
            { return p >= 0 && p < Npts; };
            if (!in_range(o) || !in_range(a) || !in_range(b) || o == a || o == b || a == b)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::PERP_AT_ORIGIN;
            auto *cost = new ceres::AutoDiffCostFunction<PerpAtOriginW, 1, 3, 3, 3>(new PerpAtOriginW(w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * o], &pts[3 * a], &pts[3 * b]);
            G.blocks.push_back(rid);
        }
        break;

        // LOCKS are deferred to second pass
        case op::LOCK_X:
        case op::LOCK_Y:
        case op::LOCK_Z:
        case op::LOCK_XY:
        case op::LOCK_XZ:
        case op::LOCK_YZ:
        {
            if (ip + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            ip += n + 1; // skip here; added in second pass
        }
        break;

        // PRIORS — install dynamic residuals now
        case op::PRIOR_3:
        case op::PRIOR_X:
        case op::PRIOR_Y:
        case op::PRIOR_Z:
        {
            if (ip + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            if (n < 1 || ip + n + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }

            std::vector<int> g;
            g.reserve(n);
            bool all = false;
            for (int k = 0; k < n; ++k)
            {
                int id = (int)program[ip++];
                if (opcode == op::PRIOR_3 && id == -1)
                {
                    all = true;
                    break;
                }
                g.push_back(id);
            }
            double w = program[ip++];

            if (all)
            {
                g.clear();
                for (int i = 0; i < Npts; ++i)
                    g.push_back(i);
            }

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = opcode;

            for (int id : g)
            {
                if (id < 0 || id >= Npts)
                    continue;
                switch (opcode)
                {
                case op::PRIOR_3:
                {
                    prior_wx[id] = prior_wy[id] = prior_wz[id] = w;
                    if ((prior_mask[id] & 0x07) != 0x07)
                    {
                        auto *cost = new ceres::AutoDiffCostFunction<Prior3Dyn, 3, 3>(
                            new Prior3Dyn(&prior_target[3 * id], &prior_wx[id], &prior_wy[id], &prior_wz[id]));
                        ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, nullptr, &pts[3 * id]);
                        G.blocks.push_back(rid);
                        prior_mask[id] |= 0x07;
                    }
                }
                break;
                case op::PRIOR_X:
                {
                    prior_wx[id] = w;
                    if (!(prior_mask[id] & 0x01))
                    {
                        auto *cost = new ceres::AutoDiffCostFunction<Prior1Dyn, 1, 3>(
                            new Prior1Dyn(&prior_target[3 * id], &prior_wx[id], 0));
                        ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, nullptr, &pts[3 * id]);
                        G.blocks.push_back(rid);
                        prior_mask[id] |= 0x01;
                    }
                }
                break;
                case op::PRIOR_Y:
                {
                    prior_wy[id] = w;
                    if (!(prior_mask[id] & 0x02))
                    {
                        auto *cost = new ceres::AutoDiffCostFunction<Prior1Dyn, 1, 3>(
                            new Prior1Dyn(&prior_target[3 * id], &prior_wy[id], 1));
                        ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, nullptr, &pts[3 * id]);
                        G.blocks.push_back(rid);
                        prior_mask[id] |= 0x02;
                    }
                }
                break;
                case op::PRIOR_Z:
                {
                    prior_wz[id] = w;
                    if (!(prior_mask[id] & 0x04))
                    {
                        auto *cost = new ceres::AutoDiffCostFunction<Prior1Dyn, 1, 3>(
                            new Prior1Dyn(&prior_target[3 * id], &prior_wz[id], 2));
                        ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, nullptr, &pts[3 * id]);
                        G.blocks.push_back(rid);
                        prior_mask[id] |= 0x04;
                    }
                }
                break;
                }
            }
        }
        break;

        case op::MIN_DISTANCE:
        {
            if (ip + 4 > prog_len)
            {
                ip = prog_len;
                break;
            }
            double Dmin = program[ip++];
            int i = (int)program[ip++], j = (int)program[ip++];
            double w = program[ip++];
            if (i >= 0 && i < Npts && j >= 0 && j < Npts && i != j)
            {
                groups.push_back(GroupCost{});
                GroupCost &G = groups.back();
                G.opcode = op::MIN_DISTANCE;
                auto *cost = new ceres::AutoDiffCostFunction<MinDistanceW, 1, 3, 3>(new MinDistanceW(Dmin, w));
                ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, nullptr, &pts[3 * i], &pts[3 * j]);
                G.blocks.push_back(rid);
            }
        }
        break;

        case op::MAX_DISTANCE:
        {
            if (ip + 4 > prog_len)
            {
                ip = prog_len;
                break;
            }
            double Dmax = program[ip++];
            int i = (int)program[ip++], j = (int)program[ip++];
            double w = program[ip++];
            if (i >= 0 && i < Npts && j >= 0 && j < Npts && i != j)
            {
                groups.push_back(GroupCost{});
                GroupCost &G = groups.back();
                G.opcode = op::MAX_DISTANCE;
                auto *cost = new ceres::AutoDiffCostFunction<MaxDistanceW, 1, 3, 3>(new MaxDistanceW(Dmax, w));
                ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, nullptr, &pts[3 * i], &pts[3 * j]);
                G.blocks.push_back(rid);
            }
        }
        break;

        case op::EQUAL_DISTANCE:
        {
            if (ip + 5 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int a = (int)program[ip++], b = (int)program[ip++], c = (int)program[ip++], d = (int)program[ip++];
            double w = program[ip++];
            if (a >= 0 && a < Npts && b >= 0 && b < Npts && c >= 0 && c < Npts && d >= 0 && d < Npts && a != b && c != d)
            {
                groups.push_back(GroupCost{});
                GroupCost &G = groups.back();
                G.opcode = op::EQUAL_DISTANCE;
                auto *cost = new ceres::AutoDiffCostFunction<EqualDistanceW, 1, 3, 3, 3, 3>(new EqualDistanceW(w));
                ceres::LossFunction *L = loss.make();
                ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * a], &pts[3 * b], &pts[3 * c], &pts[3 * d]);
                G.blocks.push_back(rid);
            }
        }
        break;

        case op::NO_Z_NEG:
        {
            if (ip + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            if (n < 1 || ip + n + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }

            std::vector<int> g;
            g.reserve(n);
            bool all = false;
            for (int k = 0; k < n; ++k)
            {
                int id = (int)program[ip++];
                if (id == -1)
                {
                    all = true;
                    break;
                }
                g.push_back(id);
            }
            double w = program[ip++];

            if (all)
            {
                g.clear();
                for (int i = 0; i < Npts; ++i)
                    g.push_back(i);
            }

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::NO_Z_NEG;

            for (int id : g)
                if (id >= 0 && id < Npts)
                {
                    auto *cost = new ceres::AutoDiffCostFunction<NoZNegativeW, 1, 3>(new NoZNegativeW(w));
                    ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, nullptr, &pts[3 * id]);
                    G.blocks.push_back(rid);
                }
        }
        break;

        case op::DOT_AT_ORIGIN:
        {
            if (ip + 6 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int o = (int)program[ip++], a = (int)program[ip++], b = (int)program[ip++];
            double target = program[ip++];
            double normalized = program[ip++];
            double w = program[ip++];
            auto in_range = [&](int p)
            { return p >= 0 && p < Npts; };
            if (!in_range(o) || !in_range(a) || !in_range(b) || o == a || o == b || a == b)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::DOT_AT_ORIGIN;
            auto *cost = new ceres::AutoDiffCostFunction<DotAtOriginW, 1, 3, 3, 3>(
                new DotAtOriginW(target, normalized, w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * o], &pts[3 * a], &pts[3 * b]);
            G.blocks.push_back(rid);
        }
        break;

        case op::VECTOR_DOT:
        {
            if (ip + 7 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int a0 = (int)program[ip++], a1 = (int)program[ip++], b0 = (int)program[ip++], b1 = (int)program[ip++];
            double target = program[ip++];
            double normalized = program[ip++];
            double w = program[ip++];
            auto in_range = [&](int p)
            { return p >= 0 && p < Npts; };
            if (!in_range(a0) || !in_range(a1) || !in_range(b0) || !in_range(b1) ||
                a0 == a1 || b0 == b1 || a0 == b0 || a0 == b1 || a1 == b0 || a1 == b1)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::VECTOR_DOT;
            auto *cost = new ceres::AutoDiffCostFunction<VectorDotW, 1, 3, 3, 3, 3>(
                new VectorDotW(target, normalized, w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * a0], &pts[3 * a1], &pts[3 * b0], &pts[3 * b1]);
            G.blocks.push_back(rid);
        }
        break;

        case op::POINT_ON_PLANE:
        {
            if (ip + 6 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int p = (int)program[ip++], a = (int)program[ip++], b = (int)program[ip++], c = (int)program[ip++];
            double offset = program[ip++];
            double w = program[ip++];
            auto in_range = [&](int id)
            { return id >= 0 && id < Npts; };
            if (!in_range(p) || !in_range(a) || !in_range(b) || !in_range(c) ||
                p == a || p == b || p == c || a == b || a == c || b == c)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::POINT_ON_PLANE;
            auto *cost = new ceres::AutoDiffCostFunction<PointOnPlane3W, 1, 3, 3, 3, 3>(
                new PointOnPlane3W(offset, w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * p], &pts[3 * a], &pts[3 * b], &pts[3 * c]);
            G.blocks.push_back(rid);
        }
        break;

        case op::LINE_PARALLEL:
        {
            if (ip + 5 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int a0 = (int)program[ip++], a1 = (int)program[ip++], b0 = (int)program[ip++], b1 = (int)program[ip++];
            double w = program[ip++];
            auto in_range = [&](int id)
            { return id >= 0 && id < Npts; };
            if (!in_range(a0) || !in_range(a1) || !in_range(b0) || !in_range(b1) ||
                a0 == a1 || b0 == b1 || a0 == b0 || a0 == b1 || a1 == b0 || a1 == b1)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::LINE_PARALLEL;
            auto *cost = new ceres::AutoDiffCostFunction<LineParallelW, 3, 3, 3, 3, 3>(
                new LineParallelW(w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * a0], &pts[3 * a1], &pts[3 * b0], &pts[3 * b1]);
            G.blocks.push_back(rid);
        }
        break;

        case op::LINE_ANGLE:
        {
            if (ip + 6 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int a0 = (int)program[ip++], a1 = (int)program[ip++], b0 = (int)program[ip++], b1 = (int)program[ip++];
            double target = program[ip++];
            double w = program[ip++];
            auto in_range = [&](int id)
            { return id >= 0 && id < Npts; };
            if (!in_range(a0) || !in_range(a1) || !in_range(b0) || !in_range(b1) ||
                a0 == a1 || b0 == b1 || a0 == b0 || a0 == b1 || a1 == b0 || a1 == b1)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::LINE_ANGLE;
            auto *cost = new ceres::AutoDiffCostFunction<LineAngleW, 1, 3, 3, 3, 3>(
                new LineAngleW(target, w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * a0], &pts[3 * a1], &pts[3 * b0], &pts[3 * b1]);
            G.blocks.push_back(rid);
        }
        break;

        case op::DIHEDRAL_ANGLE:
        {
            if (ip + 6 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int a = (int)program[ip++], b = (int)program[ip++], c = (int)program[ip++], d = (int)program[ip++];
            double target = program[ip++];
            double w = program[ip++];
            auto in_range = [&](int id)
            { return id >= 0 && id < Npts; };
            if (!in_range(a) || !in_range(b) || !in_range(c) || !in_range(d) ||
                a == b || a == c || a == d || b == c || b == d || c == d)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::DIHEDRAL_ANGLE;
            auto *cost = new ceres::AutoDiffCostFunction<DihedralAngleW, 1, 3, 3, 3, 3>(
                new DihedralAngleW(target, w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * a], &pts[3 * b], &pts[3 * c], &pts[3 * d]);
            G.blocks.push_back(rid);
        }
        break;

        case op::EQUAL_EDGE_VECTOR:
        {
            if (ip + 5 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int a = (int)program[ip++], b = (int)program[ip++], c = (int)program[ip++], d = (int)program[ip++];
            double w = program[ip++];
            auto in_range = [&](int id)
            { return id >= 0 && id < Npts; };
            if (!in_range(a) || !in_range(b) || !in_range(c) || !in_range(d) ||
                a == b || c == d || a == c || a == d || b == c || b == d)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::EQUAL_EDGE_VECTOR;
            auto *cost = new ceres::AutoDiffCostFunction<EqualEdgeVectorW, 3, 3, 3, 3, 3>(
                new EqualEdgeVectorW(w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * a], &pts[3 * b], &pts[3 * c], &pts[3 * d]);
            G.blocks.push_back(rid);
        }
        break;

        case op::MIRROR_ACROSS_PLANE:
        {
            if (ip + 6 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int p = (int)program[ip++], q = (int)program[ip++], a = (int)program[ip++], b = (int)program[ip++], c = (int)program[ip++];
            double w = program[ip++];
            auto in_range = [&](int id)
            { return id >= 0 && id < Npts; };
            if (!in_range(p) || !in_range(q) || !in_range(a) || !in_range(b) || !in_range(c) ||
                p == q || a == b || a == c || b == c ||
                p == a || p == b || p == c || q == a || q == b || q == c)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::MIRROR_ACROSS_PLANE;
            auto *cost = new ceres::AutoDiffCostFunction<MirrorAcrossPlaneW, 4, 3, 3, 3, 3, 3>(
                new MirrorAcrossPlaneW(w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * p], &pts[3 * q], &pts[3 * a], &pts[3 * b], &pts[3 * c]);
            G.blocks.push_back(rid);
        }
        break;

        case op::PANEL_AREA:
        {
            if (ip + 6 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int a = (int)program[ip++], b = (int)program[ip++], c = (int)program[ip++], d = (int)program[ip++];
            double target = program[ip++];
            double w = program[ip++];
            auto in_range = [&](int id)
            { return id >= 0 && id < Npts; };
            if (!in_range(a) || !in_range(b) || !in_range(c) || !in_range(d) ||
                a == b || a == c || a == d || b == c || b == d || c == d)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::PANEL_AREA;
            auto *cost = new ceres::AutoDiffCostFunction<PanelAreaW, 1, 3, 3, 3, 3>(
                new PanelAreaW(target, w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * a], &pts[3 * b], &pts[3 * c], &pts[3 * d]);
            G.blocks.push_back(rid);
        }
        break;

        case op::POINT_IN_HALFSPACE:
        {
            if (ip + 6 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int p = (int)program[ip++], a = (int)program[ip++], b = (int)program[ip++], c = (int)program[ip++];
            double min_signed_distance = program[ip++];
            double w = program[ip++];
            auto in_range = [&](int id)
            { return id >= 0 && id < Npts; };
            if (!in_range(p) || !in_range(a) || !in_range(b) || !in_range(c) ||
                p == a || p == b || p == c || a == b || a == c || b == c)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::POINT_IN_HALFSPACE;
            auto *cost = new ceres::AutoDiffCostFunction<PointInHalfspaceW, 1, 3, 3, 3, 3>(
                new PointInHalfspaceW(min_signed_distance, w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * p], &pts[3 * a], &pts[3 * b], &pts[3 * c]);
            G.blocks.push_back(rid);
        }
        break;

        case op::LINE_DIRECTION:
        {
            if (ip + 6 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int a = (int)program[ip++], b = (int)program[ip++];
            double dx = program[ip++], dy = program[ip++], dz = program[ip++];
            double w = program[ip++];
            auto in_range = [&](int id)
            { return id >= 0 && id < Npts; };
            if (!in_range(a) || !in_range(b) || a == b || dx * dx + dy * dy + dz * dz < 1e-20)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::LINE_DIRECTION;
            auto *cost = new ceres::AutoDiffCostFunction<LineDirectionW, 3, 3, 3>(
                new LineDirectionW(dx, dy, dz, w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * a], &pts[3 * b]);
            G.blocks.push_back(rid);
        }
        break;

        case op::PLANE_NORMAL_DIRECTION:
        {
            if (ip + 8 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int a = (int)program[ip++], b = (int)program[ip++], c = (int)program[ip++];
            double nx = program[ip++], ny = program[ip++], nz = program[ip++];
            double target = program[ip++];
            double w = program[ip++];
            auto in_range = [&](int id)
            { return id >= 0 && id < Npts; };
            if (!in_range(a) || !in_range(b) || !in_range(c) ||
                a == b || a == c || b == c || nx * nx + ny * ny + nz * nz < 1e-20)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::PLANE_NORMAL_DIRECTION;
            auto *cost = new ceres::AutoDiffCostFunction<PlaneNormalDirectionW, 1, 3, 3, 3>(
                new PlaneNormalDirectionW(nx, ny, nz, target, w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * a], &pts[3 * b], &pts[3 * c]);
            G.blocks.push_back(rid);
        }
        break;

        case op::LINE_TOWARD_POINT:
        {
            if (ip + 4 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int a = (int)program[ip++], b = (int)program[ip++], vp = (int)program[ip++];
            double w = program[ip++];
            auto in_range = [&](int id)
            { return id >= 0 && id < Npts; };
            if (!in_range(a) || !in_range(b) || !in_range(vp) || a == b || a == vp || b == vp)
                break;

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::LINE_TOWARD_POINT;
            auto *cost = new ceres::AutoDiffCostFunction<PointOnLineW, 3, 3, 3, 3>(
                new PointOnLineW(w));
            ceres::LossFunction *L = loss.make();
            ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, L, &pts[3 * vp], &pts[3 * a], &pts[3 * b]);
            G.blocks.push_back(rid);
        }
        break;

        case op::SET_LOSS:
            // Already consumed in the counting pass; just step over it.
            ip += 2;
            break;

        case op::PLANAR_HESSE:
        {
            if (ip >= prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            if (n < 3 || ip + n + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            std::vector<int> idx(n);
            for (int k = 0; k < n; ++k)
                idx[k] = (int)program[ip++];
            double w = program[ip++];

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = op::PLANAR_HESSE;

            planes.push_back(PlaneVar{});
            PlaneVar &pv = planes.back();

            // Seed from the current points, exactly as PLANAR does: centroid plus the smallest
            // eigenvector of the scatter matrix.
            Eigen::Vector3d c(0, 0, 0);
            int seen = 0;
            for (int id : idx)
                if (id >= 0 && id < Npts)
                {
                    c += Eigen::Vector3d(pts[3 * id + 0], pts[3 * id + 1], pts[3 * id + 2]);
                    ++seen;
                }
            if (seen > 0)
                c /= double(seen);

            Eigen::Matrix3d C = Eigen::Matrix3d::Zero();
            for (int id : idx)
                if (id >= 0 && id < Npts)
                {
                    Eigen::Vector3d v(pts[3 * id + 0] - c.x(), pts[3 * id + 1] - c.y(), pts[3 * id + 2] - c.z());
                    C += v * v.transpose();
                }
            Eigen::SelfAdjointEigenSolver<Eigen::Matrix3d> es(C);
            Eigen::Vector3d nml(1, 0, 0);
            if (es.info() == Eigen::Success)
                nml = es.eigenvectors().col(0);
            if (nml.norm() < 1e-12)
                nml = Eigen::Vector3d(1, 0, 0);
            nml.normalize();

            pv.n[0] = nml.x();
            pv.n[1] = nml.y();
            pv.n[2] = nml.z();
            pv.d = nml.dot(c);

            problem->AddParameterBlock(pv.n, 3, new ceres::SphereManifold<3>());
            problem->AddParameterBlock(&pv.d, 1);

            // No stabilisers. Unlike PLANAR there is nothing to stabilise: (n, d) has no null
            // direction, so the plane is fully determined by the residuals that use it.
            for (int id : idx)
                if (id >= 0 && id < Npts)
                {
                    auto *cost = new ceres::AutoDiffCostFunction<PointOnPlaneHesseW, 1, 3, 3, 1>(
                        new PointOnPlaneHesseW(w));
                    ceres::ResidualBlockId rid =
                        problem->AddResidualBlock(cost, loss.make(), &pts[3 * id], pv.n, &pv.d);
                    G.blocks.push_back(rid);
                }
        }
        break;

        case op::PLANAR_FIT:
        {
            if (ip >= prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            if (n < 3 || ip + n + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            std::vector<int> idx;
            for (int k = 0; k < n; ++k)
            {
                int id = (int)program[ip++];
                if (id >= 0 && id < Npts)
                    idx.push_back(id);
            }
            double w = program[ip++];

            // The Newell fit reads each corner in cycle order, so a repeated vertex would need the
            // same parameter block twice and Ceres forbids that. Such a face is degenerate anyway.
            std::vector<int> sorted = idx;
            std::sort(sorted.begin(), sorted.end());
            bool repeats = std::adjacent_find(sorted.begin(), sorted.end()) != sorted.end();
            if ((int)idx.size() >= 3 && !repeats)
            {
                groups.push_back(GroupCost{});
                GroupCost &G = groups.back();
                G.opcode = op::PLANAR_FIT;

                auto *functor = new PlanarFitW((int)idx.size(), w);
                auto *cost = new ceres::DynamicAutoDiffCostFunction<PlanarFitW>(functor);
                std::vector<double *> blocks;
                for (int id : idx)
                {
                    cost->AddParameterBlock(3);
                    blocks.push_back(&pts[3 * id]);
                }
                cost->SetNumResiduals((int)idx.size());
                ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, loss.make(), blocks);
                G.blocks.push_back(rid);
            }
        }
        break;

        case op::GAUGE_ROTATION:
        {
            if (ip >= prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            std::vector<int> idx;
            if (n < 0)
            {
                if (ip + 1 > prog_len)
                {
                    ip = prog_len;
                    break;
                }
                idx.resize(Npts);
                for (int i = 0; i < Npts; ++i)
                    idx[i] = i;
            }
            else
            {
                if (ip + n + 1 > prog_len)
                {
                    ip = prog_len;
                    break;
                }
                for (int k = 0; k < n; ++k)
                {
                    int id = (int)program[ip++];
                    if (id >= 0 && id < Npts)
                        idx.push_back(id);
                }
            }
            double w = program[ip++];

            std::sort(idx.begin(), idx.end());
            idx.erase(std::unique(idx.begin(), idx.end()), idx.end());
            if (!idx.empty())
            {
                groups.push_back(GroupCost{});
                GroupCost &G = groups.back();
                G.opcode = op::GAUGE_ROTATION;

                // The reference pose is the one the problem was built with, which is the pose the
                // caller handed to ken_ceres_solver_create.
                std::vector<double> ref;
                ref.reserve(3 * idx.size());
                for (int id : idx)
                {
                    ref.push_back(points_in[3 * id]);
                    ref.push_back(points_in[3 * id + 1]);
                    ref.push_back(points_in[3 * id + 2]);
                }
                auto *functor = new GaugeRotationW(std::move(ref), w);
                auto *cost = new ceres::DynamicAutoDiffCostFunction<GaugeRotationW>(functor);
                std::vector<double *> blocks;
                for (int id : idx)
                {
                    cost->AddParameterBlock(3);
                    blocks.push_back(&pts[3 * id]);
                }
                cost->SetNumResiduals(3);
                ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, loss.make(), blocks);
                G.blocks.push_back(rid);
            }
        }
        break;

        case op::EDGE_TANGENCY:
        {
            if (ip + 4 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int a = (int)program[ip++], b = (int)program[ip++];
            double r = program[ip++];
            double w = program[ip++];
            if (a >= 0 && a < Npts && b >= 0 && b < Npts && a != b)
            {
                groups.push_back(GroupCost{});
                GroupCost &G = groups.back();
                G.opcode = op::EDGE_TANGENCY;
                auto *cost = new ceres::AutoDiffCostFunction<EdgeTangencyW, 1, 3, 3>(
                    new EdgeTangencyW(r, w));
                ceres::ResidualBlockId rid =
                    problem->AddResidualBlock(cost, loss.make(), &pts[3 * a], &pts[3 * b]);
                G.blocks.push_back(rid);
            }
        }
        break;

        case op::TANGENCY_CENTROID:
        {
            if (ip >= prog_len)
            {
                ip = prog_len;
                break;
            }
            int nEdges = (int)program[ip++];
            if (nEdges < 1 || ip + 2 * nEdges + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            std::vector<int> ends(2 * nEdges);
            for (int k = 0; k < 2 * nEdges; ++k)
                ends[k] = (int)program[ip++];
            double w = program[ip++];

            // One parameter block per *distinct* endpoint: Ceres rejects a residual block that
            // names the same block twice, and shared vertices are the normal case here.
            std::vector<int> distinct;
            std::vector<int> slot(Npts, -1);
            auto slot_of = [&](int id) -> int
            {
                if (id < 0 || id >= Npts)
                    return -1;
                if (slot[id] < 0)
                {
                    slot[id] = (int)distinct.size();
                    distinct.push_back(id);
                }
                return slot[id];
            };
            std::vector<int> ea, eb;
            for (int e = 0; e < nEdges; ++e)
            {
                int sa = slot_of(ends[2 * e]), sb = slot_of(ends[2 * e + 1]);
                if (sa < 0 || sb < 0 || sa == sb)
                    continue;
                ea.push_back(sa);
                eb.push_back(sb);
            }
            if (!ea.empty())
            {
                groups.push_back(GroupCost{});
                GroupCost &G = groups.back();
                G.opcode = op::TANGENCY_CENTROID;
                auto *functor = new TangencyCentroidW(ea, eb, w);
                auto *cost = new ceres::DynamicAutoDiffCostFunction<TangencyCentroidW>(functor);
                std::vector<double *> blocks;
                for (int id : distinct)
                {
                    cost->AddParameterBlock(3);
                    blocks.push_back(&pts[3 * id]);
                }
                cost->SetNumResiduals(3);
                ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, loss.make(), blocks);
                G.blocks.push_back(rid);
            }
        }
        break;

        case op::CENTROID:
        {
            if (ip >= prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            std::vector<int> idx;
            if (n < 0)
            {
                if (ip + 4 > prog_len)
                {
                    ip = prog_len;
                    break;
                }
                idx.resize(Npts);
                for (int i = 0; i < Npts; ++i)
                    idx[i] = i;
            }
            else
            {
                if (ip + n + 4 > prog_len)
                {
                    ip = prog_len;
                    break;
                }
                for (int k = 0; k < n; ++k)
                {
                    int id = (int)program[ip++];
                    if (id >= 0 && id < Npts)
                        idx.push_back(id);
                }
            }
            double tx = program[ip++], ty = program[ip++], tz = program[ip++];
            double w = program[ip++];

            std::sort(idx.begin(), idx.end());
            idx.erase(std::unique(idx.begin(), idx.end()), idx.end());
            if (!idx.empty())
            {
                groups.push_back(GroupCost{});
                GroupCost &G = groups.back();
                G.opcode = op::CENTROID;
                auto *functor = new CentroidW(tx, ty, tz, (int)idx.size(), w);
                auto *cost = new ceres::DynamicAutoDiffCostFunction<CentroidW>(functor);
                std::vector<double *> blocks;
                for (int id : idx)
                {
                    cost->AddParameterBlock(3);
                    blocks.push_back(&pts[3 * id]);
                }
                cost->SetNumResiduals(3);
                ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, loss.make(), blocks);
                G.blocks.push_back(rid);
            }
        }
        break;

        case op::RADIUS_ABOUT_CENTROID:
        {
            if (ip >= prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            std::vector<int> idx;
            if (n < 0)
            {
                if (ip + 2 > prog_len)
                {
                    ip = prog_len;
                    break;
                }
                idx.resize(Npts);
                for (int i = 0; i < Npts; ++i)
                    idx[i] = i;
            }
            else
            {
                if (ip + n + 2 > prog_len)
                {
                    ip = prog_len;
                    break;
                }
                for (int k = 0; k < n; ++k)
                {
                    int id = (int)program[ip++];
                    if (id >= 0 && id < Npts)
                        idx.push_back(id);
                }
            }
            double r = program[ip++];
            double w = program[ip++];

            std::sort(idx.begin(), idx.end());
            idx.erase(std::unique(idx.begin(), idx.end()), idx.end());
            if (!idx.empty())
            {
                groups.push_back(GroupCost{});
                GroupCost &G = groups.back();
                G.opcode = op::RADIUS_ABOUT_CENTROID;
                auto *functor = new RadiusAboutCentroidW(r, (int)idx.size(), w);
                auto *cost = new ceres::DynamicAutoDiffCostFunction<RadiusAboutCentroidW>(functor);
                std::vector<double *> blocks;
                for (int id : idx)
                {
                    cost->AddParameterBlock(3);
                    blocks.push_back(&pts[3 * id]);
                }
                cost->SetNumResiduals((int)idx.size());
                ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, loss.make(), blocks);
                G.blocks.push_back(rid);
            }
        }
        break;

        case op::EXPR:
        {
            // [nParams, p..., nResiduals, slot..., nSlots, nConsts, c..., nInstr, (op,dst,a,b)..., w]
            //
            // Validated hard here rather than in the evaluator: the evaluator runs once per
            // residual per Jet per iteration, and an out-of-range slot index there is an
            // out-of-bounds write in someone else's process.
            int span = operand_span(op::EXPR, program, ip, prog_len);
            if (span < 0)
            {
                build_error = "malformed EXPR at offset " + std::to_string(ip - 1);
                ip = prog_len;
                break;
            }
            const int end = ip + span;

            int nParams = (int)program[ip++];
            std::vector<int> params(nParams);
            for (int k = 0; k < nParams; ++k)
                params[k] = (int)program[ip++];

            int nResiduals = (int)program[ip++];
            std::vector<int> residualSlots(nResiduals);
            for (int k = 0; k < nResiduals; ++k)
                residualSlots[k] = (int)program[ip++];

            int nSlots = (int)program[ip++];

            int nConsts = (int)program[ip++];
            std::vector<double> constants(nConsts);
            for (int k = 0; k < nConsts; ++k)
                constants[k] = program[ip++];

            int nInstr = (int)program[ip++];
            std::vector<int> code(4 * nInstr);
            for (int k = 0; k < 4 * nInstr; ++k)
                code[k] = (int)program[ip++];

            double w = program[ip++];
            ip = end; // defensive: never let a miscount here desync the rest of the program

            auto fail = [&](const std::string &why)
            {
                build_error = "EXPR: " + why;
            };

            bool ok = true;
            if (nParams < 1 || nResiduals < 1 || nSlots < 1)
            {
                fail("needs at least one parameter block, one residual and one slot");
                ok = false;
            }
            // Ceres forbids naming the same parameter block twice in one residual block.
            std::vector<int> sortedParams = params;
            std::sort(sortedParams.begin(), sortedParams.end());
            if (ok && std::adjacent_find(sortedParams.begin(), sortedParams.end()) != sortedParams.end())
            {
                fail("the same point appears twice in one expression's parameter list");
                ok = false;
            }
            if (ok)
                for (int id : params)
                    if (id < 0 || id >= Npts)
                    {
                        fail("point index " + std::to_string(id) + " out of range");
                        ok = false;
                        break;
                    }
            if (ok)
                for (int slotIndex : residualSlots)
                    if (slotIndex < 0 || slotIndex >= nSlots)
                    {
                        fail("residual reads slot " + std::to_string(slotIndex) + " of " + std::to_string(nSlots));
                        ok = false;
                        break;
                    }
            if (ok)
                for (int k = 0; k < nInstr; ++k)
                {
                    const int o = code[4 * k], dst = code[4 * k + 1];
                    const int a = code[4 * k + 2], b = code[4 * k + 3];
                    if (o < expr::CONST || o > expr::RECIP)
                    {
                        fail("unknown expression op " + std::to_string(o) + " at instruction " + std::to_string(k));
                        ok = false;
                        break;
                    }
                    if (dst < 0 || dst >= nSlots)
                    {
                        fail("instruction " + std::to_string(k) + " writes slot " + std::to_string(dst));
                        ok = false;
                        break;
                    }
                    if (o == expr::CONST)
                    {
                        if (a < 0 || a >= nConsts)
                        {
                            fail("instruction " + std::to_string(k) + " reads constant " + std::to_string(a));
                            ok = false;
                            break;
                        }
                    }
                    else if (o == expr::PARAM)
                    {
                        if (a < 0 || a >= nParams || b < 0 || b > 2)
                        {
                            fail("instruction " + std::to_string(k) + " reads parameter " +
                                 std::to_string(a) + " component " + std::to_string(b));
                            ok = false;
                            break;
                        }
                    }
                    else
                    {
                        // Every remaining op reads slots. Binary ops read both; unary ops ignore b,
                        // and the encoder writes 0 there, which is always a valid slot.
                        if (a < 0 || a >= nSlots || b < 0 || b >= nSlots)
                        {
                            fail("instruction " + std::to_string(k) + " reads slots " +
                                 std::to_string(a) + "," + std::to_string(b) + " of " + std::to_string(nSlots));
                            ok = false;
                            break;
                        }
                    }
                }

            if (ok)
            {
                groups.push_back(GroupCost{});
                GroupCost &G = groups.back();
                G.opcode = op::EXPR;

                auto *vm = new ExprVm();
                vm->code = std::move(code);
                vm->constants = std::move(constants);
                vm->residualSlots = residualSlots;
                vm->slotCount = nSlots;
                vm->s = std::sqrt(std::max(0.0, w));

                auto *cost = new ceres::DynamicAutoDiffCostFunction<ExprVm>(vm);
                std::vector<double *> blocks;
                for (int id : params)
                {
                    cost->AddParameterBlock(3);
                    blocks.push_back(&pts[3 * id]);
                }
                cost->SetNumResiduals(nResiduals);
                ceres::ResidualBlockId rid = problem->AddResidualBlock(cost, loss.make(), blocks);
                G.blocks.push_back(rid);
            }
        }
        break;

        default:
            // Unknown opcode. Everything after it is unparseable, so stop — but say so. Silently
            // truncating here means solving a *different, smaller* problem and still reporting
            // success, which is the worst possible failure mode for a caller that just added a
            // constraint the native side does not know about.
            build_error = "unknown opcode " + std::to_string(opcode) +
                          " at offset " + std::to_string(ip - 1) +
                          "; the rest of the program was not built";
            ip = prog_len;
            break;
        }
    }

    // --- Second pass: LOCKS (use build-time lock_target)
    ip = 0;
    while (ip < prog_len)
    {
        int opcode = (int)program[ip++];
        switch (opcode)
        {
        case op::LOCK_X:
        case op::LOCK_Y:
        case op::LOCK_Z:
        case op::LOCK_XY:
        case op::LOCK_XZ:
        case op::LOCK_YZ:
        {
            if (ip + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            int n = (int)program[ip++];
            if (n < 1 || ip + n + 1 > prog_len)
            {
                ip = prog_len;
                break;
            }
            std::vector<int> g(n);
            for (int k = 0; k < n; ++k)
                g[k] = (int)program[ip++];
            double w = program[ip++];

            groups.push_back(GroupCost{});
            GroupCost &G = groups.back();
            G.opcode = opcode;

            for (int id : g)
                if (id >= 0 && id < Npts)
                {
                    ceres::ResidualBlockId rid;
                    switch (opcode)
                    {
                    case op::LOCK_X:
                        rid = problem->AddResidualBlock(
                            new ceres::AutoDiffCostFunction<LockXW, 1, 3>(new LockXW(&lock_target[3 * id + 0], w)),
                            nullptr, &pts[3 * id]);
                        break;
                    case op::LOCK_Y:
                        rid = problem->AddResidualBlock(
                            new ceres::AutoDiffCostFunction<LockYW, 1, 3>(new LockYW(&lock_target[3 * id + 1], w)),
                            nullptr, &pts[3 * id]);
                        break;
                    case op::LOCK_Z:
                        rid = problem->AddResidualBlock(
                            new ceres::AutoDiffCostFunction<LockZW, 1, 3>(new LockZW(&lock_target[3 * id + 2], w)),
                            nullptr, &pts[3 * id]);
                        break;
                    case op::LOCK_XY:
                        rid = problem->AddResidualBlock(
                            new ceres::AutoDiffCostFunction<LockXYW, 2, 3>(new LockXYW(&lock_target[3 * id + 0], &lock_target[3 * id + 1], w)),
                            nullptr, &pts[3 * id]);
                        break;
                    case op::LOCK_XZ:
                        rid = problem->AddResidualBlock(
                            new ceres::AutoDiffCostFunction<LockXZW, 2, 3>(new LockXZW(&lock_target[3 * id + 0], &lock_target[3 * id + 2], w)),
                            nullptr, &pts[3 * id]);
                        break;
                    case op::LOCK_YZ:
                        rid = problem->AddResidualBlock(
                            new ceres::AutoDiffCostFunction<LockYZW, 2, 3>(new LockYZW(&lock_target[3 * id + 1], &lock_target[3 * id + 2], w)),
                            nullptr, &pts[3 * id]);
                        break;
                    }
                    G.blocks.push_back(rid);
                }
        }
        break;

        default:
        {
            // Skip payloads for non-lock opcodes to keep cursor in sync
            switch (opcode)
            {
            case op::PLANAR:
            {
                int n = (int)program[ip++];
                ip += n + 1;
            }
            break;
            case op::ON_LINE:
            {
                int m = (int)program[ip++];
                ip += m + 1;
            }
            break;
            case op::DISTANCE:
                ip += 4;
                break;
            case op::SAME_X:
            case op::SAME_Y:
            case op::SAME_Z:
            case op::SAME_XY:
            case op::SAME_XZ:
            case op::SAME_YZ:
            {
                int n = (int)program[ip++];
                ip += n + 1;
            }
            break;
            case op::PERP_AT_ORIGIN:
                ip += 4;
                break;
            case op::PRIOR_3:
            case op::PRIOR_X:
            case op::PRIOR_Y:
            case op::PRIOR_Z:
            {
                int n = (int)program[ip++];
                ip += n + 1;
            }
            break;
            case op::MIN_DISTANCE:
            case op::MAX_DISTANCE:
                ip += 4;
                break;
            case op::EQUAL_DISTANCE:
                ip += 5;
                break;
            case op::NO_Z_NEG:
            {
                int n = (int)program[ip++];
                ip += n + 1;
            }
            break;
            case op::DOT_AT_ORIGIN:
                ip += 6;
                break;
            case op::VECTOR_DOT:
                ip += 7;
                break;
            case op::POINT_ON_PLANE:
                ip += 6;
                break;
            case op::LINE_PARALLEL:
                ip += 5;
                break;
            case op::LINE_ANGLE:
                ip += 6;
                break;
            case op::DIHEDRAL_ANGLE:
                ip += 6;
                break;
            case op::EQUAL_EDGE_VECTOR:
                ip += 5;
                break;
            case op::MIRROR_ACROSS_PLANE:
                ip += 6;
                break;
            case op::PANEL_AREA:
                ip += 6;
                break;
            case op::POINT_IN_HALFSPACE:
                ip += 6;
                break;
            case op::LINE_DIRECTION:
                ip += 6;
                break;
            case op::PLANE_NORMAL_DIRECTION:
                ip += 8;
                break;
            case op::LINE_TOWARD_POINT:
                ip += 4;
                break;
            default:
            {
                int span = operand_span(opcode, program, ip, prog_len);
                ip = (span < 0) ? prog_len : ip + span;
            }
            break;
            }
        }
        break;
        }
    }
}

// ============================================================
// Entry points
// ============================================================
// The solve-time knobs a caller can set, decoded from the flat double array the JVM passes. Flat
// doubles rather than a struct so the FFM layout on the Java side stays trivially correct.
struct SolveSettings
{
    int max_iterations = 8;
    int linear_solver = 0; // indexes kLinearSolvers below
    int num_threads = 1;
    double function_tolerance = 1e-6;
    double gradient_tolerance = 1e-10;
    double parameter_tolerance = 1e-8;
    bool nonmonotonic_steps = false;
    bool progress_to_stdout = false;
};

// Index order is ABI. Append only.
static const ceres::LinearSolverType kLinearSolvers[] = {
    ceres::DENSE_QR,                // 0 — the historic default
    ceres::DENSE_NORMAL_CHOLESKY,   // 1
    ceres::SPARSE_NORMAL_CHOLESKY,  // 2
    ceres::CGNR,                    // 3
    ceres::DENSE_SCHUR,             // 4
    ceres::ITERATIVE_SCHUR,         // 5
};
static const int kLinearSolverCount = (int)(sizeof(kLinearSolvers) / sizeof(kLinearSolvers[0]));

// ceres::LinearSolverType has its own numbering (DENSE_NORMAL_CHOLESKY = 0, DENSE_QR = 1, ...) and
// it is not ours. Translate back into the index space above before reporting which solver Ceres
// actually used, so the ABI never leaks Ceres' internal enum values — those are not stable across
// Ceres versions and a caller decoding them with our table would silently mislabel the answer.
// Returns -1 for a Ceres solver this ABI has no name for (e.g. SPARSE_SCHUR).
static int linear_solver_index(ceres::LinearSolverType used)
{
    for (int i = 0; i < kLinearSolverCount; ++i)
        if (kLinearSolvers[i] == used)
            return i;
    return -1;
}

static int solve_with_context(SolverContext *ctx,
                              const double *points_in, int num_points,
                              const SolveSettings &settings,
                              const double *prior_w_xyz,
                              double *points_out,
                              double *total_cost_out,
                              double *group_costs_out,
                              int group_costs_cap,
                              int *out_group_count,
                              double *summary_out,
                              int summary_cap)
{
    if (!ctx || !ctx->problem || !points_in || !points_out || num_points != ctx->num_pts)
        return 0;

    for (int i = 0; i < 3 * num_points; ++i)
    {
        ctx->pts[i] = points_in[i];
        ctx->prior_target[i] = points_in[i];
    }

    if (prior_w_xyz)
    {
        for (int i = 0; i < num_points; ++i)
        {
            ctx->prior_wx[i] = prior_w_xyz[3 * i + 0];
            ctx->prior_wy[i] = prior_w_xyz[3 * i + 1];
            ctx->prior_wz[i] = prior_w_xyz[3 * i + 2];
        }
    }

    ceres::Solver::Options opts;
    opts.linear_solver_type =
        kLinearSolvers[(settings.linear_solver >= 0 && settings.linear_solver < kLinearSolverCount)
                           ? settings.linear_solver
                           : 0];
    opts.max_num_iterations = std::max(1, settings.max_iterations);
    opts.num_threads = std::max(1, settings.num_threads);
    opts.function_tolerance = settings.function_tolerance;
    opts.gradient_tolerance = settings.gradient_tolerance;
    opts.parameter_tolerance = settings.parameter_tolerance;
    opts.use_nonmonotonic_steps = settings.nonmonotonic_steps;
    opts.minimizer_progress_to_stdout = settings.progress_to_stdout;
    opts.logging_type = settings.progress_to_stdout ? ceres::PER_MINIMIZER_ITERATION : ceres::SILENT;

    ceres::Solver::Summary summary;
    ceres::Solve(opts, ctx->problem.get(), &summary);

    for (int i = 0; i < 3 * num_points; ++i)
        points_out[i] = ctx->pts[i];

    // Fixed-layout summary. Append only; a caller that knows fewer slots passes a smaller cap.
    if (summary_out && summary_cap > 0)
    {
        double slots[] = {
            summary.initial_cost,
            summary.final_cost,
            (double)summary.num_successful_steps,
            (double)summary.num_unsuccessful_steps,
            (double)(summary.num_successful_steps + summary.num_unsuccessful_steps),
            (double)(int)summary.termination_type, // ceres::TerminationType
            summary.total_time_in_seconds,
            (double)summary.num_residuals,
            (double)summary.num_effective_parameters,
            (double)summary.num_parameter_blocks,
            (double)summary.num_residual_blocks,
            (double)linear_solver_index(summary.linear_solver_type_used),
        };
        int n = std::min(summary_cap, (int)(sizeof(slots) / sizeof(slots[0])));
        for (int i = 0; i < n; ++i)
            summary_out[i] = slots[i];
        for (int i = n; i < summary_cap; ++i)
            summary_out[i] = 0.0;
    }

    if (total_cost_out)
        *total_cost_out = summary.final_cost;

    if (group_costs_out && group_costs_cap > 0)
    {
        ceres::Problem::EvaluateOptions eo;
        eo.apply_loss_function = true;

        for (int i = 0; i < group_costs_cap; ++i)
            group_costs_out[i] = 0.0;

        for (const auto &G : ctx->groups)
        {
            if (G.opcode < 0 || G.opcode >= group_costs_cap)
                continue;
            eo.residual_blocks = G.blocks;
            double cost = 0.0;
            if (ctx->problem->Evaluate(eo, &cost, nullptr, nullptr, nullptr))
                group_costs_out[G.opcode] = cost;
            else
                group_costs_out[G.opcode] = std::numeric_limits<double>::quiet_NaN();
        }

        if (out_group_count)
            *out_group_count = group_costs_cap;
    }
    else if (out_group_count)
    {
        *out_group_count = 0;
    }

    return summary.IsSolutionUsable() ? 1 : 0;
}

extern "C"
{

    // Ceres logs through glog, and parts of it — the Levenberg-Marquardt strategy's "linear solver
    // failure" warning in particular — use LOG(WARNING) directly rather than going through
    // Solver::Options::logging_type. In a library loaded into someone else's process that means
    // uninitialised-glog noise on stderr during an otherwise successful solve. Initialise once and
    // keep it quiet; a caller who wants Ceres' own chatter asks for it per solve with
    // minimizer_progress_to_stdout.
    static void quiet_glog_once()
    {
#if defined(KEN_CERES_HAVE_GLOG)
        static bool done = false;
        if (done)
            return;
        done = true;
        if (!google::IsGoogleLoggingInitialized())
            google::InitGoogleLogging("ken_ceres");
        FLAGS_minloglevel = google::GLOG_ERROR;
        FLAGS_logtostderr = false;
        FLAGS_stderrthreshold = google::GLOG_FATAL;
#endif
    }

    const char *ken_ceres_jvm_abi_version()
    {
        // v2 adds ken_ceres_solver_solve2 (solver options + summary), ken_ceres_solver_last_error,
        // and the SET_LOSS / EDGE_TANGENCY / TANGENCY_CENTROID / CENTROID / RADIUS_ABOUT_CENTROID
        // opcodes. Every v1 entry point still behaves exactly as it did.
        return "ken_ceres_jvm_abi_v2";
    }

    // Empty when the whole program parsed. Non-empty means residuals are MISSING from the problem
    // and any solve is of a different, smaller problem than the caller asked for.
    const char *ken_ceres_solver_last_error(void *handle)
    {
        auto *ctx = static_cast<SolverContext *>(handle);
        return (ctx && !ctx->build_error.empty()) ? ctx->build_error.c_str() : "";
    }

    void *ken_ceres_solver_create(const double *points_in, int num_points,
                                  const double *program, int program_len,
                                  uint32_t constraints_hash)
    {
        if (!points_in || !program || num_points < 0 || program_len < 0)
            return nullptr;

        quiet_glog_once();
        auto *ctx = new SolverContext();
        ctx->build(num_points, points_in, program, program_len);
        ctx->hash = constraints_hash;
        return ctx;
    }

    void ken_ceres_solver_destroy(void *handle)
    {
        delete static_cast<SolverContext *>(handle);
    }

    int ken_ceres_solver_solve(void *handle,
                               const double *points_in, int num_points,
                               int max_iters,
                               const double *prior_w_xyz,
                               double *points_out,
                               double *total_cost_out,
                               double *group_costs_out,
                               int group_costs_cap,
                               int *out_group_count)
    {
        SolveSettings settings;
        settings.max_iterations = max_iters;
        return solve_with_context(static_cast<SolverContext *>(handle),
                                  points_in, num_points, settings,
                                  prior_w_xyz, points_out,
                                  total_cost_out, group_costs_out,
                                  group_costs_cap, out_group_count,
                                  nullptr, 0);
    }

    // As above, plus solver options in and a solver summary out.
    //
    // options is a flat array; a shorter one just leaves the later settings at their defaults:
    //   [0] max_iterations
    //   [1] linear solver   0 DENSE_QR, 1 DENSE_NORMAL_CHOLESKY, 2 SPARSE_NORMAL_CHOLESKY,
    //                       3 CGNR, 4 DENSE_SCHUR, 5 ITERATIVE_SCHUR
    //   [2] num_threads
    //   [3] function_tolerance
    //   [4] gradient_tolerance
    //   [5] parameter_tolerance
    //   [6] use_nonmonotonic_steps (0/1)
    //   [7] minimizer_progress_to_stdout (0/1)
    //
    // summary_out is a flat array of at most 12 slots; see the writer in solve_with_context.
    int ken_ceres_solver_solve2(void *handle,
                                const double *points_in, int num_points,
                                const double *options, int options_len,
                                const double *prior_w_xyz,
                                double *points_out,
                                double *total_cost_out,
                                double *group_costs_out,
                                int group_costs_cap,
                                int *out_group_count,
                                double *summary_out,
                                int summary_cap)
    {
        SolveSettings settings;
        auto slot = [&](int i, double fallback) -> double
        { return (options && i < options_len) ? options[i] : fallback; };
        if (options && options_len > 0)
        {
            settings.max_iterations = (int)slot(0, settings.max_iterations);
            settings.linear_solver = (int)slot(1, settings.linear_solver);
            settings.num_threads = (int)slot(2, settings.num_threads);
            settings.function_tolerance = slot(3, settings.function_tolerance);
            settings.gradient_tolerance = slot(4, settings.gradient_tolerance);
            settings.parameter_tolerance = slot(5, settings.parameter_tolerance);
            settings.nonmonotonic_steps = slot(6, 0.0) != 0.0;
            settings.progress_to_stdout = slot(7, 0.0) != 0.0;
        }
        return solve_with_context(static_cast<SolverContext *>(handle),
                                  points_in, num_points, settings,
                                  prior_w_xyz, points_out,
                                  total_cost_out, group_costs_out,
                                  group_costs_cap, out_group_count,
                                  summary_out, summary_cap);
    }

    // Basic: no dynamic prior weights, no metrics
    int solve_constraints(const double *points_in, int num_points,
                          const double *program, int program_len,
                          uint32_t constraints_hash, int max_iters,
                          double *points_out)
    {
        if (!CTX)
            CTX = std::make_unique<SolverContext>();

        const bool rebuild = (!CTX->problem) ||
                             (CTX->num_pts != num_points) ||
                             (CTX->hash != constraints_hash);

        if (rebuild)
        {
            printf("Rebuilding Ceres problem for %d points, hash %08x\n", num_points, constraints_hash);
            CTX->build(num_points, points_in, program, program_len);
            CTX->hash = constraints_hash;
        }

        // Warm start + refresh prior targets to current UI pose
        for (int i = 0; i < 3 * num_points; ++i)
        {
            CTX->pts[i] = points_in[i];
            CTX->prior_target[i] = points_in[i];
        }

        ceres::Solver::Options opts;
        opts.linear_solver_type = ceres::DENSE_QR;
        opts.max_num_iterations = std::max(1, max_iters);
        opts.minimizer_progress_to_stdout = false;
        opts.logging_type = ceres::PER_MINIMIZER_ITERATION;
        opts.logging_type = ceres::SILENT;
        opts.num_threads = 1;

        ceres::Solver::Summary summary;
        ceres::Solve(opts, CTX->problem.get(), &summary);

        for (int i = 0; i < 3 * num_points; ++i)
            points_out[i] = CTX->pts[i];
        return summary.IsSolutionUsable() ? 1 : 0;
    }

    // Extended: dynamic per-axis prior weights (length 3*N array or null)
    int solve_constraints_ex(const double *points_in, int num_points,
                             const double *program, int program_len,
                             uint32_t constraints_hash, int max_iters,
                             const double *prior_w_xyz, // nullable, len = 3*N
                             double *points_out)
    {
        if (!CTX)
            CTX = std::make_unique<SolverContext>();

        const bool rebuild = (!CTX->problem) ||
                             (CTX->num_pts != num_points) ||
                             (CTX->hash != constraints_hash);

        if (rebuild)
        {
            printf("Rebuilding Ceres problem for %d points, hash %08x\n", num_points, constraints_hash);
            CTX->build(num_points, points_in, program, program_len);
            CTX->hash = constraints_hash;
        }

        // Warm start + refresh prior targets
        for (int i = 0; i < 3 * num_points; ++i)
        {
            CTX->pts[i] = points_in[i];
            CTX->prior_target[i] = points_in[i];
        }

        // Optional dynamic weights
        if (prior_w_xyz)
        {
            for (int i = 0; i < num_points; ++i)
            {
                CTX->prior_wx[i] = prior_w_xyz[3 * i + 0];
                CTX->prior_wy[i] = prior_w_xyz[3 * i + 1];
                CTX->prior_wz[i] = prior_w_xyz[3 * i + 2];
            }
        }

        ceres::Solver::Options opts;
        opts.linear_solver_type = ceres::DENSE_QR;
        opts.max_num_iterations = std::max(1, max_iters);
        opts.minimizer_progress_to_stdout = false;
        opts.logging_type = ceres::PER_MINIMIZER_ITERATION;
        opts.logging_type = ceres::SILENT;
        opts.num_threads = 1;

        ceres::Solver::Summary summary;
        ceres::Solve(opts, CTX->problem.get(), &summary);

        for (int i = 0; i < 3 * num_points; ++i)
            points_out[i] = CTX->pts[i];
        return summary.IsSolutionUsable() ? 1 : 0;
    }

    // Metrics variant: returns total and per-group costs
    int solve_constraints_metrics(const double *points_in, int num_points,
                                  const double *program, int program_len,
                                  uint32_t constraints_hash, int max_iters,
                                  const double *prior_w_xyz, // nullable
                                  double *points_out,
                                  double *total_cost_out,  // nullable
                                  double *group_costs_out, // nullable; cap long enough
                                  int group_costs_cap,
                                  int *out_group_count) // nullable
    {
        if (!CTX)
            CTX = std::make_unique<SolverContext>();

        const bool rebuild = (!CTX->problem) ||
                             (CTX->num_pts != num_points) ||
                             (CTX->hash != constraints_hash);

        if (rebuild)
        {
            printf("Rebuilding Ceres problem for %d points, hash %08x\n", num_points, constraints_hash);
            CTX->build(num_points, points_in, program, program_len);
            CTX->hash = constraints_hash;
        }

        for (int i = 0; i < 3 * num_points; ++i)
        {
            CTX->pts[i] = points_in[i];
            CTX->prior_target[i] = points_in[i];
        }
        if (prior_w_xyz)
        {
            for (int i = 0; i < num_points; ++i)
            {
                CTX->prior_wx[i] = prior_w_xyz[3 * i + 0];
                CTX->prior_wy[i] = prior_w_xyz[3 * i + 1];
                CTX->prior_wz[i] = prior_w_xyz[3 * i + 2];
            }
        }

        ceres::Solver::Options opts;
        opts.linear_solver_type = ceres::DENSE_QR;
        opts.max_num_iterations = std::max(1, max_iters);
        opts.minimizer_progress_to_stdout = false;
        opts.logging_type = ceres::PER_MINIMIZER_ITERATION;
        opts.logging_type = ceres::SILENT;
        opts.num_threads = 1;

        ceres::Solver::Summary summary;
        ceres::Solve(opts, CTX->problem.get(), &summary);

        for (int i = 0; i < 3 * num_points; ++i)
            points_out[i] = CTX->pts[i];

        if (total_cost_out)
            *total_cost_out = summary.final_cost;

        if (group_costs_out && group_costs_cap > 0)
        {
            ceres::Problem::EvaluateOptions eo;
            eo.apply_loss_function = true;

            // zero-fill the whole array so unused opcodes are 0
            for (int i = 0; i < group_costs_cap; ++i)
            {
                group_costs_out[i] = 0.0;
            }

            for (const auto &G : CTX->groups)
            {
                if (G.opcode < 0 || G.opcode >= group_costs_cap)
                    continue; // safety
                eo.residual_blocks = G.blocks;
                double cost = 0.0;
                if (CTX->problem->Evaluate(eo, &cost, nullptr, nullptr, nullptr))
                {
                    group_costs_out[G.opcode] = cost; // cost keyed by opcode
                }
                else
                {
                    group_costs_out[G.opcode] = std::numeric_limits<double>::quiet_NaN();
                }
            }
            if (out_group_count)
                *out_group_count = group_costs_cap;
        }

        return summary.IsSolutionUsable() ? 1 : 0;
    }

} // extern "C"

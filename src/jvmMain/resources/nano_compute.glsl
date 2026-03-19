#version 450

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │                   Nano VM  ─  GLSL Compute Shader                       │
 * │                                                                         │
 * │  Runs one independent Nano VM instance per GPU invocation.              │
 * │  Instances share two large flat buffers (RAM + code) with non-          │
 * │  overlapping slices.  A mapping buffer connects each dispatch ID        │
 * │  to its slice offsets so you can freely partition the buffers.          │
 * │                                                                         │
 * │  Buffer bindings                                                        │
 * │  ─────────────────                                                      │
 * │  0  readonly  NanoMapping[]  dispatch-id → (ramBase, codeBase,          │
 * │                              outputBase, maxSteps)                      │
 * │  1            uint[]         RAM bytes, packed 4 per uint  (r/w).       │
 * │  2  readonly  uint[]         code bytes, packed 4 per uint.             │
 * │  3            uint[]         OUT-instruction results, packed 4/uint.    │
 * │                                                                         │
 * │  Push constants                                                         │
 * │  ──────────────                                                         │
 * │  numInstances     total VM instances (over-dispatch guard)              │
 * │  defaultMaxSteps  step cap when mapping.maxSteps == 0                   │
 * │  ramSize          RAM bytes per instance; must be a power of 2 (≤256)  │
 * │  codeSize         code bytes per instance (≤256)                       │
 * │                                                                         │
 * │  Byte-packing convention                                                │
 * │  ──────────────────────                                                 │
 * │  Byte at logical offset B is at word index B>>2,                        │
 * │  bits ((B&3)<<3) through ((B&3)<<3)+7.                                  │
 * │  Instances must not share any word across buffer boundaries.            │
 * │                                                                         │
 * │  Nano ISA summary (8-bit registers r0–r3, 32-byte RAM, ≤64-byte code)  │
 * │  ───────────────────────────────────────────────────────────────────── │
 * │  0x00       NOP                                                         │
 * │  0x01       HLT      halt                                               │
 * │  0x02       RET      pop return address                                 │
 * │  0x04–0x07  INC rX                                                      │
 * │  0x08–0x0B  DEC rX                                                      │
 * │  0x0C–0x0F  NOT rX                                                      │
 * │  0x10–0x13  NEG rX                                                      │
 * │  0x14–0x17  CLR rX                                                      │
 * │  0x18–0x1B  OUT rX   write reg to output stream                        │
 * │  0x1C–0x1F  RNG rX   fill with pseudo-random byte                      │
 * │  0x20–0x2F  MOV rD, rS    (encoded OP + (d<<2) + s)                    │
 * │  0x30–0x3F  ADD rD, rS                                                  │
 * │  0x40–0x4F  SUB rD, rS                                                  │
 * │  0x50–0x5F  MUL rD, rS                                                  │
 * │  0x60–0x6F  AND rD, rS                                                  │
 * │  0x70–0x7F  OR  rD, rS                                                  │
 * │  0x80–0x8F  XOR rD, rS                                                  │
 * │  0x90–0x9F  CMP rD, rS   (flags only)                                  │
 * │  0xA0–0xAF  LDR rD, (rS)  rD = RAM[rS]                                 │
 * │  0xB0–0xBF  STR (rD), rS  RAM[rD] = rS                                 │
 * │  0xC0–0xC3  LDI rX, imm   (2-byte)                                     │
 * │  0xC4–0xC7  LDA rX, addr  rX = RAM[addr]  (2-byte)                     │
 * │  0xC8–0xCB  STA rX, addr  RAM[addr] = rX  (2-byte)                     │
 * │  0xCC–0xCF  ADDI rX, imm  rX += signed imm  (2-byte)                   │
 * │  0xD0–0xD3  LDRR rX, rel  rX = RAM[PC+rel]  (2-byte, PC-relative RAM)  │
 * │  0xD4–0xD7  STRR rX, rel  RAM[PC+rel] = rX  (2-byte, PC-relative RAM)  │
 * │  0xD8–0xDB  SHL rX, imm   (2-byte, imm masked to 3 bits)               │
 * │  0xDC–0xDF  SHR rX, imm   (2-byte, imm masked to 3 bits)               │
 * │  0xE0       JMP rel   (2-byte, signed offset)                           │
 * │  0xE1       JZ  rel                                                     │
 * │  0xE2       JNZ rel                                                     │
 * │  0xE3       JGT rel   (!Z && !S)                                        │
 * │  0xE4       JLT rel   (S)                                               │
 * │  0xE5       JGE rel   (!S)                                              │
 * │  0xE6       JLE rel   (Z || S)                                          │
 * │  0xE7       JC  rel                                                     │
 * │  0xE8       JNC rel                                                     │
 * │  0xE9       CALL rel  (2-byte, push PC+2, jump)                        │
 * │  0xEA       JMPA addr (2-byte, absolute)                                │
 * │  0xEB       CALLA addr (2-byte, absolute call)                          │
 * │  0xEC–0xFB  DIV rD, rS   (1-byte; rS==0 → rD=0xFF)                     │
 * │  0xFC–0xFF  (invalid – decode to d≥4; treated as halt)                  │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

// Tune to match GPU occupancy / register pressure tradeoffs.
layout(local_size_x = 64) in;

// ─────────────────────────────────────────────────────────────────────────────
//  Buffers
// ─────────────────────────────────────────────────────────────────────────────

struct NanoMapping {
    uint ramBase;    // byte offset into ramWords  for this instance's 32-byte RAM
    uint codeBase;   // byte offset into codeWords for this instance's program
    uint outputBase; // byte offset into outWords  for this instance's OUT stream
    uint maxSteps;   // per-instance step cap (0 → use push-constant default)
};

layout(std430, binding = 0) readonly buffer MappingBuffer {
    NanoMapping mappings[];
};

// Flat RAM storage: all instances packed end-to-end.
// Regions are non-overlapping; each instance owns [ramBase, ramBase+ramSize).
layout(std430, binding = 1) buffer RamBuffer {
    uint ramWords[];
};

// Flat code storage: read-only.  Each instance owns [codeBase, codeBase+codeSize).
layout(std430, binding = 2) readonly buffer CodeBuffer {
    uint codeWords[];
};

// OUT instruction results: each instance appends bytes starting at outputBase.
layout(std430, binding = 3) buffer OutBuffer {
    uint outWords[];
};

// ─────────────────────────────────────────────────────────────────────────────
//  Push constants
// ─────────────────────────────────────────────────────────────────────────────

layout(push_constant) uniform PushConstants {
    uint numInstances;    // guard against over-dispatch
    uint defaultMaxSteps; // fallback when mapping.maxSteps == 0
    uint ramSize;         // bytes per instance (must be power of 2, typically 32)
    uint codeSize;        // bytes per instance (typically 64)
} pc;

// ─────────────────────────────────────────────────────────────────────────────
//  Utility helpers
// ─────────────────────────────────────────────────────────────────────────────

// Sign-extend an 8-bit value (stored as uint) to a signed int (-128..127).
int signExt8(uint v) {
    return (v > 127u) ? int(v) - 256 : int(v);
}

// Extract the destination-register index from a two-reg opcode encoding.
// Encoding: OP_BASE + (dst << 2) + src  →  dst = (op - base) >> 2
uint regD(uint op, uint base) { return (op - base) >> 2u; }

// Extract the source-register index from a two-reg opcode encoding.
// Encoding: OP_BASE + (dst << 2) + src  →  src = (op - base) & 3
uint regS(uint op, uint base) { return (op - base) & 3u; }

// ─────────────────────────────────────────────────────────────────────────────
//  Packed byte helpers
// ─────────────────────────────────────────────────────────────────────────────

// Read byte at absolute buffer offset flat = base + addr.
// addr is AND-masked with ramMask to implement the Nano RAM wrap-around.
uint ramLoad(uint base, uint addr, uint mask) {
    uint flat = base + (addr & mask);
    return (ramWords[flat >> 2u] >> ((flat & 3u) << 3u)) & 0xFFu;
}

// Write byte val at absolute buffer offset base + addr (with wrap-around mask).
void ramStore(uint base, uint addr, uint val, uint mask) {
    uint flat  = base + (addr & mask);
    uint word  = flat >> 2u;
    uint shift = (flat & 3u) << 3u;
    ramWords[word] = (ramWords[word] & ~(0xFFu << shift)) | ((val & 0xFFu) << shift);
}

// Read byte from code buffer at absolute offset base + addr.
// Returns 0 when addr is out of the code slice (treated as NOP on the next decode).
uint codeLoad(uint base, uint addr, uint codeSz) {
    if (addr >= codeSz) return 0u;
    uint flat = base + addr;
    return (codeWords[flat >> 2u] >> ((flat & 3u) << 3u)) & 0xFFu;
}

// Append byte val to the output stream for this instance.
void outStore(uint base, uint off, uint val) {
    uint flat  = base + off;
    uint word  = flat >> 2u;
    uint shift = (flat & 3u) << 3u;
    outWords[word] = (outWords[word] & ~(0xFFu << shift)) | ((val & 0xFFu) << shift);
}

// ─────────────────────────────────────────────────────────────────────────────
//  Flag helpers  (flags: bit0=Z  bit1=C  bit2=S  bit3=O)
// ─────────────────────────────────────────────────────────────────────────────

const uint F_Z = 1u;
const uint F_C = 2u;
const uint F_S = 4u;
const uint F_O = 8u;

// Set Z and S from an 8-bit result (other flags unchanged).
void setArith(inout uint flg, uint r8) {
    if ((r8 & 0xFFu) == 0u) flg |= F_Z; else flg &= ~F_Z;
    if ((r8 & 0x80u) != 0u) flg |= F_S; else flg &= ~F_S;
}

// Set all four flags for an addition result (a + b = r, r may exceed 8 bits).
void setAdd(inout uint flg, uint a, uint b, uint r) {
    if (r > 0xFFu) flg |= F_C; else flg &= ~F_C;
    if (((a ^ r) & (b ^ r) & 0x80u) != 0u) flg |= F_O; else flg &= ~F_O;
    setArith(flg, r);
}

// Set all four flags for a subtraction result (a - b = r).
void setSub(inout uint flg, uint a, uint b, uint r) {
    if (a < b) flg |= F_C; else flg &= ~F_C;
    if (((a ^ b) & (a ^ r) & 0x80u) != 0u) flg |= F_O; else flg &= ~F_O;
    setArith(flg, r);
}

// ─────────────────────────────────────────────────────────────────────────────
//  Xorshift-32 PRNG — seeded per invocation, advanced on each RNG instruction
// ─────────────────────────────────────────────────────────────────────────────

uint xorshift(uint s) {
    s ^= s << 13u;
    s ^= s >> 17u;
    s ^= s <<  5u;
    return s;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Main execution kernel
// ─────────────────────────────────────────────────────────────────────────────

void main() {
    uint gid = gl_GlobalInvocationID.x;
    if (gid >= pc.numInstances) return;

    NanoMapping m = mappings[gid];
    uint ramBase  = m.ramBase;
    uint codeBase = m.codeBase;
    uint outBase  = m.outputBase;
    uint maxSteps = (m.maxSteps > 0u) ? m.maxSteps : pc.defaultMaxSteps;
    uint codeSz   = pc.codeSize;
    uint ramMask  = pc.ramSize - 1u;  // power-of-2 wrap mask

    // ── VM state ──────────────────────────────────────────────────────────────
    uint reg[4];
    reg[0] = 0u; reg[1] = 0u; reg[2] = 0u; reg[3] = 0u;
    uint vPC    = 0u;
    uint flg    = F_Z;   // all regs zero → Z set
    uint stk[8];
    for (int _i = 0; _i < 8; _i++) stk[_i] = 0u;
    uint sp     = 0u;
    uint outOff = 0u;
    // Seed PRNG uniquely per invocation; avoid zero seed.
    uint rng    = gid ^ 0xDEADBEEFu ^ (gid << 16u);
    if (rng == 0u) rng = 1u;

    // ── Execution loop ────────────────────────────────────────────────────────
    for (uint step = 0u; step < maxSteps; ++step) {

        // Out-of-range PC → halt (matches Kotlin NanoVM bounds check)
        if (vPC >= codeSz) break;

        uint op  = codeLoad(codeBase, vPC,      codeSz);
        uint imm = codeLoad(codeBase, vPC + 1u, codeSz); // 2nd byte (may be unused)
        int  si  = signExt8(imm);                         // sign-extended immediate

        // ── 0x00  NOP ─────────────────────────────────────────────────────────
        if (op == 0x00u) {
            vPC += 1u;
        }
        // ── 0x01  HLT ─────────────────────────────────────────────────────────
        else if (op == 0x01u) {
            break;
        }
        // ── 0x02  RET ─────────────────────────────────────────────────────────
        else if (op == 0x02u) {
            if (sp == 0u) break;  // stack underflow → halt
            sp--;
            vPC = stk[sp];
        }
        // ── 0x04–0x07  INC rX ────────────────────────────────────────────────
        else if (op >= 0x04u && op <= 0x07u) {
            uint r   = op - 0x04u;
            uint res = reg[r] + 1u;
            if (res > 0xFFu) flg |= F_C; else flg &= ~F_C;
            reg[r] = res & 0xFFu;
            setArith(flg, reg[r]);
            vPC += 1u;
        }
        // ── 0x08–0x0B  DEC rX ────────────────────────────────────────────────
        else if (op >= 0x08u && op <= 0x0Bu) {
            uint r = op - 0x08u;
            if (reg[r] == 0u) flg |= F_C; else flg &= ~F_C;  // borrow
            reg[r] = (reg[r] - 1u) & 0xFFu;
            setArith(flg, reg[r]);
            vPC += 1u;
        }
        // ── 0x0C–0x0F  NOT rX ────────────────────────────────────────────────
        else if (op >= 0x0Cu && op <= 0x0Fu) {
            uint r = op - 0x0Cu;
            reg[r] = (~reg[r]) & 0xFFu;
            setArith(flg, reg[r]);
            vPC += 1u;
        }
        // ── 0x10–0x13  NEG rX ────────────────────────────────────────────────
        else if (op >= 0x10u && op <= 0x13u) {
            uint r = op - 0x10u;
            if (reg[r] != 0u) flg |= F_C; else flg &= ~F_C;
            reg[r] = (0x100u - reg[r]) & 0xFFu;
            setArith(flg, reg[r]);
            vPC += 1u;
        }
        // ── 0x14–0x17  CLR rX ────────────────────────────────────────────────
        else if (op >= 0x14u && op <= 0x17u) {
            uint r = op - 0x14u;
            reg[r] = 0u;
            flg = (flg & ~F_S) | F_Z;
            vPC += 1u;
        }
        // ── 0x18–0x1B  OUT rX ────────────────────────────────────────────────
        else if (op >= 0x18u && op <= 0x1Bu) {
            uint r = op - 0x18u;
            outStore(outBase, outOff, reg[r]);
            outOff++;
            vPC += 1u;
        }
        // ── 0x1C–0x1F  RNG rX ────────────────────────────────────────────────
        // XORing step into the seed makes each RNG instruction produce a
        // different value even if called in a tight loop, while keeping the
        // sequence fully deterministic and reproducible per invocation+program.
        else if (op >= 0x1Cu && op <= 0x1Fu) {
            uint r = op - 0x1Cu;
            rng    = xorshift(rng ^ step);
            reg[r] = rng & 0xFFu;
            setArith(flg, reg[r]);
            vPC += 1u;
        }
        // ── 0x20–0x2F  MOV rD, rS ────────────────────────────────────────────
        else if (op >= 0x20u && op <= 0x2Fu) {
            uint d = regD(op, 0x20u),  s = regS(op, 0x20u);
            reg[d] = reg[s];
            vPC += 1u;
        }
        // ── 0x30–0x3F  ADD rD, rS ────────────────────────────────────────────
        else if (op >= 0x30u && op <= 0x3Fu) {
            uint d = regD(op, 0x30u),  s = regS(op, 0x30u);
            uint res = reg[d] + reg[s];
            setAdd(flg, reg[d], reg[s], res);
            reg[d] = res & 0xFFu;
            vPC += 1u;
        }
        // ── 0x40–0x4F  SUB rD, rS ────────────────────────────────────────────
        else if (op >= 0x40u && op <= 0x4Fu) {
            uint d = regD(op, 0x40u),  s = regS(op, 0x40u);
            uint res = reg[d] - reg[s];
            setSub(flg, reg[d], reg[s], res);
            reg[d] = res & 0xFFu;
            vPC += 1u;
        }
        // ── 0x50–0x5F  MUL rD, rS ────────────────────────────────────────────
        else if (op >= 0x50u && op <= 0x5Fu) {
            uint d = regD(op, 0x50u),  s = regS(op, 0x50u);
            uint res = reg[d] * reg[s];
            if (res > 0xFFu) flg |= F_C; else flg &= ~F_C;
            reg[d] = res & 0xFFu;
            setArith(flg, reg[d]);
            vPC += 1u;
        }
        // ── 0x60–0x6F  AND rD, rS ────────────────────────────────────────────
        else if (op >= 0x60u && op <= 0x6Fu) {
            uint d = regD(op, 0x60u),  s = regS(op, 0x60u);
            reg[d] = reg[d] & reg[s];
            setArith(flg, reg[d]);
            vPC += 1u;
        }
        // ── 0x70–0x7F  OR rD, rS ─────────────────────────────────────────────
        else if (op >= 0x70u && op <= 0x7Fu) {
            uint d = regD(op, 0x70u),  s = regS(op, 0x70u);
            reg[d] = reg[d] | reg[s];
            setArith(flg, reg[d]);
            vPC += 1u;
        }
        // ── 0x80–0x8F  XOR rD, rS ────────────────────────────────────────────
        else if (op >= 0x80u && op <= 0x8Fu) {
            uint d = regD(op, 0x80u),  s = regS(op, 0x80u);
            reg[d] = reg[d] ^ reg[s];
            setArith(flg, reg[d]);
            vPC += 1u;
        }
        // ── 0x90–0x9F  CMP rD, rS  (flags only, no register write) ──────────
        else if (op >= 0x90u && op <= 0x9Fu) {
            uint d = regD(op, 0x90u),  s = regS(op, 0x90u);
            uint res = reg[d] - reg[s];
            setSub(flg, reg[d], reg[s], res);
            vPC += 1u;
        }
        // ── 0xA0–0xAF  LDR rD, (rS)  rD = RAM[rS] ───────────────────────────
        else if (op >= 0xA0u && op <= 0xAFu) {
            uint d = regD(op, 0xA0u),  s = regS(op, 0xA0u);
            reg[d] = ramLoad(ramBase, reg[s], ramMask);
            setArith(flg, reg[d]);
            vPC += 1u;
        }
        // ── 0xB0–0xBF  STR (rD), rS  RAM[rD] = rS ───────────────────────────
        else if (op >= 0xB0u && op <= 0xBFu) {
            uint d = regD(op, 0xB0u),  s = regS(op, 0xB0u);
            ramStore(ramBase, reg[d], reg[s], ramMask);
            vPC += 1u;
        }
        // ── 0xC0–0xC3  LDI rX, imm ───────────────────────────────────────────
        else if (op >= 0xC0u && op <= 0xC3u) {
            uint r = op - 0xC0u;
            reg[r] = imm;
            setArith(flg, reg[r]);
            vPC += 2u;
        }
        // ── 0xC4–0xC7  LDA rX, addr  rX = RAM[addr] ─────────────────────────
        else if (op >= 0xC4u && op <= 0xC7u) {
            uint r = op - 0xC4u;
            reg[r] = ramLoad(ramBase, imm, ramMask);
            setArith(flg, reg[r]);
            vPC += 2u;
        }
        // ── 0xC8–0xCB  STA rX, addr  RAM[addr] = rX ─────────────────────────
        else if (op >= 0xC8u && op <= 0xCBu) {
            uint r = op - 0xC8u;
            ramStore(ramBase, imm, reg[r], ramMask);
            vPC += 2u;
        }
        // ── 0xCC–0xCF  ADDI rX, signed-imm ──────────────────────────────────
        // Cannot reuse setAdd() here: the raw result can be negative (when si < 0
        // and reg[r] is small), which would wrap to a huge uint and falsely set
        // F_C.  Instead carry is checked as `ires > 255` in integer arithmetic,
        // and overflow uses the raw byte `imm` as operand b (matching the
        // Kotlin reference: setAddFlags(reg[r], imm and 0xFF, result)).
        else if (op >= 0xCCu && op <= 0xCFu) {
            uint r    = op - 0xCCu;
            int  ires = int(reg[r]) + si;          // -128..383 range
            uint a    = reg[r];
            uint b    = imm;                        // raw byte for overflow calc
            uint res  = uint(ires);
            if (ires > 255) flg |= F_C; else flg &= ~F_C;
            if (((a ^ res) & (b ^ res) & 0x80u) != 0u) flg |= F_O; else flg &= ~F_O;
            setArith(flg, res);
            reg[r] = res & 0xFFu;
            vPC += 2u;
        }
        // ── 0xD0–0xD3  LDRR rX, rel  rX = RAM[PC + rel] ─────────────────────
        else if (op >= 0xD0u && op <= 0xD3u) {
            uint r = op - 0xD0u;
            // vPC is the current instruction address; si is signed relative offset
            reg[r] = ramLoad(ramBase, uint(int(vPC) + si), ramMask);
            setArith(flg, reg[r]);
            vPC += 2u;
        }
        // ── 0xD4–0xD7  STRR rX, rel  RAM[PC + rel] = rX ─────────────────────
        else if (op >= 0xD4u && op <= 0xD7u) {
            uint r = op - 0xD4u;
            ramStore(ramBase, uint(int(vPC) + si), reg[r], ramMask);
            vPC += 2u;
        }
        // ── 0xD8–0xDB  SHL rX, imm ───────────────────────────────────────────
        else if (op >= 0xD8u && op <= 0xDBu) {
            uint r   = op - 0xD8u;
            uint sh  = imm & 7u;
            uint res = reg[r] << sh;
            if ((res & 0xFF00u) != 0u) flg |= F_C; else flg &= ~F_C;
            reg[r] = res & 0xFFu;
            setArith(flg, reg[r]);
            vPC += 2u;
        }
        // ── 0xDC–0xDF  SHR rX, imm ───────────────────────────────────────────
        else if (op >= 0xDCu && op <= 0xDFu) {
            uint r  = op - 0xDCu;
            uint sh = imm & 7u;
            reg[r]  = (reg[r] >> sh) & 0xFFu;
            setArith(flg, reg[r]);
            vPC += 2u;
        }
        // ── 0xE0  JMP rel ────────────────────────────────────────────────────
        else if (op == 0xE0u) {
            vPC = uint(int(vPC) + 2 + si);
            // bounds check performed at top of next iteration
        }
        // ── 0xE1  JZ rel ─────────────────────────────────────────────────────
        else if (op == 0xE1u) {
            vPC = ((flg & F_Z) != 0u) ? uint(int(vPC) + 2 + si) : (vPC + 2u);
        }
        // ── 0xE2  JNZ rel ────────────────────────────────────────────────────
        else if (op == 0xE2u) {
            vPC = ((flg & F_Z) == 0u) ? uint(int(vPC) + 2 + si) : (vPC + 2u);
        }
        // ── 0xE3  JGT rel  (!Z && !S) ────────────────────────────────────────
        else if (op == 0xE3u) {
            vPC = ((flg & (F_Z | F_S)) == 0u) ? uint(int(vPC) + 2 + si) : (vPC + 2u);
        }
        // ── 0xE4  JLT rel  (S) ───────────────────────────────────────────────
        else if (op == 0xE4u) {
            vPC = ((flg & F_S) != 0u) ? uint(int(vPC) + 2 + si) : (vPC + 2u);
        }
        // ── 0xE5  JGE rel  (!S) ──────────────────────────────────────────────
        else if (op == 0xE5u) {
            vPC = ((flg & F_S) == 0u) ? uint(int(vPC) + 2 + si) : (vPC + 2u);
        }
        // ── 0xE6  JLE rel  (Z || S) ──────────────────────────────────────────
        else if (op == 0xE6u) {
            vPC = ((flg & (F_Z | F_S)) != 0u) ? uint(int(vPC) + 2 + si) : (vPC + 2u);
        }
        // ── 0xE7  JC rel ─────────────────────────────────────────────────────
        else if (op == 0xE7u) {
            vPC = ((flg & F_C) != 0u) ? uint(int(vPC) + 2 + si) : (vPC + 2u);
        }
        // ── 0xE8  JNC rel ────────────────────────────────────────────────────
        else if (op == 0xE8u) {
            vPC = ((flg & F_C) == 0u) ? uint(int(vPC) + 2 + si) : (vPC + 2u);
        }
        // ── 0xE9  CALL rel ───────────────────────────────────────────────────
        else if (op == 0xE9u) {
            if (sp >= 8u) break;  // call stack overflow → halt
            stk[sp] = vPC + 2u;
            sp++;
            vPC = uint(int(vPC) + 2 + si);
        }
        // ── 0xEA  JMPA addr (absolute jump) ──────────────────────────────────
        else if (op == 0xEAu) {
            vPC = imm;
        }
        // ── 0xEB  CALLA addr (absolute call) ─────────────────────────────────
        else if (op == 0xEBu) {
            if (sp >= 8u) break;  // call stack overflow → halt
            stk[sp] = vPC + 2u;
            sp++;
            vPC = imm;
        }
        // ── 0xEC–0xFB  DIV rD, rS  rD = rD / rS  (rS==0 → rD=0xFF) ─────────
        // 0xFC–0xFF: regD() returns >= 4 (invalid register) → halt.
        else if (op >= 0xECu) {
            uint d = regD(op, 0xECu),  s = regS(op, 0xECu);
            if (d >= 4u) break;  // opcodes 0xFC-0xFF: invalid register → halt
            reg[d] = (reg[s] == 0u) ? 0xFFu : (reg[d] / reg[s]) & 0xFFu;
            setArith(flg, reg[d]);
            vPC += 1u;
        }
        // ── undefined opcode (e.g. 0x03) → halt ──────────────────────────────
        else {
            break;
        }
    }
    // After the loop, RAM at [ramBase, ramBase+ramSize) reflects the final
    // VM state.  OUT bytes are at outWords[outBase .. outBase+outOff).
    // Registers, PC, and flags are local and not written back; add a 5th
    // binding if you need to readback register state.
}

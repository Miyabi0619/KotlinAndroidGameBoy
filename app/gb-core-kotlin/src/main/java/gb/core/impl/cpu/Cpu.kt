package gb.core.impl.cpu

/**
 * Game Boy CPU 本体。
 *
 * - [registers]: レジスタとフラグの状態
 * - [bus]: メモリアクセス用インターフェース
 *
 * 現時点では NOP / LD A, n / INC A / 一部のレジスタ間コピーのみをサポートし、
 * それ以外のオペコードは例外を投げる。後続タスクで命令セットを段階的に拡張していく。
 */
class Cpu(
    private val bus: Bus,
) {
    private enum class AluOp {
        ADD,
        ADC,
        SUB,
        SBC,
        AND,
        OR,
        XOR,
        CP,
    }

    private enum class Condition {
        NZ,
        Z,
        NC,
        C,
    }

    /**
     * 1 命令あたりのサイクル数を表す定数群。
     * 今後命令が増えた場合もここに追加していく。
     */
    private object Cycles {
        const val NOP: Int = 4
        const val LD_R_N: Int = 8
        const val LD_RR_NN: Int = 12
        const val LD_SP_HL: Int = 8
        const val LD_NN_SP: Int = 20
        const val LD_HL_SP_E: Int = 12
        const val ALU_R: Int = 4
        const val ALU_N: Int = 8
        const val ALU_FROM_HL: Int = 8
        const val JP: Int = 16
        const val JP_COND_TAKEN: Int = 16
        const val JP_COND_NOT_TAKEN: Int = 12
        const val JR: Int = 12
        const val JR_COND_TAKEN: Int = 12
        const val JR_COND_NOT_TAKEN: Int = 8
        const val CALL: Int = 24
        const val CALL_COND_TAKEN: Int = 24
        const val CALL_COND_NOT_TAKEN: Int = 12
        const val RET: Int = 16
        const val RET_COND_TAKEN: Int = 20
        const val RET_COND_NOT_TAKEN: Int = 8
        const val RST: Int = 16
        const val PUSH: Int = 16
        const val POP: Int = 12
        const val INC_R: Int = 4
        const val DEC_R: Int = 4
        const val INC_16: Int = 8
        const val DEC_16: Int = 8
        const val LD_R_R: Int = 4
        const val LD_A_FROM_HL: Int = 8
        const val LD_HL_FROM_A: Int = 8
        const val LD_A_FROM_HL_INC: Int = 8
        const val LD_HL_FROM_A_INC: Int = 8
        const val LD_R_FROM_HL: Int = 8
        const val LD_HL_FROM_R: Int = 8
        const val INC_DEC_AT_HL: Int = 12
        const val BIT_R: Int = 8
    }

    val registers = Registers()

    /**
     * 現在の PC が指す 1 命令を実行し、その命令に要したサイクル数を返す。
     */
    fun executeInstruction(): Int {
        val pcBefore = registers.pc
        val opcode = bus.readByte(pcBefore).toInt()

        // 次の命令に備えて PC を 1 バイト分進める。
        registers.pc = (pcBefore.toInt() + 1).toUShort()

        return executeByOpcode(opcode, pcBefore)
    }

    private fun executeByOpcode(
        opcode: Int,
        pcBefore: UShort,
    ): Int =
        when (opcode) {
            0x00 -> executeNop()
            0xCB -> executeCbPrefixed()
            // 即値ロード
            0x06 -> executeLdImmediate(::setB) // LD B, n
            0x0E -> executeLdImmediate(::setC) // LD C, n
            0x16 -> executeLdImmediate(::setD) // LD D, n
            0x1E -> executeLdImmediate(::setE) // LD E, n
            0x26 -> executeLdImmediate(::setH) // LD H, n
            0x2E -> executeLdImmediate(::setL) // LD L, n
            0x3E -> executeLdImmediate(::setA) // LD A, n
            // 16bit 即値ロード
            0x01 -> executeLd16Immediate { value -> registers.bc = value } // LD BC, nn
            0x11 -> executeLd16Immediate { value -> registers.de = value } // LD DE, nn
            0x21 -> executeLd16Immediate { value -> registers.hl = value } // LD HL, nn
            0x31 -> executeLd16Immediate { value -> registers.sp = value } // LD SP, nn
            // 8bit INC
            0x04 -> executeInc8({ registers.b }, ::setB) // INC B
            0x0C -> executeInc8({ registers.c }, ::setC) // INC C
            0x14 -> executeInc8({ registers.d }, ::setD) // INC D
            0x1C -> executeInc8({ registers.e }, ::setE) // INC E
            0x24 -> executeInc8({ registers.h }, ::setH) // INC H
            0x2C -> executeInc8({ registers.l }, ::setL) // INC L
            0x3C -> executeInc8({ registers.a }, ::setA) // INC A
            0x34 -> executeIncAtHL() // INC (HL)
            // 8bit DEC
            0x05 -> executeDec8({ registers.b }, ::setB) // DEC B
            0x0D -> executeDec8({ registers.c }, ::setC) // DEC C
            0x15 -> executeDec8({ registers.d }, ::setD) // DEC D
            0x1D -> executeDec8({ registers.e }, ::setE) // DEC E
            0x25 -> executeDec8({ registers.h }, ::setH) // DEC H
            0x2D -> executeDec8({ registers.l }, ::setL) // DEC L
            0x3D -> executeDec8({ registers.a }, ::setA) // DEC A
            0x35 -> executeDecAtHL() // DEC (HL)
            // 8bit 算術（A, r / A, n / A, (HL)）
            0x27 -> executeDaa() // DAA
            0x80 -> executeAlu(AluOp.ADD, registers.b) // ADD A, B
            0x81 -> executeAlu(AluOp.ADD, registers.c) // ADD A, C
            0x82 -> executeAlu(AluOp.ADD, registers.d) // ADD A, D
            0x83 -> executeAlu(AluOp.ADD, registers.e) // ADD A, E
            0x84 -> executeAlu(AluOp.ADD, registers.h) // ADD A, H
            0x85 -> executeAlu(AluOp.ADD, registers.l) // ADD A, L
            0x87 -> executeAlu(AluOp.ADD, registers.a) // ADD A, A
            0x86 -> executeAluFromHL(AluOp.ADD) // ADD A, (HL)
            0xC6 -> executeAluImmediate(AluOp.ADD) // ADD A, n
            0x88 -> executeAlu(AluOp.ADC, registers.b) // ADC A, B
            0x89 -> executeAlu(AluOp.ADC, registers.c) // ADC A, C
            0x8A -> executeAlu(AluOp.ADC, registers.d) // ADC A, D
            0x8B -> executeAlu(AluOp.ADC, registers.e) // ADC A, E
            0x8C -> executeAlu(AluOp.ADC, registers.h) // ADC A, H
            0x8D -> executeAlu(AluOp.ADC, registers.l) // ADC A, L
            0x8F -> executeAlu(AluOp.ADC, registers.a) // ADC A, A
            0x8E -> executeAluFromHL(AluOp.ADC) // ADC A, (HL)
            0xCE -> executeAluImmediate(AluOp.ADC) // ADC A, n
            0x90 -> executeAlu(AluOp.SUB, registers.b) // SUB B
            0x91 -> executeAlu(AluOp.SUB, registers.c) // SUB C
            0x92 -> executeAlu(AluOp.SUB, registers.d) // SUB D
            0x93 -> executeAlu(AluOp.SUB, registers.e) // SUB E
            0x94 -> executeAlu(AluOp.SUB, registers.h) // SUB H
            0x95 -> executeAlu(AluOp.SUB, registers.l) // SUB L
            0x97 -> executeAlu(AluOp.SUB, registers.a) // SUB A
            0x96 -> executeAluFromHL(AluOp.SUB) // SUB (HL)
            0xD6 -> executeAluImmediate(AluOp.SUB) // SUB n
            0x98 -> executeAlu(AluOp.SBC, registers.b) // SBC A, B
            0x99 -> executeAlu(AluOp.SBC, registers.c) // SBC A, C
            0x9A -> executeAlu(AluOp.SBC, registers.d) // SBC A, D
            0x9B -> executeAlu(AluOp.SBC, registers.e) // SBC A, E
            0x9C -> executeAlu(AluOp.SBC, registers.h) // SBC A, H
            0x9D -> executeAlu(AluOp.SBC, registers.l) // SBC A, L
            0x9F -> executeAlu(AluOp.SBC, registers.a) // SBC A, A
            0x9E -> executeAluFromHL(AluOp.SBC) // SBC A, (HL)
            0xDE -> executeAluImmediate(AluOp.SBC) // SBC A, n
            0xA0 -> executeAlu(AluOp.AND, registers.b) // AND B
            0xA1 -> executeAlu(AluOp.AND, registers.c) // AND C
            0xA2 -> executeAlu(AluOp.AND, registers.d) // AND D
            0xA3 -> executeAlu(AluOp.AND, registers.e) // AND E
            0xA4 -> executeAlu(AluOp.AND, registers.h) // AND H
            0xA5 -> executeAlu(AluOp.AND, registers.l) // AND L
            0xA7 -> executeAlu(AluOp.AND, registers.a) // AND A
            0xA6 -> executeAluFromHL(AluOp.AND) // AND (HL)
            0xE6 -> executeAluImmediate(AluOp.AND) // AND n
            0xA8 -> executeAlu(AluOp.XOR, registers.b) // XOR B
            0xA9 -> executeAlu(AluOp.XOR, registers.c) // XOR C
            0xAA -> executeAlu(AluOp.XOR, registers.d) // XOR D
            0xAB -> executeAlu(AluOp.XOR, registers.e) // XOR E
            0xAC -> executeAlu(AluOp.XOR, registers.h) // XOR H
            0xAD -> executeAlu(AluOp.XOR, registers.l) // XOR L
            0xAF -> executeAlu(AluOp.XOR, registers.a) // XOR A
            0xAE -> executeAluFromHL(AluOp.XOR) // XOR (HL)
            0xEE -> executeAluImmediate(AluOp.XOR) // XOR n
            0xB0 -> executeAlu(AluOp.OR, registers.b) // OR B
            0xB1 -> executeAlu(AluOp.OR, registers.c) // OR C
            0xB2 -> executeAlu(AluOp.OR, registers.d) // OR D
            0xB3 -> executeAlu(AluOp.OR, registers.e) // OR E
            0xB4 -> executeAlu(AluOp.OR, registers.h) // OR H
            0xB5 -> executeAlu(AluOp.OR, registers.l) // OR L
            0xB7 -> executeAlu(AluOp.OR, registers.a) // OR A
            0xB6 -> executeAluFromHL(AluOp.OR) // OR (HL)
            0xF6 -> executeAluImmediate(AluOp.OR) // OR n
            0xB8 -> executeAlu(AluOp.CP, registers.b) // CP B
            0xB9 -> executeAlu(AluOp.CP, registers.c) // CP C
            0xBA -> executeAlu(AluOp.CP, registers.d) // CP D
            0xBB -> executeAlu(AluOp.CP, registers.e) // CP E
            0xBC -> executeAlu(AluOp.CP, registers.h) // CP H
            0xBD -> executeAlu(AluOp.CP, registers.l) // CP L
            0xBF -> executeAlu(AluOp.CP, registers.a) // CP A
            0xBE -> executeAluFromHL(AluOp.CP) // CP (HL)
            0xFE -> executeAluImmediate(AluOp.CP) // CP n
            // JP nn / JP cc, nn
            0xC3 -> executeJpUnconditional() // JP nn
            0xC2 -> executeJpConditional(Condition.NZ) // JP NZ, nn
            0xCA -> executeJpConditional(Condition.Z) // JP Z, nn
            0xD2 -> executeJpConditional(Condition.NC) // JP NC, nn
            0xDA -> executeJpConditional(Condition.C) // JP C, nn
            // JR e / JR cc, e
            0x18 -> executeJrUnconditional() // JR e
            0x20 -> executeJrConditional(Condition.NZ) // JR NZ, e
            0x28 -> executeJrConditional(Condition.Z) // JR Z, e
            0x30 -> executeJrConditional(Condition.NC) // JR NC, e
            0x38 -> executeJrConditional(Condition.C) // JR C, e
            // PUSH rr / POP rr
            0xC5 -> executePush { registers.bc } // PUSH BC
            0xD5 -> executePush { registers.de } // PUSH DE
            0xE5 -> executePush { registers.hl } // PUSH HL
            0xF5 -> executePush { registers.af } // PUSH AF
            0xC1 -> executePop { value -> registers.bc = value } // POP BC
            0xD1 -> executePop { value -> registers.de = value } // POP DE
            0xE1 -> executePop { value -> registers.hl = value } // POP HL
            0xF1 -> executePop { value -> registers.af = value } // POP AF
            // CALL nn / CALL cc, nn
            0xCD -> executeCallUnconditional() // CALL nn
            0xC4 -> executeCallConditional(Condition.NZ) // CALL NZ, nn
            0xCC -> executeCallConditional(Condition.Z) // CALL Z, nn
            0xD4 -> executeCallConditional(Condition.NC) // CALL NC, nn
            0xDC -> executeCallConditional(Condition.C) // CALL C, nn
            // RET / RET cc
            0xC9 -> executeRetUnconditional() // RET
            0xC0 -> executeRetConditional(Condition.NZ) // RET NZ
            0xC8 -> executeRetConditional(Condition.Z) // RET Z
            0xD0 -> executeRetConditional(Condition.NC) // RET NC
            0xD8 -> executeRetConditional(Condition.C) // RET C
            // RST
            0xC7 -> executeRst(0x00u) // RST 00H
            0xCF -> executeRst(0x08u) // RST 08H
            0xD7 -> executeRst(0x10u) // RST 10H
            0xDF -> executeRst(0x18u) // RST 18H
            0xE7 -> executeRst(0x20u) // RST 20H
            0xEF -> executeRst(0x28u) // RST 28H
            0xF7 -> executeRst(0x30u) // RST 30H
            0xFF -> executeRst(0x38u) // RST 38H
            // 16bit INC/DEC
            0x03 -> executeIncBC()
            0x0B -> executeDecBC()
            0x23 -> executeIncHL()
            0x13 -> executeIncDE()
            0x1B -> executeDecDE()
            0x33 -> executeIncSP()
            0x3B -> executeDecSP()
            // 16bit LD（SP とメモリ）
            0xF9 -> executeLdSpFromHl() // LD SP, HL
            0x08 -> executeLdMemoryFromSp() // LD (nn), SP
            0xF8 -> executeLdHlFromSpPlusImmediate() // LD HL, SP+e
            // HL 自動インクリメント付きロード／ストア
            0x22 -> executeLdHLPlusFromA()
            0x2A -> executeLdAFromHLPlus()
            // HL 経由のメモリアクセス（単発）
            0x7E -> executeLdAFromHL()
            0x77 -> executeLdHLFromA()
            // A <-> (BC/DE)
            0x0A -> executeLdAFromIndirect(registers.bc) // LD A, (BC)
            0x1A -> executeLdAFromIndirect(registers.de) // LD A, (DE)
            0x02 -> executeLdIndirectFromA(registers.bc) // LD (BC), A
            0x12 -> executeLdIndirectFromA(registers.de) // LD (DE), A
            // A <-> (nn)
            0xFA -> executeLdAFromDirectAddress() // LD A, (nn)
            0xEA -> executeLdDirectAddressFromA() // LD (nn), A
            // 高位 I/O / HRAM アクセス
            0xE0 -> executeLdhFromAWithImmediateOffset() // LDH (n), A
            0xF0 -> executeLdhAToImmediateOffset() // LDH A, (n)
            0xE2 -> executeLdhFromAWithCOffset() // LD (C), A
            0xF2 -> executeLdhAToCOffset() // LD A, (C)
            // レジスタ <-> (HL)
            0x46 -> executeLdRegisterFromHL(::setB) // LD B, (HL)
            0x4E -> executeLdRegisterFromHL(::setC) // LD C, (HL)
            0x56 -> executeLdRegisterFromHL(::setD) // LD D, (HL)
            0x5E -> executeLdRegisterFromHL(::setE) // LD E, (HL)
            0x66 -> executeLdRegisterFromHL(::setH) // LD H, (HL)
            0x6E -> executeLdRegisterFromHL(::setL) // LD L, (HL)
            0x70 -> executeLdHLFromRegister(registers.b) // LD (HL), B
            0x71 -> executeLdHLFromRegister(registers.c) // LD (HL), C
            0x72 -> executeLdHLFromRegister(registers.d) // LD (HL), D
            0x73 -> executeLdHLFromRegister(registers.e) // LD (HL), E
            0x74 -> executeLdHLFromRegister(registers.h) // LD (HL), H
            0x75 -> executeLdHLFromRegister(registers.l) // LD (HL), L
            // 0x76 は HALT（別途実装予定）
            // レジスタ間コピー（A -> その他）
            0x47 -> executeLdRegister(::setB, registers.a) // LD B, A
            0x4F -> executeLdRegister(::setC, registers.a) // LD C, A
            0x57 -> executeLdRegister(::setD, registers.a) // LD D, A
            0x5F -> executeLdRegister(::setE, registers.a) // LD E, A
            0x67 -> executeLdRegister(::setH, registers.a) // LD H, A
            0x6F -> executeLdRegister(::setL, registers.a) // LD L, A
            // レジスタ間コピー（その他 -> A）
            0x78 -> executeLdRegister(::setA, registers.b) // LD A, B
            0x79 -> executeLdRegister(::setA, registers.c) // LD A, C
            0x7A -> executeLdRegister(::setA, registers.d) // LD A, D
            0x7B -> executeLdRegister(::setA, registers.e) // LD A, E
            0x7C -> executeLdRegister(::setA, registers.h) // LD A, H
            0x7D -> executeLdRegister(::setA, registers.l) // LD A, L
            // レジスタ間コピー（B, C, D, E, H, L 同士）
            // B 行
            0x40 -> executeLdRegister(::setB, registers.b) // LD B, B
            0x41 -> executeLdRegister(::setB, registers.c) // LD B, C
            0x42 -> executeLdRegister(::setB, registers.d) // LD B, D
            0x43 -> executeLdRegister(::setB, registers.e) // LD B, E
            0x44 -> executeLdRegister(::setB, registers.h) // LD B, H
            0x45 -> executeLdRegister(::setB, registers.l) // LD B, L
            // C 行
            0x48 -> executeLdRegister(::setC, registers.b) // LD C, B
            0x49 -> executeLdRegister(::setC, registers.c) // LD C, C
            0x4A -> executeLdRegister(::setC, registers.d) // LD C, D
            0x4B -> executeLdRegister(::setC, registers.e) // LD C, E
            0x4C -> executeLdRegister(::setC, registers.h) // LD C, H
            0x4D -> executeLdRegister(::setC, registers.l) // LD C, L
            // D 行
            0x50 -> executeLdRegister(::setD, registers.b) // LD D, B
            0x51 -> executeLdRegister(::setD, registers.c) // LD D, C
            0x52 -> executeLdRegister(::setD, registers.d) // LD D, D
            0x53 -> executeLdRegister(::setD, registers.e) // LD D, E
            0x54 -> executeLdRegister(::setD, registers.h) // LD D, H
            0x55 -> executeLdRegister(::setD, registers.l) // LD D, L
            // E 行
            0x58 -> executeLdRegister(::setE, registers.b) // LD E, B
            0x59 -> executeLdRegister(::setE, registers.c) // LD E, C
            0x5A -> executeLdRegister(::setE, registers.d) // LD E, D
            0x5B -> executeLdRegister(::setE, registers.e) // LD E, E
            0x5C -> executeLdRegister(::setE, registers.h) // LD E, H
            0x5D -> executeLdRegister(::setE, registers.l) // LD E, L
            // H 行
            0x60 -> executeLdRegister(::setH, registers.b) // LD H, B
            0x61 -> executeLdRegister(::setH, registers.c) // LD H, C
            0x62 -> executeLdRegister(::setH, registers.d) // LD H, D
            0x63 -> executeLdRegister(::setH, registers.e) // LD H, E
            0x64 -> executeLdRegister(::setH, registers.h) // LD H, H
            0x65 -> executeLdRegister(::setH, registers.l) // LD H, L
            // L 行
            0x68 -> executeLdRegister(::setL, registers.b) // LD L, B
            0x69 -> executeLdRegister(::setL, registers.c) // LD L, C
            0x6A -> executeLdRegister(::setL, registers.d) // LD L, D
            0x6B -> executeLdRegister(::setL, registers.e) // LD L, E
            0x6C -> executeLdRegister(::setL, registers.h) // LD L, H
            0x6D -> executeLdRegister(::setL, registers.l) // LD L, L
            // A 行の自己コピー
            0x7F -> executeLdRegister(::setA, registers.a) // LD A, A
            else -> error("Unknown opcode: 0x${opcode.toString(16)} at PC=0x${pcBefore.toString(16)}")
        }

    /**
     * CB プレフィックス付き命令の実行。
     *
     * 0xCB オペコードを読み取ったあと、次の 1 バイトを拡張オペコードとして解釈する。
     */
    private fun executeCbPrefixed(): Int {
        val pc = registers.pc
        val cbOpcode = bus.readByte(pc).toInt()
        // 拡張オペコード 1 バイト分 PC を進める
        registers.pc = (pc.toInt() + 1).toUShort()
        return executeCbByOpcode(cbOpcode)
    }

    /**
     * CB xx 命令群のデコード。
     *
     * 現時点では BIT 命令のごく一部（BIT 4, A）のみを実装。
     */
    private fun executeCbByOpcode(cbOpcode: Int): Int =
        when (cbOpcode) {
            0x67 -> executeBitOnRegister(bit = 4, value = registers.a) // BIT 4, A
            else -> error("Unknown CB opcode: 0x${cbOpcode.toString(16)} at PC=0x${registers.pc.toString(16)}")
        }

    /**
     * NOP 命令: 何もしないで 4 サイクル消費する。
     */
    private fun executeNop(): Int = Cycles.NOP

    /**
     * LD r, n 命令群の共通処理: 汎用レジスタ ← 即値 1 バイト。
     *
     * - 対象:
     *   - 0x06: LD B, n
     *   - 0x0E: LD C, n
     *   - 0x16: LD D, n
     *   - 0x1E: LD E, n
     *   - 0x26: LD H, n
     *   - 0x2E: LD L, n
     *   - 0x3E: LD A, n
     * - フラグ: 変化なし
     * - サイクル数: 8
     */
    private fun executeLdImmediate(setTarget: (UByte) -> Unit): Int {
        val pc = registers.pc
        val value = bus.readByte(pc)
        setTarget(value)
        registers.pc = (pc.toInt() + 1).toUShort()
        return Cycles.LD_R_N
    }

    /**
     * 8bit 汎用インクリメント共通処理: r ← r + 1。
     *
     * - フラグ:
     *   - Z: 結果が 0 のとき 1
     *   - N: 0
     *   - H: 下位 4bit に桁上がりがあった場合 1（0x0F -> 0x10 など）
     *   - C: 変化しない
     * - サイクル数:
     *   - レジスタ: 4
     */
    private fun executeInc8(
        get: () -> UByte,
        set: (UByte) -> Unit,
    ): Int {
        val before = get()
        val result = (before + 1u).toUByte()

        set(result)

        registers.flagZ = result == 0u.toUByte()
        registers.flagN = false
        registers.flagH = (before and 0x0Fu) == 0x0Fu.toUByte()

        return Cycles.INC_R
    }

    /**
     * INC (HL) 命令専用: [HL] ← [HL] + 1。
     *
     * - フラグは [executeInc8] と同じ。
     * - サイクル数: 12
     */
    private fun executeIncAtHL(): Int {
        val address = registers.hl
        val before = bus.readByte(address)
        val result = (before + 1u).toUByte()

        bus.writeByte(address, result)

        registers.flagZ = result == 0u.toUByte()
        registers.flagN = false
        registers.flagH = (before and 0x0Fu) == 0x0Fu.toUByte()

        return Cycles.INC_DEC_AT_HL
    }

    /**
     * 16bit 汎用インクリメント共通処理。
     *
     * Game Boy の 16bit INC（BC/DE/HL/SP）はフラグを一切変更しない。
     */
    private fun executeInc16(
        get: () -> UShort,
        set: (UShort) -> Unit,
    ): Int {
        val before = get()
        val result = (before + 1u).toUShort()
        set(result)
        return Cycles.INC_16
    }

    /**
     * 16bit 汎用デクリメント共通処理。
     *
     * Game Boy の 16bit DEC（BC/DE/HL/SP）もフラグを一切変更しない。
     */
    private fun executeDec16(
        get: () -> UShort,
        set: (UShort) -> Unit,
    ): Int {
        val before = get()
        val result = (before - 1u).toUShort()
        set(result)
        return Cycles.DEC_16
    }

    /**
     * LD SP, HL 命令: SP ← HL。
     *
     * - オペコード: 0xF9
     * - フラグ: 変更なし
     * - サイクル数: 8
     */
    private fun executeLdSpFromHl(): Int {
        registers.sp = registers.hl
        return Cycles.LD_SP_HL
    }

    /**
     * LD (nn), SP 命令: メモリ [nn] に SP を保存。
     *
     * - オペコード: 0x08
     * - フラグ: 変更なし
     * - サイクル数: 20
     */
    private fun executeLdMemoryFromSp(): Int {
        val pc = registers.pc
        val address = readWordAt(pc)
        registers.pc = (pc.toInt() + 2).toUShort()

        val sp = registers.sp.toInt()
        val low = (sp and 0xFF).toUByte()
        val high = ((sp shr 8) and 0xFF).toUByte()

        bus.writeByte(address, low)
        bus.writeByte((address.toInt() + 1).toUShort(), high)

        return Cycles.LD_NN_SP
    }

    /**
     * LD HL, SP+e 命令。
     *
     * - オペコード: 0xF8
     * - サイクル数: 12
     * - フラグ:
     *   - Z: 0
     *   - N: 0
     *   - H/C: SP の下位 8bit とオフセットの加算結果に基づく
     */
    private fun executeLdHlFromSpPlusImmediate(): Int {
        val pc = registers.pc
        val offset = bus.readByte(pc)
        registers.pc = (pc.toInt() + 1).toUShort()

        val sp = registers.sp.toInt()
        val e = signExtend(offset)
        val result = sp + e

        // H/C は下位 8bit の加算に対して判定
        val spLow = sp and 0xFF
        val eLow = e and 0xFF
        val sumLow = spLow + eLow

        registers.hl = result.toUShort()
        registers.flagZ = false
        registers.flagN = false
        registers.flagH = (spLow xor eLow xor sumLow) and 0x10 != 0
        registers.flagC = (spLow xor eLow xor sumLow) and 0x100 != 0

        return Cycles.LD_HL_SP_E
    }

    /**
     * 8bit 汎用デクリメント共通処理: r ← r - 1。
     *
     * - フラグ:
     *   - Z: 結果が 0 のとき 1
     *   - N: 1
     *   - H: 下位 4bit で借りが発生した場合 1（0x10 -> 0x0F など）
     *   - C: 変化しない
     * - サイクル数:
     *   - レジスタ: 4
     */
    private fun executeDec8(
        get: () -> UByte,
        set: (UByte) -> Unit,
    ): Int {
        val before = get()
        val result = (before - 1u).toUByte()

        set(result)

        registers.flagZ = result == 0u.toUByte()
        registers.flagN = true
        // 下位 4bit が 0 から借りるときに H=1
        registers.flagH = (before and 0x0Fu) == 0u.toUByte()

        return Cycles.DEC_R
    }

    /**
     * DEC (HL) 命令専用: [HL] ← [HL] - 1。
     *
     * - フラグは [executeDec8] と同じ。
     * - サイクル数: 12
     */
    private fun executeDecAtHL(): Int {
        val address = registers.hl
        val before = bus.readByte(address)
        val result = (before - 1u).toUByte()

        bus.writeByte(address, result)

        registers.flagZ = result == 0u.toUByte()
        registers.flagN = true
        registers.flagH = (before and 0x0Fu) == 0u.toUByte()

        return Cycles.INC_DEC_AT_HL
    }

    /**
     * 16bit 汎用即値ロード: rr ← nn。
     *
     * - オペコード例: LD BC, nn (0x01), LD DE, nn (0x11) など
     * - サイクル数: 12
     */
    private fun executeLd16Immediate(set: (UShort) -> Unit): Int {
        val pc = registers.pc
        val value = readWordAt(pc)
        registers.pc = (pc.toInt() + 2).toUShort()
        set(value)
        return Cycles.LD_RR_NN
    }

    private fun checkCondition(cond: Condition): Boolean =
        when (cond) {
            Condition.NZ -> !registers.flagZ
            Condition.Z -> registers.flagZ
            Condition.NC -> !registers.flagC
            Condition.C -> registers.flagC
        }

    /**
     * 指定アドレスから 16bit の値（リトルエンディアン）を読み出す。
     *
     * - low = [address], high = [address+1] として (high << 8) | low を返す
     */
    private fun readWordAt(address: UShort): UShort {
        val low = bus.readByte(address).toInt()
        val high = bus.readByte((address.toInt() + 1).toUShort()).toInt()
        return ((high shl 8) or low).toUShort()
    }

    private fun signExtend(offset: UByte): Int {
        val raw = offset.toInt()
        return if (raw and 0x80 != 0) raw or -0x100 else raw
    }

    /**
     * A とオペランドを用いた 8bit 算術のコア処理。
     *
     * - この関数は A とフラグを更新するだけで、PC・サイクル数は呼び出し元で扱う。
     */
    private fun applyAlu(
        op: AluOp,
        operand: UByte,
    ) {
        val a = registers.a
        val aInt = a.toInt()
        val bInt = operand.toInt()
        when (op) {
            AluOp.ADD -> {
                val sum = aInt + bInt
                val result = sum and 0xFF
                registers.a = result.toUByte()
                registers.flagZ = result == 0
                registers.flagN = false
                registers.flagH = (aInt and 0x0F) + (bInt and 0x0F) > 0x0F
                registers.flagC = sum > 0xFF
            }
            AluOp.ADC -> {
                val carry = if (registers.flagC) 1 else 0
                val sum = aInt + bInt + carry
                val result = sum and 0xFF
                registers.a = result.toUByte()
                registers.flagZ = result == 0
                registers.flagN = false
                registers.flagH = (aInt and 0x0F) + (bInt and 0x0F) + carry > 0x0F
                registers.flagC = sum > 0xFF
            }
            AluOp.SUB, AluOp.CP -> {
                val diff = aInt - bInt
                val result = diff and 0xFF
                if (op != AluOp.CP) {
                    registers.a = result.toUByte()
                }
                registers.flagZ = result == 0
                registers.flagN = true
                registers.flagH = (aInt and 0x0F) < (bInt and 0x0F)
                registers.flagC = aInt < bInt
            }
            AluOp.SBC -> {
                val carry = if (registers.flagC) 1 else 0
                val diff = aInt - bInt - carry
                val result = diff and 0xFF
                registers.a = result.toUByte()
                registers.flagZ = result == 0
                registers.flagN = true
                registers.flagH = (aInt and 0x0F) < ((bInt and 0x0F) + carry)
                registers.flagC = aInt < bInt + carry
            }
            AluOp.AND -> {
                val result = (aInt and bInt) and 0xFF
                registers.a = result.toUByte()
                registers.flagZ = result == 0
                registers.flagN = false
                registers.flagH = true
                registers.flagC = false
            }
            AluOp.OR -> {
                val result = (aInt or bInt) and 0xFF
                registers.a = result.toUByte()
                registers.flagZ = result == 0
                registers.flagN = false
                registers.flagH = false
                registers.flagC = false
            }
            AluOp.XOR -> {
                val result = (aInt xor bInt) and 0xFF
                registers.a = result.toUByte()
                registers.flagZ = result == 0
                registers.flagN = false
                registers.flagH = false
                registers.flagC = false
            }
        }
    }

    /**
     * DAA 命令: A を BCD（10進）に調整する。
     *
     * - オペコード: 0x27
     * - 直前の演算とフラグ（N/H/C）に応じて A を補正する。
     * - サイクル数: 4
     */
    private fun executeDaa(): Int {
        var a = registers.a.toInt()
        var adjust = 0
        var carry = registers.flagC

        if (!registers.flagN) {
            // 直前が加算系
            if (registers.flagH || (a and 0x0F) > 0x09) {
                adjust += 0x06
            }
            if (carry || a > 0x99) {
                adjust += 0x60
                carry = true
            }
            a = (a + adjust) and 0xFF
        } else {
            // 直前が減算系
            if (registers.flagH) {
                adjust += 0x06
            }
            if (carry) {
                adjust += 0x60
            }
            a = (a - adjust) and 0xFF
        }

        registers.a = a.toUByte()
        registers.flagZ = a == 0
        // N はそのまま（直前の ADD/SUB の種別を保持）
        registers.flagH = false
        registers.flagC = carry

        return Cycles.ALU_R
    }

    /**
     * A, r 形式の ALU 命令（レジスタ版）。
     *
     * - 例: ADD A, B (`0x80`)
     * - サイクル数: 4
     */
    private fun executeAlu(
        op: AluOp,
        operand: UByte,
    ): Int {
        applyAlu(op, operand)
        return Cycles.ALU_R
    }

    /**
     * A, n 形式の ALU 命令（即値版）。
     *
     * - 例: ADD A, n (`0xC6`)
     * - サイクル数: 8
     */
    private fun executeAluImmediate(op: AluOp): Int {
        val pc = registers.pc
        val value = bus.readByte(pc)
        registers.pc = (pc.toInt() + 1).toUShort()

        applyAlu(op, value)

        return Cycles.ALU_N
    }

    /**
     * A, (HL) 形式の ALU 命令。
     *
     * - 例: ADD A, (HL) (`0x86`)
     * - サイクル数: 8
     */
    private fun executeAluFromHL(op: AluOp): Int {
        val address = registers.hl
        val value = bus.readByte(address)
        applyAlu(op, value)
        return Cycles.ALU_FROM_HL
    }

    /**
     * JP nn（無条件）の実装。
     */
    private fun executeJpUnconditional(): Int {
        val pc = registers.pc
        val target = readWordAt(pc)
        registers.pc = target
        return Cycles.JP
    }

    /**
     * JP cc, nn（条件付き）の実装。
     */
    private fun executeJpConditional(cond: Condition): Int {
        val pc = registers.pc
        val target = readWordAt(pc)
        // 即値 2 バイト分を読み飛ばす
        registers.pc = (pc.toInt() + 2).toUShort()
        return if (checkCondition(cond)) {
            registers.pc = target
            Cycles.JP_COND_TAKEN
        } else {
            Cycles.JP_COND_NOT_TAKEN
        }
    }

    /**
     * JR e（無条件）の実装。
     */
    private fun executeJrUnconditional(): Int {
        val pc = registers.pc
        val offset = bus.readByte(pc)
        // 即値 1 バイト分を読み飛ばす
        val pcAfterImmediate = (pc.toInt() + 1).toUShort()
        val newPc = pcAfterImmediate.toInt() + signExtend(offset)
        registers.pc = newPc.toUShort()
        return Cycles.JR
    }

    /**
     * JR cc, e（条件付き）の実装。
     */
    private fun executeJrConditional(cond: Condition): Int {
        val pc = registers.pc
        val offset = bus.readByte(pc)
        // 即値 1 バイト分を読み飛ばす
        val pcAfterImmediate = (pc.toInt() + 1).toUShort()

        return if (checkCondition(cond)) {
            // 条件成立時のみ、次の命令アドレスを基準にオフセットを適用
            val newPc = pcAfterImmediate.toInt() + signExtend(offset)
            registers.pc = newPc.toUShort()
            Cycles.JR_COND_TAKEN
        } else {
            // 条件不成立時は単に即値を読み飛ばすだけ
            registers.pc = pcAfterImmediate
            Cycles.JR_COND_NOT_TAKEN
        }
    }

    /**
     * スタックに 16bit 値を PUSH する共通処理。
     *
     * - Game Boy のスタックは高アドレスから低アドレスへ伸びる。
     * - 仕様通り、[SP-1] に上位バイト, [SP-2] に下位バイトを書き、SP を 2 減少させる。
     */
    private fun pushWord(value: UShort) {
        val spBefore = registers.sp.toInt()
        val high = ((value.toInt() shr 8) and 0xFF).toUByte()
        val low = (value.toInt() and 0xFF).toUByte()

        val spHigh = (spBefore - 1).toUShort()
        val spLow = (spBefore - 2).toUShort()

        bus.writeByte(spHigh, high)
        bus.writeByte(spLow, low)

        registers.sp = spLow
    }

    /**
     * スタックから 16bit 値を POP する共通処理。
     *
     * - [SP] = low, [SP+1] = high を読み、SP を 2 増加させる。
     */
    private fun popWord(): UShort {
        val sp = registers.sp.toInt()
        val low = bus.readByte(sp.toUShort()).toInt()
        val high = bus.readByte((sp + 1).toUShort()).toInt()
        registers.sp = (sp + 2).toUShort()
        return ((high shl 8) or low).toUShort()
    }

    /**
     * PUSH rr（BC/DE/HL/AF）の共通処理。
     *
     * - 渡された 16bit レジスタ値をスタックに積む。
     * - サイクル数: 16
     */
    private fun executePush(get: () -> UShort): Int {
        pushWord(get())
        return Cycles.PUSH
    }

    /**
     * POP rr（BC/DE/HL/AF）の共通処理。
     *
     * - スタックトップを POP して、渡された setter でレジスタに書き戻す。
     * - サイクル数: 12
     */
    private fun executePop(set: (UShort) -> Unit): Int {
        val value = popWord()
        set(value)
        return Cycles.POP
    }

    /**
     * CALL nn（無条件呼び出し）。
     *
     * - オペコード: 0xCD
     * - フォーマット: [0xCD][low][high]
     * - 動作: 次の命令アドレスをスタックに積み、PC を nn へ変更
     */
    private fun executeCallUnconditional(): Int {
        val pcImmediate = registers.pc
        val target = readWordAt(pcImmediate)
        val returnAddress = (pcImmediate.toInt() + 2).toUShort()

        pushWord(returnAddress)
        registers.pc = target

        return Cycles.CALL
    }

    /**
     * CALL cc, nn（条件付き呼び出し）。
     *
     * - 条件成立: CALL と同様に push + PC=nn（24 cycles）
     * - 条件不成立: 即値を読み飛ばしのみ（12 cycles）
     */
    private fun executeCallConditional(cond: Condition): Int {
        val pcImmediate = registers.pc
        val target = readWordAt(pcImmediate)
        val pcAfterImmediate = (pcImmediate.toInt() + 2).toUShort()

        return if (checkCondition(cond)) {
            val returnAddress = pcAfterImmediate
            pushWord(returnAddress)
            registers.pc = target
            Cycles.CALL_COND_TAKEN
        } else {
            registers.pc = pcAfterImmediate
            Cycles.CALL_COND_NOT_TAKEN
        }
    }

    /**
     * RET（無条件リターン）。
     *
     * - オペコード: 0xC9
     * - 動作: スタックからアドレスを POP して PC へ設定
     */
    private fun executeRetUnconditional(): Int {
        registers.pc = popWord()
        return Cycles.RET
    }

    /**
     * RET cc（条件付きリターン）。
     *
     * - 条件成立: POP して PC へ設定（20 cycles）
     * - 条件不成立: 何もせず（8 cycles）
     */
    private fun executeRetConditional(cond: Condition): Int =
        if (checkCondition(cond)) {
            registers.pc = popWord()
            Cycles.RET_COND_TAKEN
        } else {
            Cycles.RET_COND_NOT_TAKEN
        }

    /**
     * RST n（リスタート）。
     *
     * - 対応アドレス: 0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38
     * - 動作: 次の命令アドレスを PUSH して、PC を固定アドレスへ変更
     */
    private fun executeRst(vector: UByte): Int {
        val returnAddress = registers.pc
        pushWord(returnAddress)
        registers.pc = vector.toUShort()
        return Cycles.RST
    }

    /**
     * BIT b, r 命令（レジスタ版）の共通処理。
     *
     * - 指定ビットが 0 なら Z=1、1 なら Z=0
     * - N=0, H=1, C は変更しない
     * - サイクル数: 8
     */
    private fun executeBitOnRegister(
        bit: Int,
        value: UByte,
    ): Int {
        val mask = (1 shl bit).toUByte()
        registers.flagZ = (value and mask) == 0u.toUByte()
        registers.flagN = false
        registers.flagH = true
        // flagC は変更しない
        return Cycles.BIT_R
    }

    /**
     * INC HL 命令: HL レジスタを 1 増加させる。
     *
     * - オペコード: 0x23
     * - フラグ: 変更なし
     * - サイクル数: 8
     */
    private fun executeIncHL(): Int =
        executeInc16(
            get = { registers.hl },
            set = { registers.hl = it },
        )

    /**
     * INC BC 命令: BC レジスタを 1 増加させる。
     *
     * - オペコード: 0x03
     * - フラグ: 変更なし
     * - サイクル数: 8
     */
    private fun executeIncBC(): Int =
        executeInc16(
            get = { registers.bc },
            set = { registers.bc = it },
        )

    /**
     * DEC BC 命令: BC レジスタを 1 減少させる。
     *
     * - オペコード: 0x0B
     * - フラグ: 変更なし
     * - サイクル数: 8
     */
    private fun executeDecBC(): Int =
        executeDec16(
            get = { registers.bc },
            set = { registers.bc = it },
        )

    /**
     * INC DE 命令: DE レジスタを 1 増加させる。
     *
     * - オペコード: 0x13
     * - フラグ: 変更なし
     * - サイクル数: 8
     */
    private fun executeIncDE(): Int =
        executeInc16(
            get = { registers.de },
            set = { registers.de = it },
        )

    /**
     * DEC DE 命令: DE レジスタを 1 減少させる。
     *
     * - オペコード: 0x1B
     * - フラグ: 変更なし
     * - サイクル数: 8
     */
    private fun executeDecDE(): Int =
        executeDec16(
            get = { registers.de },
            set = { registers.de = it },
        )

    /**
     * INC SP 命令: SP レジスタを 1 増加させる。
     *
     * - オペコード: 0x33
     * - フラグ: 変更なし
     * - サイクル数: 8
     */
    private fun executeIncSP(): Int =
        executeInc16(
            get = { registers.sp },
            set = { registers.sp = it },
        )

    /**
     * DEC SP 命令: SP レジスタを 1 減少させる。
     *
     * - オペコード: 0x3B
     * - フラグ: 変更なし
     * - サイクル数: 8
     */
    private fun executeDecSP(): Int =
        executeDec16(
            get = { registers.sp },
            set = { registers.sp = it },
        )

    /**
     * LD A, (HL) 命令: A ← [HL]。
     *
     * - オペコード: 0x7E
     * - フラグ: 変化なし
     * - サイクル数: 8
     */
    private fun executeLdAFromHL(): Int {
        val address = registers.hl
        val value = bus.readByte(address)
        registers.a = value
        return Cycles.LD_A_FROM_HL
    }

    /**
     * LD A, (HL+) 命令: A ← [HL]; HL ← HL + 1。
     *
     * - オペコード: 0x2A
     * - フラグ: 変化なし
     * - サイクル数: 8
     */
    private fun executeLdAFromHLPlus(): Int {
        val address = registers.hl
        val value = bus.readByte(address)
        registers.a = value
        registers.hl = (address + 1u).toUShort()
        return Cycles.LD_A_FROM_HL_INC
    }

    /**
     * LD (HL), A 命令: [HL] ← A。
     *
     * - オペコード: 0x77
     * - フラグ: 変化なし
     * - サイクル数: 8
     */
    private fun executeLdHLFromA(): Int {
        val address = registers.hl
        bus.writeByte(address, registers.a)
        return Cycles.LD_HL_FROM_A
    }

    /**
     * 汎用 8bit ロード: A ← (rr)。
     *
     * - 例: LD A, (BC) / LD A, (DE)
     * - サイクル数: 8
     */
    private fun executeLdAFromIndirect(address: UShort): Int {
        registers.a = bus.readByte(address)
        return Cycles.LD_A_FROM_HL
    }

    /**
     * 汎用 8bit ロード: (rr) ← A。
     *
     * - 例: LD (BC), A / LD (DE), A
     * - サイクル数: 8
     */
    private fun executeLdIndirectFromA(address: UShort): Int {
        bus.writeByte(address, registers.a)
        return Cycles.LD_HL_FROM_A
    }

    /**
     * LD A, (nn) 命令。
     *
     * - オペコード: 0xFA
     * - サイクル数: 16
     */
    private fun executeLdAFromDirectAddress(): Int {
        val pc = registers.pc
        val address = readWordAt(pc)
        registers.pc = (pc.toInt() + 2).toUShort()
        registers.a = bus.readByte(address)
        return 16
    }

    /**
     * LD (nn), A 命令。
     *
     * - オペコード: 0xEA
     * - サイクル数: 16
     */
    private fun executeLdDirectAddressFromA(): Int {
        val pc = registers.pc
        val address = readWordAt(pc)
        registers.pc = (pc.toInt() + 2).toUShort()
        bus.writeByte(address, registers.a)
        return 16
    }

    /**
     * LDH (n), A 命令。高位 I/O/HRAM への書き込み。
     *
     * - 実アドレス: 0xFF00 + n
     * - オペコード: 0xE0
     * - サイクル数: 12
     */
    private fun executeLdhFromAWithImmediateOffset(): Int {
        val pc = registers.pc
        val offset = bus.readByte(pc)
        registers.pc = (pc.toInt() + 1).toUShort()
        val address = (0xFF00 + offset.toInt()).toUShort()
        bus.writeByte(address, registers.a)
        return 12
    }

    /**
     * LDH A, (n) 命令。高位 I/O/HRAM からの読み込み。
     *
     * - 実アドレス: 0xFF00 + n
     * - オペコード: 0xF0
     * - サイクル数: 12
     */
    private fun executeLdhAToImmediateOffset(): Int {
        val pc = registers.pc
        val offset = bus.readByte(pc)
        registers.pc = (pc.toInt() + 1).toUShort()
        val address = (0xFF00 + offset.toInt()).toUShort()
        registers.a = bus.readByte(address)
        return 12
    }

    /**
     * LD (C), A 命令。
     *
     * - 実アドレス: 0xFF00 + C
     * - オペコード: 0xE2
     * - サイクル数: 8
     */
    private fun executeLdhFromAWithCOffset(): Int {
        val address = (0xFF00 + registers.c.toInt()).toUShort()
        bus.writeByte(address, registers.a)
        return 8
    }

    /**
     * LD A, (C) 命令。
     *
     * - 実アドレス: 0xFF00 + C
     * - オペコード: 0xF2
     * - サイクル数: 8
     */
    private fun executeLdhAToCOffset(): Int {
        val address = (0xFF00 + registers.c.toInt()).toUShort()
        registers.a = bus.readByte(address)
        return 8
    }

    /**
     * LD (HL+), A 命令: [HL] ← A; HL ← HL + 1。
     *
     * - オペコード: 0x22
     * - フラグ: 変化なし
     * - サイクル数: 8
     */
    private fun executeLdHLPlusFromA(): Int {
        val address = registers.hl
        bus.writeByte(address, registers.a)
        registers.hl = (address + 1u).toUShort()
        return Cycles.LD_HL_FROM_A_INC
    }

    /**
     * LD r, (HL) 系命令の共通処理: 汎用レジスタ ← [HL]。
     *
     * フラグは変更しない。
     */
    private fun executeLdRegisterFromHL(setTarget: (UByte) -> Unit): Int {
        val address = registers.hl
        val value = bus.readByte(address)
        setTarget(value)
        return Cycles.LD_R_FROM_HL
    }

    /**
     * LD (HL), r 系命令の共通処理: [HL] ← 汎用レジスタ。
     *
     * フラグは変更しない。
     */
    private fun executeLdHLFromRegister(source: UByte): Int {
        val address = registers.hl
        bus.writeByte(address, source)
        return Cycles.LD_HL_FROM_R
    }

    /**
     * レジスタ間コピーの共通処理。
     *
     * @param setTarget 値を書き込む先のレジスタセッター
     * @param source コピー元の値
     */
    private fun executeLdRegister(
        setTarget: (UByte) -> Unit,
        source: UByte,
    ): Int {
        setTarget(source)
        return Cycles.LD_R_R
    }

    // セッター群（ラムダキャプチャを避けて明示的な関数参照にする）
    private fun setA(value: UByte) {
        registers.a = value
    }

    private fun setB(value: UByte) {
        registers.b = value
    }

    private fun setC(value: UByte) {
        registers.c = value
    }

    private fun setD(value: UByte) {
        registers.d = value
    }

    private fun setE(value: UByte) {
        registers.e = value
    }

    private fun setH(value: UByte) {
        registers.h = value
    }

    private fun setL(value: UByte) {
        registers.l = value
    }
}

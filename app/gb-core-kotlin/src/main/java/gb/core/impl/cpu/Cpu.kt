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
        const val ALU_R: Int = 4
        const val ALU_N: Int = 8
        const val ALU_FROM_HL: Int = 8
        const val JP: Int = 16
        const val JP_COND_TAKEN: Int = 16
        const val JP_COND_NOT_TAKEN: Int = 12
        const val JR: Int = 12
        const val JR_COND_TAKEN: Int = 12
        const val JR_COND_NOT_TAKEN: Int = 8
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
            // 16bit INC/DEC
            0x03 -> executeIncBC()
            0x0B -> executeDecBC()
            0x23 -> executeIncHL()
            0x13 -> executeIncDE()
            0x1B -> executeDecDE()
            0x33 -> executeIncSP()
            0x3B -> executeDecSP()
            // HL 自動インクリメント付きロード／ストア
            0x22 -> executeLdHLPlusFromA()
            0x2A -> executeLdAFromHLPlus()
            // HL 経由のメモリアクセス（単発）
            0x7E -> executeLdAFromHL()
            0x77 -> executeLdHLFromA()
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
        registers.pc = pcAfterImmediate
        return if (checkCondition(cond)) {
            val newPc = pcAfterImmediate.toInt() + signExtend(offset)
            registers.pc = newPc.toUShort()
            Cycles.JR_COND_TAKEN
        } else {
            Cycles.JR_COND_NOT_TAKEN
        }
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

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
    /**
     * 1 命令あたりのサイクル数を表す定数群。
     * 今後命令が増えた場合もここに追加していく。
     */
    private object Cycles {
        const val NOP: Int = 4
        const val LD_A_N: Int = 8
        const val INC_A: Int = 4
        const val INC_16: Int = 8
        const val DEC_16: Int = 8
        const val LD_R_R: Int = 4
        const val LD_A_FROM_HL: Int = 8
        const val LD_HL_FROM_A: Int = 8
        const val LD_A_FROM_HL_INC: Int = 8
        const val LD_HL_FROM_A_INC: Int = 8
        const val LD_R_FROM_HL: Int = 8
        const val LD_HL_FROM_R: Int = 8
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
            0x3E -> executeLdAN()
            0x3C -> executeIncA()
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
     * NOP 命令: 何もしないで 4 サイクル消費する。
     */
    private fun executeNop(): Int = Cycles.NOP

    /**
     * LD A, n 命令: 即値 n を A レジスタにロードする。
     *
     * - オペコード: 0x3E
     * - フォーマット: [0x3E][n]
     * - 動作: A ← n, PC は合計 2 バイト進む, 8 サイクル
     */
    private fun executeLdAN(): Int {
        val pc = registers.pc
        val value = bus.readByte(pc)
        registers.a = value
        // 即値 1 バイト分 PC を進める
        registers.pc = (pc.toInt() + 1).toUShort()
        return Cycles.LD_A_N
    }

    /**
     * INC A 命令: A レジスタを 1 増加させる。
     *
     * - オペコード: 0x3C
     * - フラグ:
     *   - Z: 結果が 0 のとき 1、それ以外は 0
     *   - N: 必ず 0（加算なので）
     *   - H: 下位 4bit に桁上がりがあった場合 1（0x0F -> 0x10 など）
     *   - C: 変化しない
     * - サイクル数: 4
     */
    private fun executeIncA(): Int {
        val before = registers.a
        val result = (before + 1u).toUByte()

        registers.a = result

        // フラグ更新
        registers.flagZ = result == 0u.toUByte()
        registers.flagN = false
        registers.flagH = (before and 0x0Fu) == 0x0Fu.toUByte()
        // flagC は変更しない

        return Cycles.INC_A
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

package gb.core.impl.cpu

import kotlin.ExperimentalUnsignedTypes

/**
 * CPU・メモリバス・タイマ・割り込みコントローラを束ねた「本体」クラス。
 *
 * - 1 命令ごとの実行 (`stepInstruction`) と、割り込み受付を担当する。
 * - 将来的に PPU や Joypad もここに統合していく。
 */
@OptIn(ExperimentalUnsignedTypes::class)
class Machine(
    rom: UByteArray,
    cartridgeRam: UByteArray? = null,
) {
    private val interruptController = InterruptController()
    private val timer = Timer(interruptController)
    private val bus =
        SystemBus(
            rom = rom,
            cartridgeRam = cartridgeRam,
            interruptController = interruptController,
            timer = timer,
        )

    val cpu: Cpu = Cpu(bus)

    /**
     * 1 命令を実行し、その間に消費した総サイクル数（割り込みサービスを含む）を返す。
     *
     * - 流れ:
     *   1. CPU で 1 命令実行
     *   2. そのサイクル数をタイマへ通知（DIV/TIMA 更新）
     *   3. IME と IF/IE を見て割り込みがあればサービスし、その分のサイクルもタイマへ通知
     */
    fun stepInstruction(): Int {
        val cycles = cpu.executeInstruction()
        timer.step(cycles)

        val interruptCycles = handleInterrupts()
        if (interruptCycles > 0) {
            timer.step(interruptCycles)
        }

        return cycles + interruptCycles
    }

    private fun handleInterrupts(): Int {
        val pending = interruptController.nextPending(cpu.isInterruptsEnabled()) ?: return 0
        return cpu.serviceInterrupt(pending)
    }
}

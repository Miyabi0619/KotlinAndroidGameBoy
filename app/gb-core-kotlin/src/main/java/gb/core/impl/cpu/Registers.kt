package gb.core.impl.cpu

/**
 * Game Boy CPU のレジスタセットを表すクラス。
 *
 * - 8bit レジスタ: A, B, C, D, E, H, L
 * - 16bit レジスタ: PC, SP
 * - フラグ: Z, N, H, C（F レジスタ相当）
 *
 * 16bit のペアレジスタ（BC, DE, HL, AF）は、プロパティでアクセスできるようにしている。
 */
data class Registers(
    var a: UByte = 0u,
    var b: UByte = 0u,
    var c: UByte = 0u,
    var d: UByte = 0u,
    var e: UByte = 0u,
    var h: UByte = 0u,
    var l: UByte = 0u,
    var pc: UShort = 0u,
    var sp: UShort = 0u,
    var flagZ: Boolean = false,
    var flagN: Boolean = false,
    var flagH: Boolean = false,
    var flagC: Boolean = false,
) {
    /**
     * BC, DE, HL, AF などの 16bit ペアレジスタをまとめて扱うためのヘルパー。
     */
    var bc: UShort
        get() = combine(b, c)
        set(value) {
            b = highByte(value)
            c = lowByte(value)
        }

    var de: UShort
        get() = combine(d, e)
        set(value) {
            d = highByte(value)
            e = lowByte(value)
        }

    var hl: UShort
        get() = combine(h, l)
        set(value) {
            h = highByte(value)
            l = lowByte(value)
        }

    var af: UShort
        get() {
            val f =
                buildFlagsByte(
                    z = flagZ,
                    n = flagN,
                    h = flagH,
                    c = flagC,
                )
            return combine(a, f)
        }
        set(value) {
            a = highByte(value)
            val f = lowByte(value)
            flagZ = f and 0b1000_0000u != 0u.toUByte()
            flagN = f and 0b0100_0000u != 0u.toUByte()
            flagH = f and 0b0010_0000u != 0u.toUByte()
            flagC = f and 0b0001_0000u != 0u.toUByte()
        }

    companion object {
        private fun combine(
            high: UByte,
            low: UByte,
        ): UShort {
            val highInt = high.toInt() shl 8
            val lowInt = low.toInt()
            return (highInt or lowInt).toUShort()
        }

        private fun highByte(value: UShort): UByte {
            return ((value.toInt() shr 8) and 0xFF).toUByte()
        }

        private fun lowByte(value: UShort): UByte {
            return (value.toInt() and 0xFF).toUByte()
        }

        private fun buildFlagsByte(
            z: Boolean,
            n: Boolean,
            h: Boolean,
            c: Boolean,
        ): UByte {
            var result = 0u
            if (z) {
                result = result or 0b1000_0000u
            }
            if (n) {
                result = result or 0b0100_0000u
            }
            if (h) {
                result = result or 0b0010_0000u
            }
            if (c) {
                result = result or 0b0001_0000u
            }
            return result.toUByte()
        }
    }
}

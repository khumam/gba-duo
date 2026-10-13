package com.khumam.gbaduo.companion

/** Raw little-endian GBA memory access. Implemented by NativeCore on-device, faked in tests. */
interface MemoryReader {
    fun u8(addr: Long): Int
    fun u16(addr: Long): Int
    fun u32(addr: Long): Long
}

/** Interprets a pack's declared [ValueSpec]s against live memory. Pure, no Android deps. */
object ValuesReader {

    fun readAll(values: Map<String, ValueSpec>, memory: MemoryReader): Map<String, Long> =
        values.mapValues { (_, spec) -> read(spec, memory) }

    fun read(spec: ValueSpec, memory: MemoryReader): Long {
        val base = spec.addr ?: run {
            val ptrValue = memory.u32(requireNotNull(spec.ptr) { "ValueSpec needs addr or ptr" })
            ptrValue + spec.offset
        }

        var raw: Long = when (spec.type) {
            "u8" -> memory.u8(base).toLong()
            "s8" -> memory.u8(base).toByte().toLong()
            "u16" -> memory.u16(base).toLong()
            "s16" -> memory.u16(base).toShort().toLong()
            "u32" -> memory.u32(base)
            "s32" -> memory.u32(base).toInt().toLong()
            else -> error("Unknown value type '${spec.type}'")
        }

        spec.mask?.let { raw = raw and it }
        if (spec.shift != 0) raw = raw shr spec.shift
        return raw
    }
}

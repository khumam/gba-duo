package com.khumam.gbaduo.companion

import com.khumam.gbaduo.emu.NativeCore

/** [MemoryReader] backed by the loaded native core. */
object NativeCoreMemoryReader : MemoryReader {
    override fun u8(addr: Long): Int = NativeCore.rawRead8(addr) and 0xFF
    override fun u16(addr: Long): Int = NativeCore.rawRead16(addr) and 0xFFFF
    override fun u32(addr: Long): Long = NativeCore.rawRead32(addr) and 0xFFFFFFFFL
}

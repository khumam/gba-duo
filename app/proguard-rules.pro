-keep class com.khumam.gbaduo.emu.NativeCore { *; }

# achievements.c finds this class and its @JvmStatic callbacks (serverCall, onLogin,
# onGameLoad, onEvent) by name over JNI.
-keep class com.khumam.gbaduo.achievements.RetroAchievements {
    public static <methods>;
}

package kotlin.coroutines.jvm.internal;

/**
 * Backward-compatibility stub for plugins compiled with Kotlin 1.8.x / 1.9.x.
 *
 * Kotlin 2.0 removed SpillingKt from the stdlib. Plugins compiled with older Kotlin
 * reference it via bytecode such as:
 *
 *   getstatic  kotlin/coroutines/jvm/internal/SpillingKt.SPILLING_ENABLED Z
 *   invokestatic kotlin/coroutines/jvm/internal/SpillingKt.nullOutSpilledVariable(Ljava/lang/Object;)Ljava/lang/Object;
 *
 * This Java stub satisfies the class-loader at runtime. Java (unlike Kotlin 2.0's K2
 * compiler) has no restriction against third-party code residing in the kotlin.* package.
 */
@SuppressWarnings({"unused", "SpellCheckingInspection"})
public final class SpillingKt {

    /** Matches the @JvmField static field referenced by Kotlin 1.8/1.9-compiled plugins. */
    public static final boolean SPILLING_ENABLED = true;

    /** Kotlin object companion — some call sites access SpillingKt.INSTANCE. */
    public static final SpillingKt INSTANCE = new SpillingKt();

    private SpillingKt() {}

    /**
     * Inline function from Kotlin 1.9 stdlib — nulls out a spilled coroutine variable.
     * Signature: (Ljava/lang/Object;)Ljava/lang/Object;
     * The original implementation was: inline fun <T> nullOutSpilledVariable(value: T): T = null as T
     */
    @SuppressWarnings("TypeParameterUnusedInFormals")
    public static <T> T nullOutSpilledVariable(T value) {
        return null;
    }
}

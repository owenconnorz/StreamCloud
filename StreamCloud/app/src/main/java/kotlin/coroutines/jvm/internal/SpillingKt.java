package kotlin.coroutines.jvm.internal;

/**
 * Backward-compatibility stub for plugins compiled with Kotlin 1.8.x / 1.9.x.
 *
 * Kotlin 2.0 removed SpillingKt from the stdlib. Plugins compiled with older Kotlin
 * reference it via bytecode like:
 *
 *   getstatic kotlin/coroutines/jvm/internal/SpillingKt.SPILLING_ENABLED Z
 *
 * This Java stub satisfies the class-loader at runtime. Java (unlike Kotlin 2.0's K2
 * compiler) has no restriction against third-party code residing in the kotlin.* package.
 *
 * SPILLING_ENABLED = true preserves the original spilling behaviour.
 */
@SuppressWarnings({"unused", "SpellCheckingInspection"})
public final class SpillingKt {

    /** Matches the @JvmField static field that Kotlin 1.9 compiled plugins reference. */
    public static final boolean SPILLING_ENABLED = true;

    /** Kotlin object companion — some code accesses SpillingKt.INSTANCE. */
    public static final SpillingKt INSTANCE = new SpillingKt();

    private SpillingKt() {}
}

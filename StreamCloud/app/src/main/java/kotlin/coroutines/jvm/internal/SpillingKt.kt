@file:Suppress("PackageDirectoryMismatch", "PACKAGE_OR_CLASSIFIER_REDECLARATION",
    "NOTHING_TO_INLINE", "unused")
package kotlin.coroutines.jvm.internal

/**
 * Backward-compatibility stub for plugins compiled with Kotlin 1.8.x / 1.9.x.
 *
 * In Kotlin 1.8.20, SpillingKt was added to the JVM coroutines runtime to control
 * whether local variables are "spilled" into coroutine fields on suspension.  Compiled
 * plugins contain bytecode like:
 *
 *   getstatic kotlin/coroutines/jvm/internal/SpillingKt.SPILLING_ENABLED Z
 *   ifeq ...
 *
 * Kotlin 2.0 removed this class, so loading such a plugin on an app compiled with
 * Kotlin 2.0 throws NoClassDefFoundError.  This empty stub — placed at the same
 * package path — satisfies the class-loader and lets the coroutine state machine
 * continue normally.
 *
 * SPILLING_ENABLED = true preserves the original spilling behavior.
 */
@Suppress("unused")
internal object SpillingKt {
    @JvmField
    val SPILLING_ENABLED: Boolean = true
}

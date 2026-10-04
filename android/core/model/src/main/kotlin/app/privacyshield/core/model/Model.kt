package app.privacyshield.core.model

/*
 * Pure Kotlin domain model. No Android dependencies, so everything that depends
 * only on this module (the risk engine) can be unit-tested and audited on the JVM.
 */

/** Used both for signal severity and for the overall risk level of an app. */
enum class Level { LOW, MEDIUM, HIGH, CRITICAL }

/** Ordered weakest to strongest so comparisons work. */
enum class Confidence { UNAVAILABLE, LOW, MEDIUM, HIGH }

/** How PrivacyShield came to know a fact. Android rarely gives us history, so this matters. */
enum class Provenance { SYSTEM_REPORTED, OBSERVED_BY_DIFF, INFERRED, UNAVAILABLE }

/**
 * Coarse grouping of what a capability exposes. The corroboration gate requires
 * high-weight capabilities from at least two DIFFERENT categories before an app
 * can reach CRITICAL, so one kind of access alone is never enough.
 */
enum class ExposureCategory { SCREEN_CONTROL, COMMUNICATIONS, DEVICE_CONTROL, SENSORS, DATA_ACCESS }

/**
 * A capability an app holds, as reported by Android. [weight] is the documented
 * base points in docs/risk-model.md (generated from this file by RuleCatalog).
 */
enum class Capability(
    val weight: Int,
    val category: ExposureCategory,
    /** Plain-language description used in evidence text. Must state a fact, never an intent. */
    val plainName: String,
    /**
     * First Android API level on which this access can exist at all. Below it the capability is
     * NOT APPLICABLE (nothing to hold), which is different from UNOBSERVABLE (it may exist but we cannot
     * read it). Confirmed on emulators (2026-09-29): the all-files settings screen is absent below API 30.
     */
    val introducedInApi: Int = 23,
) {
    ACCESSIBILITY_CONTENT(25, ExposureCategory.SCREEN_CONTROL, "an enabled accessibility service that can read on-screen content"),
    ACCESSIBILITY_BASIC(10, ExposureCategory.SCREEN_CONTROL, "an enabled accessibility service"),
    NOTIFICATION_LISTENER(15, ExposureCategory.COMMUNICATIONS, "notification access"),
    SMS(12, ExposureCategory.COMMUNICATIONS, "permission to read or receive text messages"),
    DEVICE_ADMIN(12, ExposureCategory.DEVICE_CONTROL, "device administrator status"),
    OVERLAY(10, ExposureCategory.SCREEN_CONTROL, "permission to display over other apps"),
    BACKGROUND_LOCATION(10, ExposureCategory.SENSORS, "permission to access location in the background", introducedInApi = 29),
    CALL_LOG(8, ExposureCategory.COMMUNICATIONS, "permission to read the call log"),
    INSTALL_UNKNOWN_APPS(8, ExposureCategory.DEVICE_CONTROL, "permission to install other apps", introducedInApi = 26),
    ALL_FILES(8, ExposureCategory.DATA_ACCESS, "access to all files on the device", introducedInApi = 30),
    CAMERA(5, ExposureCategory.SENSORS, "camera permission"),
    MICROPHONE(5, ExposureCategory.SENSORS, "microphone permission"),
    USAGE_ACCESS(5, ExposureCategory.DATA_ACCESS, "usage access (can see which apps you use)"),
    CONTACTS(4, ExposureCategory.COMMUNICATIONS, "contacts permission"),
    FINE_LOCATION(4, ExposureCategory.SENSORS, "precise location permission"),
    COARSE_LOCATION(2, ExposureCategory.SENSORS, "approximate location permission");

    val isHighWeight: Boolean get() = weight >= 10

    /** False means "cannot exist on this Android version": do NOT report it as a blind spot. */
    fun isApplicableOn(apiLevel: Int): Boolean = apiLevel >= introducedInApi
}

/** Where the app came from. "Trusted" is decided by the data layer's installer allowlist. */
enum class InstallSource { STORE_TRUSTED, SIDELOADED, UNKNOWN }

enum class ChangeKind(val points: Double, val plainName: String) {
    NEW_HIGH_WEIGHT_GRANT(10.0, "a sensitive capability was newly granted or enabled"),
    SIGNING_CERT_CHANGED(15.0, "the app's signing certificate changed"),
    UPDATE_ADDED_SENSITIVE_PERMISSIONS(8.0, "an update added several sensitive permissions"),
}

/** [observedAtMillis] is when PrivacyShield noticed the change, not necessarily when it happened. */
data class RecentChange(
    val kind: ChangeKind,
    val observedAtMillis: Long,
    val provenance: Provenance = Provenance.OBSERVED_BY_DIFF,
)

/**
 * Facts about one app, gathered by collectors from APIs Android exposes.
 * [label] is attacker-controlled text (an app can name itself anything). It is
 * for display only and must never be interpreted or forwarded to an LLM as instructions.
 */
data class AppFacts(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val installSource: InstallSource,
    val capabilities: Set<Capability>,
    /** Capabilities plausibly expected, e.g. the default SMS/dialer role holder. */
    val roleExpected: Set<Capability> = emptySet(),
    val recentChanges: List<RecentChange> = emptyList(),
    /**
     * Capabilities we could NOT check on this device (old Android version, OEM customisation, API
     * refused). Absence from [capabilities] only means "none found" for capabilities NOT listed here.
     */
    val unobservable: Set<Capability> = emptySet(),
) {
    init {
        require(capabilities.none { it in unobservable }) { "A capability cannot be both found and unobservable" }
    }

    /** System apps are excluded: their installer is normally "unknown" and that says nothing. */
    val hasProvenanceConcern: Boolean get() = !isSystem && installSource != InstallSource.STORE_TRUSTED
}

/**
 * How much PrivacyShield can observe on a given Android version. PROVISIONAL: boundaries are
 * hypotheses to be confirmed by the Phase 0 spike (docs/phase0-spike-plan.md), not verified facts.
 */
enum class SupportTier {
    /** API 33+: the full signal set is expected. */
    FULL,
    /** API 29-32: most signals; some newer signals are absent. */
    STANDARD,
    /** API 23-28: runtime-permission era; inventory and permissions work, fewer special-access signals. */
    BASIC,
    /** Below API 23: install-time permissions only; the permission model differs, so not supported. */
    UNSUPPORTED;

    companion object {
        const val MIN_SUPPORTED_API = 23

        fun forApiLevel(api: Int): SupportTier = when {
            api >= 33 -> FULL
            api >= 29 -> STANDARD
            api >= MIN_SUPPORTED_API -> BASIC
            else -> UNSUPPORTED
        }
    }
}

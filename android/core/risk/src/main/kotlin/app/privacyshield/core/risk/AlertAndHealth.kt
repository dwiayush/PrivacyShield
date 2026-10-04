package app.privacyshield.core.risk

import app.privacyshield.core.model.AppFacts
import app.privacyshield.core.model.Capability
import app.privacyshield.core.model.Confidence
import app.privacyshield.core.model.InstallSource
import app.privacyshield.core.model.Level
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A user-facing alert. Mirrors spec section 36: every alert carries evidence,
 * confidence, explanation, possible causes and a recommended action, plus the
 * "what we don't know" section from section 25.
 */
data class Alert(
    val packageName: String,
    val title: String,
    val level: Level,
    val confidence: Confidence,
    val evidence: List<String>,
    val explanation: String,
    val possibleCauses: List<String>,
    val whatWeDontKnow: List<String>,
    val recommendedActions: List<String>,
)

object AlertPolicy {
    /**
     * Alert only for HIGH or above with at least MEDIUM confidence, and never for apps the user
     * marked as trusted. Trusting an app suppresses the alert; it never changes the score or facts.
     */
    fun shouldAlert(risk: AppRisk, trustedPackages: Set<String>): Boolean =
        risk.packageName !in trustedPackages &&
            risk.level >= Level.HIGH &&
            risk.confidence >= Confidence.MEDIUM

    fun build(risk: AppRisk): Alert = Alert(
        packageName = risk.packageName,
        title = "Potential privacy risk: review recommended",
        level = risk.level,
        confidence = risk.confidence,
        evidence = risk.signals.filter { it.points >= 8.0 }.map { it.evidence },
        explanation = "This app combines several kinds of sensitive access. That can be a sign of an app that " +
            "deserves a closer look, but many legitimate apps need this access to work.",
        possibleCauses = listOf(
            "The app genuinely needs this access for a feature you use.",
            "You enabled the access yourself some time ago and have forgotten.",
            "The app was recently updated and now asks for more access.",
            "The app is not one you recognise or intended to install.",
        ),
        whatWeDontKnow = listOf(
            "We cannot see what this app actually does with this access.",
            "We cannot tell whether the access has been used.",
        ) + risk.blindSpots.map { "On this phone we could not check: ${it.plainName}. The real exposure may be higher." },
        recommendedActions = listOf(
            "Review the app and its access in Settings.",
            "Turn off any access you do not need.",
            "If you do not recognise or trust the app, consider uninstalling it yourself.",
            "Consider running your device's built-in security check.",
        ),
    )
}

data class DeviceFacts(
    val apps: List<AppFacts>,
    /** Packages the user reviewed and chose to trust. Reduces "unreviewed" penalties only. */
    val acknowledged: Set<String> = emptySet(),
    /** Null means we could not determine it. Unknown values are never penalised. */
    val screenLockSet: Boolean? = null,
    val securityPatchAgeDays: Int? = null,
    val adbEnabled: Boolean? = null,
    val osSupported: Boolean? = null,
    val nowMillis: Long,
)

data class HealthPenalty(val category: String, val penalty: Double, val cap: Double, val details: List<String>)

data class DeviceHealth(
    val score: Int,
    val rulesetVersion: String,
    val penalties: List<HealthPenalty>,
    val unknowns: List<String>,
) {
    companion object {
        const val DISCLAIMER =
            "This measures how much sensitive access is configured on your phone and how much you have " +
                "reviewed. It does not detect malware."
    }
}

object DeviceHealthEngine {
    private const val ACK_FACTOR = 0.25

    const val CAP_SPECIAL_ACCESS = 35.0
    const val CAP_SENSITIVE_DATA = 25.0
    const val CAP_PROVENANCE = 15.0
    const val CAP_RECENT_CHANGES = 10.0
    const val CAP_HYGIENE = 15.0

    val specialAccessPenalty: Map<Capability, Double> = mapOf(
        Capability.ACCESSIBILITY_CONTENT to 8.0,
        Capability.ACCESSIBILITY_BASIC to 4.0,
        Capability.NOTIFICATION_LISTENER to 5.0,
        Capability.DEVICE_ADMIN to 4.0,
        Capability.OVERLAY to 3.0,
        Capability.INSTALL_UNKNOWN_APPS to 2.0,
        Capability.ALL_FILES to 2.0,
        Capability.USAGE_ACCESS to 1.0,
    )

    val sensitiveDataPenalty: Map<Capability, Double> = mapOf(
        Capability.BACKGROUND_LOCATION to 2.0,
        Capability.SMS to 2.0,
        Capability.CALL_LOG to 2.0,
        Capability.CAMERA to 1.0,
        Capability.MICROPHONE to 1.0,
        Capability.FINE_LOCATION to 1.0,
        Capability.CONTACTS to 1.0,
        Capability.COARSE_LOCATION to 0.5,
    )

    private const val PROVENANCE_PER_APP = 5.0
    private const val CHANGE_PER_APP = 2.0
    private const val RECENT_DAYS = 14L

    fun compute(facts: DeviceFacts): DeviceHealth {
        val userApps = facts.apps.filter { !it.isSystem }
        val unknowns = mutableListOf<String>()
        val withBlindSpots = facts.apps.count { it.unobservable.isNotEmpty() }
        if (withBlindSpots > 0) {
            unknowns += "For $withBlindSpots apps some access could not be checked on this phone, so the score may understate exposure."
        }

        fun ack(pkg: String) = if (pkg in facts.acknowledged) ACK_FACTOR else 1.0
        fun held(app: AppFacts, cap: Capability) = cap in app.capabilities && cap !in app.roleExpected

        // 1. Special access exposure
        val specialDetails = mutableListOf<String>()
        var special = 0.0
        for (app in userApps) for ((cap, pts) in specialAccessPenalty) {
            if (held(app, cap)) {
                special += pts * ack(app.packageName)
                specialDetails += "${cap.name}: ${app.packageName}"
            }
        }

        // 2. Sensitive-data permissions
        val sensitiveDetails = mutableListOf<String>()
        var sensitive = 0.0
        for (app in userApps) for ((cap, pts) in sensitiveDataPenalty) {
            if (held(app, cap)) {
                sensitive += pts
                sensitiveDetails += "${cap.name}: ${app.packageName}"
            }
        }

        // 3. Provenance
        val nonStore = userApps.filter { it.installSource != InstallSource.STORE_TRUSTED }
        val provenance = nonStore.size * PROVENANCE_PER_APP

        // 4. Unreviewed recent changes
        val recentMillis = RECENT_DAYS * 86_400_000L
        val recentApps = userApps.filter { app ->
            app.recentChanges.any { facts.nowMillis - it.observedAtMillis in 0..recentMillis }
        }
        val changes = recentApps.sumOf { CHANGE_PER_APP * ack(it.packageName) }

        // 5. Device hygiene. Unknown values are reported, never penalised.
        var hygiene = 0.0
        val hygieneDetails = mutableListOf<String>()
        when (facts.screenLockSet) {
            false -> { hygiene += 8.0; hygieneDetails += "No screen lock is set." }
            null -> unknowns += "Screen lock status could not be determined."
            true -> {}
        }
        val patch = facts.securityPatchAgeDays
        if (patch == null) {
            unknowns += "Security patch age could not be determined."
        } else {
            val p = when {
                patch > 365 -> 7.0
                patch > 180 -> 4.0
                patch > 90 -> 2.0
                else -> 0.0
            }
            if (p > 0) { hygiene += p; hygieneDetails += "Security patch is $patch days old." }
        }
        when (facts.adbEnabled) {
            true -> { hygiene += 3.0; hygieneDetails += "USB debugging is on." }
            null -> unknowns += "USB debugging status could not be determined."
            false -> {}
        }
        when (facts.osSupported) {
            false -> { hygiene += 3.0; hygieneDetails += "This Android version no longer receives security updates." }
            null -> unknowns += "Android version support status could not be determined."
            true -> {}
        }

        val penalties = listOf(
            HealthPenalty("SPECIAL_ACCESS", min(special, CAP_SPECIAL_ACCESS), CAP_SPECIAL_ACCESS, specialDetails.sorted()),
            HealthPenalty("SENSITIVE_DATA", min(sensitive, CAP_SENSITIVE_DATA), CAP_SENSITIVE_DATA, sensitiveDetails.sorted()),
            HealthPenalty("PROVENANCE", min(provenance, CAP_PROVENANCE), CAP_PROVENANCE, nonStore.map { it.packageName }.sorted()),
            HealthPenalty("RECENT_CHANGES", min(changes, CAP_RECENT_CHANGES), CAP_RECENT_CHANGES, recentApps.map { it.packageName }.sorted()),
            HealthPenalty("DEVICE_HYGIENE", min(hygiene, CAP_HYGIENE), CAP_HYGIENE, hygieneDetails),
        )
        val score = max(0, min(100, (100.0 - penalties.sumOf { it.penalty }).roundToInt()))
        return DeviceHealth(score, RuleCatalog.RULESET_VERSION, penalties, unknowns)
    }
}

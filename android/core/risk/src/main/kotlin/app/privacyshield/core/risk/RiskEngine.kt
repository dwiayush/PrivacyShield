package app.privacyshield.core.risk

import app.privacyshield.core.model.AppFacts
import app.privacyshield.core.model.Capability
import app.privacyshield.core.model.ChangeKind
import app.privacyshield.core.model.Confidence
import app.privacyshield.core.model.ExposureCategory
import app.privacyshield.core.model.InstallSource
import app.privacyshield.core.model.Level
import app.privacyshield.core.model.Provenance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every constant that influences a score lives here, so docs/risk-model.md can be
 * generated from it and cannot drift from the code. Weights are PROPOSALS pending
 * calibration against a golden corpus of real apps; they are not empirical findings.
 */
object RuleCatalog {
    const val RULESET_VERSION = "2026.09.0"

    const val EXPECTED_MULTIPLIER = 0.5
    const val PROVENANCE_POINTS = 8.0
    const val CHANGE_DECAY_DAYS = 14
    const val MEDIUM_MIN = 15
    const val HIGH_MIN = 35
    const val CRITICAL_MIN = 60
    const val MIN_INDEPENDENT_CATEGORIES_FOR_CRITICAL = 2

    /** Each inner set means "any one of these"; all groups must be satisfied. */
    data class Combination(
        val id: String,
        val groups: List<Set<Capability>>,
        val requiresProvenanceConcern: Boolean,
        val points: Double,
        val description: String,
    )

    private val accessibility = setOf(Capability.ACCESSIBILITY_CONTENT, Capability.ACCESSIBILITY_BASIC)

    val combinations: List<Combination> = listOf(
        Combination(
            "COMBO_OVERLAY_ACCESSIBILITY",
            listOf(setOf(Capability.OVERLAY), accessibility),
            requiresProvenanceConcern = false,
            points = 15.0,
            description = "Overlay permission together with an accessibility service",
        ),
        Combination(
            "COMBO_LISTENER_SMS_NON_STORE",
            listOf(setOf(Capability.NOTIFICATION_LISTENER), setOf(Capability.SMS)),
            requiresProvenanceConcern = true,
            points = 12.0,
            description = "Notification access together with SMS access, from a non-store source",
        ),
        Combination(
            "COMBO_ACCESSIBILITY_ADMIN",
            listOf(accessibility, setOf(Capability.DEVICE_ADMIN)),
            requiresProvenanceConcern = false,
            points = 12.0,
            description = "An accessibility service together with device administrator status",
        ),
    )

    fun levelFor(score: Int): Level = when {
        score >= CRITICAL_MIN -> Level.CRITICAL
        score >= HIGH_MIN -> Level.HIGH
        score >= MEDIUM_MIN -> Level.MEDIUM
        else -> Level.LOW
    }

    fun severityForPoints(points: Double): Level = when {
        // A single signal is never CRITICAL by construction.
        points >= 15.0 -> Level.HIGH
        points >= 8.0 -> Level.MEDIUM
        else -> Level.LOW
    }

    fun remediationFor(category: ExposureCategory): String = when (category) {
        ExposureCategory.SCREEN_CONTROL ->
            "Open Settings and check whether you intentionally enabled this. Keep it only for apps you trust and understand."
        ExposureCategory.COMMUNICATIONS ->
            "Review whether this app needs access to your messages, calls, contacts or notifications for what you use it for."
        ExposureCategory.DEVICE_CONTROL ->
            "Review whether you intentionally allowed this. You can remove it in Settings if you no longer need it."
        ExposureCategory.SENSORS ->
            "Check in Settings that this permission matches what the app does for you. You can set it to 'only while using the app' or turn it off."
        ExposureCategory.DATA_ACCESS ->
            "Check whether this app needs this access. You can turn it off in Settings."
    }

    /** Renders the human-readable rule table. Used to generate docs/risk-model.md. */
    fun toMarkdown(): String = buildString {
        appendLine("Ruleset version: `$RULESET_VERSION`")
        appendLine()
        appendLine("### Capability points (E)")
        appendLine()
        appendLine("| Capability | Points | Category | High-weight (>=10) |")
        appendLine("|---|---|---|---|")
        Capability.entries.sortedByDescending { it.weight }.forEach {
            appendLine("| `${it.name}` | ${it.weight} | ${it.category} | ${if (it.isHighWeight) "yes" else "no"} |")
        }
        appendLine()
        appendLine("Points are multiplied by $EXPECTED_MULTIPLIER for system apps and for capabilities the app plausibly")
        appendLine("holds through a default role (for example the default SMS or dialer app).")
        appendLine("If an app has `ACCESSIBILITY_CONTENT`, `ACCESSIBILITY_BASIC` is not counted separately.")
        appendLine()
        appendLine("### Provenance (P)")
        appendLine()
        appendLine("+$PROVENANCE_POINTS if the app is not a system app, did not come from a trusted store")
        appendLine("(sideloaded or unknown source), and holds at least one high-weight capability.")
        appendLine()
        appendLine("### Documented combinations (C)")
        appendLine()
        appendLine("| Rule | Points | Needs non-store source | Description |")
        appendLine("|---|---|---|---|")
        combinations.forEach {
            appendLine("| `${it.id}` | ${it.points} | ${if (it.requiresProvenanceConcern) "yes" else "no"} | ${it.description} |")
        }
        appendLine()
        appendLine("### Recent change (R)")
        appendLine()
        appendLine("| Change | Points at age 0 |")
        appendLine("|---|---|")
        ChangeKind.entries.forEach { appendLine("| `${it.name}` | ${it.points} |") }
        appendLine()
        appendLine("Points decay linearly to 0 over $CHANGE_DECAY_DAYS days. Each kind counts once (most recent).")
        appendLine()
        appendLine("### Levels and the corroboration gate")
        appendLine()
        appendLine("`score = min(100, round(E + P + C + R))`")
        appendLine()
        appendLine("LOW < $MEDIUM_MIN, MEDIUM $MEDIUM_MIN-${HIGH_MIN - 1}, HIGH $HIGH_MIN-${CRITICAL_MIN - 1}, CRITICAL >= $CRITICAL_MIN.")
        appendLine()
        appendLine("**Gate:** an app reaches CRITICAL only if (a) its high-weight capabilities span at least")
        appendLine("$MIN_INDEPENDENT_CATEGORIES_FOR_CRITICAL different exposure categories, AND (b) at least one of: a provenance")
        appendLine("concern, a recent change, or a documented combination. Otherwise the level is capped at HIGH")
        appendLine("and the result is flagged `cappedByGate`. A single kind of access can never produce CRITICAL.")
    }
}

/** One line of a score's arithmetic. Also serves as the RiskSignal in the spec (section 24). */
data class RiskSignal(
    val ruleId: String,
    val category: SignalCategory,
    val points: Double,
    val severity: Level,
    val confidence: Confidence,
    /** States a fact. Must never state or imply intent. */
    val evidence: String,
    val timestampMillis: Long,
    val remediation: String,
)

enum class SignalCategory { CAPABILITY, PROVENANCE, COMBINATION, RECENT_CHANGE }

data class AppRisk(
    val packageName: String,
    val rulesetVersion: String,
    val rawScore: Double,
    val score: Int,
    val level: Level,
    /** True when the score alone would have been CRITICAL but the corroboration gate capped it. */
    val cappedByGate: Boolean,
    /** Lowest confidence among the meaningful (>= 8 point) signals. */
    val confidence: Confidence,
    /** The full arithmetic behind [score]; sums to [rawScore]. */
    val signals: List<RiskSignal>,
    /**
     * Capabilities we could not check on this device. When non-empty the score is a LOWER BOUND:
     * the UI must say "at least", and must never present the result as a clean bill of health.
     */
    val blindSpots: List<Capability>,
) {
    val isLowerBound: Boolean get() = blindSpots.isNotEmpty()
}

object RiskEngine {
    private const val MILLIS_PER_DAY = 86_400_000L

    /** Pure and deterministic: same facts and same [nowMillis] always give the same result. */
    fun assess(app: AppFacts, nowMillis: Long): AppRisk {
        val signals = mutableListOf<RiskSignal>()

        // Accessibility: don't double count basic + content.
        val caps = if (Capability.ACCESSIBILITY_CONTENT in app.capabilities) {
            app.capabilities - Capability.ACCESSIBILITY_BASIC
        } else {
            app.capabilities
        }

        // E: capabilities
        for (cap in caps.sortedWith(compareByDescending<Capability> { it.weight }.thenBy { it.name })) {
            val expected = app.isSystem || cap in app.roleExpected
            val points = cap.weight * (if (expected) RuleCatalog.EXPECTED_MULTIPLIER else 1.0)
            signals += RiskSignal(
                ruleId = "CAP_${cap.name}",
                category = SignalCategory.CAPABILITY,
                points = points,
                severity = RuleCatalog.severityForPoints(points),
                confidence = Confidence.HIGH, // reported directly by Android
                evidence = "This app has ${cap.plainName}." +
                    if (expected) " This is common for system apps and default-role apps." else "",
                timestampMillis = nowMillis,
                remediation = RuleCatalog.remediationFor(cap.category),
            )
        }

        // P: provenance
        val hasHighWeight = caps.any { it.isHighWeight }
        if (app.hasProvenanceConcern && hasHighWeight) {
            val sideloaded = app.installSource == InstallSource.SIDELOADED
            signals += RiskSignal(
                ruleId = "PROV_HIGH_WEIGHT_NON_STORE",
                category = SignalCategory.PROVENANCE,
                points = RuleCatalog.PROVENANCE_POINTS,
                severity = RuleCatalog.severityForPoints(RuleCatalog.PROVENANCE_POINTS),
                confidence = if (sideloaded) Confidence.HIGH else Confidence.MEDIUM,
                evidence = if (sideloaded) {
                    "This app was not installed from a trusted app store and holds sensitive access."
                } else {
                    "We could not determine where this app was installed from, and it holds sensitive access."
                },
                timestampMillis = nowMillis,
                remediation = "Confirm you know where this app came from. If you do not recognise it, consider removing it.",
            )
        }

        // C: documented combinations
        val firedCombos = RuleCatalog.combinations.filter { combo ->
            combo.groups.all { group -> group.any { it in app.capabilities } } &&
                (!combo.requiresProvenanceConcern || app.hasProvenanceConcern)
        }
        for (combo in firedCombos) {
            val expected = app.isSystem || combo.groups.all { g -> g.any { it in app.capabilities && it in app.roleExpected } }
            val points = combo.points * (if (expected) RuleCatalog.EXPECTED_MULTIPLIER else 1.0)
            signals += RiskSignal(
                ruleId = combo.id,
                category = SignalCategory.COMBINATION,
                points = points,
                severity = RuleCatalog.severityForPoints(points),
                confidence = Confidence.HIGH,
                evidence = "${combo.description}. Combinations like this deserve a closer look, " +
                    "but some legitimate apps also use them.",
                timestampMillis = nowMillis,
                remediation = "Review this app's access in Settings and keep only what you need.",
            )
        }

        // R: recent change, linear decay, one entry per kind (most recent)
        val decayMillis = RuleCatalog.CHANGE_DECAY_DAYS * MILLIS_PER_DAY
        val latestPerKind = app.recentChanges.groupBy { it.kind }.mapValues { (_, v) -> v.maxBy { it.observedAtMillis } }
        for (kind in ChangeKind.entries) {
            val change = latestPerKind[kind] ?: continue
            val age = max(0L, nowMillis - change.observedAtMillis) // future timestamps (clock skew) count as age 0
            val factor = max(0.0, 1.0 - age.toDouble() / decayMillis)
            val points = kind.points * factor
            if (points <= 0.0) continue
            signals += RiskSignal(
                ruleId = "CHG_${kind.name}",
                category = SignalCategory.RECENT_CHANGE,
                points = points,
                severity = RuleCatalog.severityForPoints(points),
                confidence = when (change.provenance) {
                    Provenance.SYSTEM_REPORTED, Provenance.OBSERVED_BY_DIFF -> Confidence.HIGH
                    Provenance.INFERRED -> Confidence.MEDIUM
                    Provenance.UNAVAILABLE -> Confidence.LOW
                },
                evidence = "Recently noticed: ${kind.plainName}.",
                timestampMillis = change.observedAtMillis,
                remediation = "Check whether this change was expected, for example after an update you approved.",
            )
        }

        val ordered = signals.sortedWith(compareByDescending<RiskSignal> { it.points }.thenBy { it.ruleId })
        val raw = ordered.sumOf { it.points }
        val score = min(100, raw.roundToInt())
        val unGated = RuleCatalog.levelFor(score)

        val independentCategories = caps.filter { it.isHighWeight }.map { it.category }.toSet().size
        val corroborated = app.hasProvenanceConcern ||
            ordered.any { it.category == SignalCategory.RECENT_CHANGE } ||
            firedCombos.isNotEmpty()
        val gatePassed = independentCategories >= RuleCatalog.MIN_INDEPENDENT_CATEGORIES_FOR_CRITICAL && corroborated
        val capped = unGated == Level.CRITICAL && !gatePassed
        val level = if (capped) Level.HIGH else unGated

        val meaningful = ordered.filter { it.points >= 8.0 }
        val confidence = meaningful.minOfOrNull { it.confidence } ?: Confidence.HIGH

        val blind = app.unobservable.sortedBy { it.name }
        return AppRisk(app.packageName, RuleCatalog.RULESET_VERSION, raw, score, level, capped, confidence, ordered, blind)
    }
}

import app.privacyshield.core.risk.RuleCatalog

/** Prints docs/risk-model.md body from the live rule catalog. CI regenerates and diffs it. */
fun main() = print(RuleCatalog.toMarkdown())

package com.dtech.apkinspector.analyzer

object RiskEngine {
    enum class RiskLevel { LOW, MEDIUM, HIGH }

    data class RiskReport(
        val level: RiskLevel,
        val score: Int,
        val factors: List<String>
    )

    fun calculateRisk(permissions: List<String>, isDebuggable: Boolean, libs: List<NativeLibAnalyzer.LibInfo>): RiskReport {
        var score = 0
        val factors = mutableListOf<String>()

        if (isDebuggable) {
            score += 50
            factors.add("App is Debuggable (High Security Risk)")
        }

        val dangerousPerms = listOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.READ_SMS",
            "android.permission.ACCESS_FINE_LOCATION"
        )

        permissions.forEach { perm ->
            if (dangerousPerms.any { perm.contains(it) }) {
                score += 10
                factors.add("Dangerous Permission: $perm")
            }
        }

        if (libs.isEmpty()) {
            // Pure Java/Kotlin app usually safer from buffer overflows than native, but not always.
        } else {
             score += 5
             factors.add("Contains Native Code (${libs.size} libs)")
        }

        val level = when {
            score >= 50 -> RiskLevel.HIGH
            score >= 20 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        return RiskReport(level, score, factors)
    }
}

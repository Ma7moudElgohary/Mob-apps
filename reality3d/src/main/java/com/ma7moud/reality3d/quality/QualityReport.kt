package com.ma7moud.reality3d.quality

/** How good a capture is, 0 to 100, and what held it back. */
data class QualityReport(val score: Int, val issues: List<String>) {

    val grade: String
        get() = when {
            score >= 85 -> "Excellent"
            score >= 70 -> "Good"
            score >= 50 -> "Fair"
            else -> "Poor"
        }
}

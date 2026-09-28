package com.ma7moud.reality3d.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ma7moud.reality3d.quality.QualityReport

private val Excellent = Color(0xFF5BE49B)
private val Weak = Color(0xFFFFC857)

/** A capture's score out of 100, what held it back, and an optional way to improve it. */
@Composable
internal fun QualityCard(title: String, report: QualityReport, goodText: String, action: (@Composable () -> Unit)? = null) {
    val color = when {
        report.score >= 85 -> Excellent
        report.score >= 70 -> MaterialTheme.colorScheme.primary
        else -> Weak
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${report.score}/100 · ${report.grade}", style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(progress = { report.score / 100f }, modifier = Modifier.fillMaxWidth(), color = color)
            if (report.issues.isEmpty()) {
                Text(goodText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                report.issues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            action?.invoke()
        }
    }
}

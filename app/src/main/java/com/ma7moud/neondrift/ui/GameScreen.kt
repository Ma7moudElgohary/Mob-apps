package com.ma7moud.neondrift.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ma7moud.neondrift.game.EnergyOrb
import com.ma7moud.neondrift.game.GameEngine
import com.ma7moud.neondrift.game.GamePhase
import com.ma7moud.neondrift.game.GameState
import com.ma7moud.neondrift.game.Obstacle
import com.ma7moud.neondrift.game.Player
import kotlinx.coroutines.isActive
import kotlin.math.sin

@Composable
fun NeonDriftApp() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val engine = remember { GameEngine() }
    val prefs = remember { context.getSharedPreferences("neon_drift", Context.MODE_PRIVATE) }

    var state by remember { mutableStateOf(GameState()) }
    var best by remember { mutableIntStateOf(prefs.getInt("best_score", 0)) }
    var lastFrame by remember { mutableLongStateOf(0L) }
    var lastCollected by remember { mutableIntStateOf(0) }

    fun start() {
        state = engine.newGame()
        lastFrame = 0L
        lastCollected = 0
    }

    fun pause() {
        if (state.phase == GamePhase.Running) {
            state = state.copy(phase = GamePhase.Paused)
            lastFrame = 0L
        }
    }

    fun resume() {
        if (state.phase == GamePhase.Paused) {
            state = state.copy(phase = GamePhase.Running)
            lastFrame = 0L
        }
    }

    BackHandler(enabled = state.phase == GamePhase.Running) { pause() }

    LaunchedEffect(state.phase) {
        if (state.phase != GamePhase.Running) return@LaunchedEffect
        while (isActive && state.phase == GamePhase.Running) {
            withFrameNanos { now ->
                if (lastFrame == 0L) {
                    lastFrame = now
                    return@withFrameNanos
                }
                val oldPhase = state.phase
                state = engine.step(state, (now - lastFrame) / 1_000_000_000f)
                lastFrame = now

                if (state.collected > lastCollected) {
                    lastCollected = state.collected
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                if (oldPhase == GamePhase.Running && state.phase == GamePhase.GameOver) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == GamePhase.GameOver && state.score > best) {
            best = state.score
            prefs.edit().putInt("best_score", best).apply()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF030712))) {
        GameCanvas(
            state = state,
            onMove = { x -> if (state.phase == GamePhase.Running) state = engine.movePlayer(state, x) },
        )

        if (state.phase == GamePhase.Running || state.phase == GamePhase.Paused) {
            Hud(state, best) { if (state.phase == GamePhase.Running) pause() else resume() }
        }

        when (state.phase) {
            GamePhase.Ready -> CenterCard {
                Title()
                Text("Dodge red gates. Collect energy. Survive the acceleration.", textAlign = TextAlign.Center, color = Muted)
                Spacer(Modifier.height(12.dp))
                Text("Drag anywhere to steer", color = Cyan, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(22.dp))
                PrimaryButton("LAUNCH", ::start)
                Spacer(Modifier.height(12.dp))
                Text("BEST  $best", color = Purple, fontWeight = FontWeight.Bold)
            }
            GamePhase.Paused -> CenterCard {
                Text("PAUSED", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(20.dp))
                PrimaryButton("RESUME", ::resume)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = ::start, modifier = Modifier.fillMaxWidth()) { Text("RESTART") }
            }
            GamePhase.GameOver -> CenterCard {
                Text("SIGNAL LOST", color = Danger, fontWeight = FontWeight.Black)
                Text("${state.score}", color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.Black)
                Text("BEST $best   •   ENERGY ${state.collected}", color = Muted)
                Spacer(Modifier.height(20.dp))
                PrimaryButton("RUN AGAIN", ::start, Purple)
            }
            GamePhase.Running -> Unit
        }
    }
}

@Composable
private fun GameCanvas(state: GameState, onMove: (Float) -> Unit) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(state.phase) {
                detectTapGestures { onMove(it.x / size.width) }
            }
            .pointerInput(state.phase) {
                detectDragGestures(
                    onDragStart = { onMove(it.x / size.width) },
                    onDrag = { change, _ ->
                        onMove(change.position.x / size.width)
                        change.consume()
                    },
                )
            },
    ) {
        drawBackground(state.elapsedSeconds)
        state.orbs.forEach { drawOrb(it, state.elapsedSeconds) }
        state.obstacles.forEach { drawObstacle(it) }
        drawPlayer(state.player, state.elapsedSeconds, state.phase == GamePhase.Running)
    }
}

private fun DrawScope.drawBackground(time: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF02040C), Color(0xFF071229), Color(0xFF02040C))))
    repeat(40) { i ->
        val x = ((i * 73) % 997) / 997f * size.width
        val y = ((((i * 193) % 991) / 991f + time * (0.012f + (i % 5) * 0.002f)) % 1f) * size.height
        drawCircle(Color.White.copy(alpha = 0.15f + (i % 4) * 0.07f), 1f + (i % 3) * 0.5f, Offset(x, y))
    }
    drawLine(Cyan.copy(alpha = 0.18f), Offset(size.width * 0.08f, 0f), Offset(size.width * 0.08f, size.height), 2f)
    drawLine(Purple.copy(alpha = 0.18f), Offset(size.width * 0.92f, 0f), Offset(size.width * 0.92f, size.height), 2f)
}

private fun DrawScope.drawPlayer(player: Player, time: Float, moving: Boolean) {
    val cx = player.x * size.width
    val cy = player.y * size.height
    val r = player.radius * size.width
    drawCircle(Cyan.copy(alpha = 0.13f), r * (1.7f + sin(time * 7f) * 0.08f), Offset(cx, cy))

    if (moving) {
        val trail = Path().apply {
            moveTo(cx - r * 0.35f, cy + r * 0.55f)
            lineTo(cx, cy + r * 2.1f)
            lineTo(cx + r * 0.35f, cy + r * 0.55f)
            close()
        }
        drawPath(trail, Brush.verticalGradient(listOf(Purple.copy(alpha = 0.85f), Color.Transparent), cy, cy + r * 2.2f))
    }

    val ship = Path().apply {
        moveTo(cx, cy - r)
        lineTo(cx + r * 0.8f, cy + r * 0.85f)
        lineTo(cx, cy + r * 0.42f)
        lineTo(cx - r * 0.8f, cy + r * 0.85f)
        close()
    }
    drawPath(ship, Cyan)
    drawCircle(Purple, r * 0.22f, Offset(cx, cy + r * 0.18f))
}

private fun DrawScope.drawObstacle(obstacle: Obstacle) {
    val w = obstacle.width * size.width
    val h = obstacle.height * size.height
    val x = obstacle.x * size.width - w / 2f
    val y = obstacle.y * size.height - h / 2f
    drawRoundRect(
        brush = Brush.horizontalGradient(listOf(Danger, Color(0xFFFF9A4D)), x, x + w),
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.25f),
    )
}

private fun DrawScope.drawOrb(orb: EnergyOrb, time: Float) {
    val center = Offset(orb.x * size.width, orb.y * size.height)
    val r = orb.radius * size.width
    drawCircle(Green.copy(alpha = 0.12f), r * (2.0f + sin(time * 8f + orb.id) * 0.15f), center)
    drawCircle(Green, r, center)
    drawCircle(Color.White.copy(alpha = 0.8f), r * 0.28f, center - Offset(r * 0.25f, r * 0.25f))
}

@Composable
private fun Hud(state: GameState, best: Int, togglePause: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 42.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column {
            Text(state.score.toString().padStart(5, '0'), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text("BEST $best   •   ⚡ ${state.collected}", color = Muted, fontSize = 12.sp)
        }
        IconButton(onClick = togglePause) {
            Text(if (state.phase == GamePhase.Paused) "▶" else "Ⅱ", color = Cyan, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun CenterCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            Modifier.padding(28.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xEB0A1020)),
        ) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        }
    }
}

@Composable
private fun Title() {
    Text("NEON\nDRIFT", textAlign = TextAlign.Center, color = Color.White, fontSize = 42.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, color: Color = Cyan) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = if (color == Cyan) Color(0xFF00191D) else Color.White),
    ) { Text(label, fontWeight = FontWeight.Black) }
}

private val Cyan = Color(0xFF54F7FF)
private val Purple = Color(0xFFB84DFF)
private val Danger = Color(0xFFFF4D6D)
private val Green = Color(0xFF8CFF62)
private val Muted = Color(0xFF9FB4CC)

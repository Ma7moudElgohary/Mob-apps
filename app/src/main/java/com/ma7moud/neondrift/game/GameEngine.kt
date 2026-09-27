package com.ma7moud.neondrift.game

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class GameEngine(
    private val random: Random = Random.Default,
) {
    fun newGame(): GameState = GameState(phase = GamePhase.Running)

    fun movePlayer(state: GameState, normalizedX: Float): GameState {
        val clamped = normalizedX.coerceIn(0.06f, 0.94f)
        return state.copy(player = state.player.copy(x = clamped))
    }

    fun step(state: GameState, deltaSeconds: Float): GameState {
        if (state.phase != GamePhase.Running || deltaSeconds <= 0f) return state

        val dt = deltaSeconds.coerceAtMost(0.05f)
        val elapsed = state.elapsedSeconds + dt
        val speed = speedAt(elapsed)

        var nextId = state.nextId
        var obstacleTimer = state.obstacleSpawnTimer + dt
        var orbTimer = state.orbSpawnTimer + dt
        var obstacles = state.obstacles.map { it.copy(y = it.y + speed * dt) }
        var orbs = state.orbs.map { it.copy(y = it.y + speed * 0.86f * dt) }

        val obstacleInterval = obstacleIntervalAt(elapsed)
        if (obstacleTimer >= obstacleInterval) {
            obstacleTimer -= obstacleInterval
            obstacles = obstacles + spawnObstacle(nextId++, elapsed)
        }

        val orbInterval = orbIntervalAt(elapsed)
        if (orbTimer >= orbInterval) {
            orbTimer -= orbInterval
            orbs = orbs + spawnOrb(nextId++)
        }

        obstacles = obstacles.filter { it.y < 1.14f }
        orbs = orbs.filter { it.y < 1.12f }

        if (obstacles.any { collides(state.player, it) }) {
            return state.copy(
                phase = GamePhase.GameOver,
                obstacles = obstacles,
                orbs = orbs,
                elapsedSeconds = elapsed,
            )
        }

        val collectedOrbs = orbs.filter { collides(state.player, it) }
        val collectedIds = collectedOrbs.mapTo(mutableSetOf()) { it.id }
        orbs = orbs.filterNot { it.id in collectedIds }

        val collectedCount = state.collected + collectedOrbs.size
        val survivalScore = (elapsed * 10f).toInt()
        val score = survivalScore + collectedCount * 50

        return state.copy(
            obstacles = obstacles,
            orbs = orbs,
            score = score,
            collected = collectedCount,
            elapsedSeconds = elapsed,
            obstacleSpawnTimer = obstacleTimer,
            orbSpawnTimer = orbTimer,
            nextId = nextId,
        )
    }

    fun speedAt(elapsedSeconds: Float): Float =
        min(0.62f, 0.24f + elapsedSeconds * 0.0065f)

    fun obstacleIntervalAt(elapsedSeconds: Float): Float =
        max(0.42f, 0.92f - elapsedSeconds * 0.008f)

    private fun orbIntervalAt(elapsedSeconds: Float): Float =
        max(1.65f, 2.6f - elapsedSeconds * 0.006f)

    private fun spawnObstacle(id: Long, elapsedSeconds: Float): Obstacle {
        val width = random.nextDouble(0.11, 0.20).toFloat()
        val height = random.nextDouble(0.042, 0.070).toFloat()
        val margin = width / 2f + 0.02f
        return Obstacle(
            id = id,
            x = random.nextDouble(margin.toDouble(), (1f - margin).toDouble()).toFloat(),
            y = -0.08f,
            width = width,
            height = height,
            spin = random.nextDouble(-1.0, 1.0).toFloat() * (1f + elapsedSeconds / 30f),
        )
    }

    private fun spawnOrb(id: Long): EnergyOrb = EnergyOrb(
        id = id,
        x = random.nextDouble(0.10, 0.90).toFloat(),
        y = -0.06f,
    )

    companion object {
        fun collides(player: Player, obstacle: Obstacle): Boolean {
            val closestX = player.x.coerceIn(
                obstacle.x - obstacle.width / 2f,
                obstacle.x + obstacle.width / 2f,
            )
            val closestY = player.y.coerceIn(
                obstacle.y - obstacle.height / 2f,
                obstacle.y + obstacle.height / 2f,
            )
            val dx = player.x - closestX
            val dy = player.y - closestY
            return dx * dx + dy * dy <= player.radius * player.radius
        }

        fun collides(player: Player, orb: EnergyOrb): Boolean {
            val dx = player.x - orb.x
            val dy = player.y - orb.y
            val combined = player.radius + orb.radius
            return dx * dx + dy * dy <= combined * combined
        }
    }
}

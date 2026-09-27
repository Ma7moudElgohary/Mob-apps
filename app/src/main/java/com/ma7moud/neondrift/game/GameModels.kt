package com.ma7moud.neondrift.game

enum class GamePhase {
    Ready,
    Running,
    Paused,
    GameOver,
}

data class Player(
    val x: Float = 0.5f,
    val y: Float = 0.84f,
    val radius: Float = 0.045f,
)

data class Obstacle(
    val id: Long,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val spin: Float,
)

data class EnergyOrb(
    val id: Long,
    val x: Float,
    val y: Float,
    val radius: Float = 0.026f,
)

data class GameState(
    val phase: GamePhase = GamePhase.Ready,
    val player: Player = Player(),
    val obstacles: List<Obstacle> = emptyList(),
    val orbs: List<EnergyOrb> = emptyList(),
    val score: Int = 0,
    val collected: Int = 0,
    val elapsedSeconds: Float = 0f,
    val obstacleSpawnTimer: Float = 0f,
    val orbSpawnTimer: Float = 0f,
    val nextId: Long = 1L,
)

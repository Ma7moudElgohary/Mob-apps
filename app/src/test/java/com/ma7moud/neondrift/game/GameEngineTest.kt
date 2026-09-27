package com.ma7moud.neondrift.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameEngineTest {
    @Test
    fun `player movement is clamped inside track`() {
        val engine = GameEngine(Random(1))
        val state = GameState()

        assertEquals(0.06f, engine.movePlayer(state, -2f).player.x)
        assertEquals(0.94f, engine.movePlayer(state, 3f).player.x)
    }

    @Test
    fun `circle collides with obstacle rectangle`() {
        val player = Player(x = 0.5f, y = 0.84f, radius = 0.05f)
        val hit = Obstacle(1, 0.5f, 0.84f, 0.12f, 0.06f, 0f)
        val miss = Obstacle(2, 0.15f, 0.20f, 0.12f, 0.06f, 0f)

        assertTrue(GameEngine.collides(player, hit))
        assertFalse(GameEngine.collides(player, miss))
    }

    @Test
    fun `difficulty increases over time but stays bounded`() {
        val engine = GameEngine(Random(2))

        assertTrue(engine.speedAt(60f) > engine.speedAt(0f))
        assertTrue(engine.obstacleIntervalAt(60f) < engine.obstacleIntervalAt(0f))
        assertEquals(0.62f, engine.speedAt(1000f))
        assertEquals(0.42f, engine.obstacleIntervalAt(1000f))
    }

    @Test
    fun `paused state does not advance`() {
        val engine = GameEngine(Random(3))
        val paused = GameState(phase = GamePhase.Paused, score = 123)

        assertEquals(paused, engine.step(paused, 1f))
    }
}

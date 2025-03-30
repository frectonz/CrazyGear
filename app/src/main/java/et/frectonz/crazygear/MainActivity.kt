package et.frectonz.crazygear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.android.awaitFrame
import java.util.UUID
import kotlin.math.*
import kotlin.random.Random

// --- Game State Enum ---
enum class GameState {
    Running,
    GameOver
}

// --- Game Constants ---
const val GRAVITY = 9.8f * 150f
const val HORIZONTAL_IMPULSE = 550f
const val VERTICAL_JUMP_IMPULSE = -650f
const val FRICTION_FACTOR = 0.98f
val GEAR_RADIUS = 30.dp

// --- Obstacle Constants ---
const val OBSTACLE_FALL_SPEED = 220f
val OBSTACLE_WIDTH_MIN = 40.dp
val OBSTACLE_WIDTH_MAX = 120.dp
val OBSTACLE_HEIGHT = 25.dp
const val OBSTACLE_SPAWN_DELAY_MS = 1400L

// --- Data Class for Obstacles ---
data class Obstacle(
    val id: String = UUID.randomUUID().toString(),
    val position: Offset,
    val size: Size,
    val color: Color
) {
    // Helper to get the bounding box of the obstacle
    val bounds: Rect
        get() = Rect(position, size = size)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                    GearGame()
                }
            }
        }
    }
}

@Composable
fun GearGame() {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current.density
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()
        val gearRadiusPx = remember { GEAR_RADIUS.toPx(density) }

        val obstacleHeightPx = remember { OBSTACLE_HEIGHT.toPx(density) }
        val obstacleWidthMinPx = remember { OBSTACLE_WIDTH_MIN.toPx(density) }
        val obstacleWidthMaxPx = remember { OBSTACLE_WIDTH_MAX.toPx(density) }

        // --- State Variables ---
        var gameState by remember { mutableStateOf(GameState.Running) }
        var gearPosition by remember { mutableStateOf(Offset.Zero) }
        var gearVelocity by remember { mutableStateOf(Offset.Zero) }
        val obstacles = remember { mutableStateListOf<Obstacle>() }
        var lastSpawnTime by remember { mutableLongStateOf(0L) }

        // Function to reset the game state
        fun resetGame() {
            gearPosition = Offset(screenWidthPx / 2f, screenHeightPx / 2f + gearRadiusPx * 4)
            gearVelocity = Offset.Zero
            obstacles.clear()
            lastSpawnTime = 0L // Reset spawn timer
            gameState = GameState.Running
        }

        // Initialize gear position correctly once dimensions are known
        LaunchedEffect(screenWidthPx, screenHeightPx) {
            if (gearPosition == Offset.Zero) {
                resetGame()
            }
        }


        // --- Input Handling ---
        val inputModifier = Modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                when (gameState) {
                    GameState.Running -> {
                        val tapX = offset.x
                        val jumpVelocityY = VERTICAL_JUMP_IMPULSE
                        gearVelocity = if (tapX < size.width / 2) {
                            Offset(-HORIZONTAL_IMPULSE, jumpVelocityY)
                        } else {
                            Offset(HORIZONTAL_IMPULSE, jumpVelocityY)
                        }
                    }
                    GameState.GameOver -> {
                        resetGame()
                    }
                }
            }
        }

        // --- Game Loop ---
        LaunchedEffect(gameState) {
            if (gameState != GameState.Running) return@LaunchedEffect

            var lastFrameTime = System.nanoTime()
            // *** NOTE: Error on Line 67 is likely before this loop starts or unrelated to it ***
            while (gameState == GameState.Running) {
                val currentNanoTime = awaitFrame()
                val currentTimeMillis = currentNanoTime / 1_000_000
                val deltaTime = (currentNanoTime - lastFrameTime) / 1_000_000_000.0f
                val dt = min(deltaTime, 0.03f)
                lastFrameTime = currentNanoTime

                // --- Gear Physics ---
                val vyAfterGravity = gearVelocity.y + GRAVITY * dt
                val vxAfterFriction = gearVelocity.x * (1.0f - (1.0f - FRICTION_FACTOR) * dt * 60)
                gearVelocity = Offset(vxAfterFriction, vyAfterGravity)

                var nextX = gearPosition.x + gearVelocity.x * dt
                var nextY = gearPosition.y + gearVelocity.y * dt

                // Boundary Checks... (omitted for brevity, same as before)
                if (nextY > screenHeightPx - gearRadiusPx) {
                    nextY = screenHeightPx - gearRadiusPx
                    gearVelocity = gearVelocity.copy(y = 0f, x = gearVelocity.x * 0.85f)
                    gameState = GameState.GameOver
                } else if (nextY < gearRadiusPx) {
                    nextY = gearRadiusPx
                    if (gearVelocity.y < 0) { gearVelocity = gearVelocity.copy(y = 0f) }
                }
                if (nextX < gearRadiusPx) {
                    nextX = gearRadiusPx
                    gearVelocity = gearVelocity.copy(x = -gearVelocity.x * 0.3f)
                } else if (nextX > screenWidthPx - gearRadiusPx) {
                    nextX = screenWidthPx - gearRadiusPx
                    gearVelocity = gearVelocity.copy(x = -gearVelocity.x * 0.3f)
                }
                gearPosition = Offset(nextX, nextY)


                // --- Obstacle Logic ---

                // Spawn...
                if (currentTimeMillis - lastSpawnTime > OBSTACLE_SPAWN_DELAY_MS) {
                    println("spawn")
                    val newObstacleWidth = Random.nextFloat() * (obstacleWidthMaxPx - obstacleWidthMinPx) + obstacleWidthMinPx
                    val newObstacleX = Random.nextFloat() * (screenWidthPx - newObstacleWidth)
                    val newObstacleColor = if (Random.nextBoolean()) Color(0xFFE57373) else Color(0xFF81D4FA) // Red or Blue
                    obstacles.add(
                        Obstacle(
                            position = Offset(newObstacleX, -obstacleHeightPx),
                            size = Size(newObstacleWidth, obstacleHeightPx),
                            color = newObstacleColor
                        )
                    )
                    lastSpawnTime = currentTimeMillis
                }

                // Update & Remove...
                val iterator = obstacles.listIterator()
                while (iterator.hasNext()) {
                    val obstacle = iterator.next()
                    val newObstacleY = obstacle.position.y + OBSTACLE_FALL_SPEED * dt
                    if (newObstacleY < screenHeightPx + obstacleHeightPx * 2) {
                        iterator.set(obstacle.copy(position = Offset(obstacle.position.x, newObstacleY)))
                    } else {
                        iterator.remove()
                    }
                }

                // --- Collision Detection ---
                // Removed gearBounds calculation here as it's not needed for the improved check below
                obstacles.forEach { obstacle ->
                    // *** FIX: Pass arguments needed by checkCircleRectOverlap (Line ~225) ***
                    if (checkCircleRectOverlap(gearRadiusPx, gearPosition, obstacle.bounds)) {
                        gameState = GameState.GameOver
                    }
                }
            }
        } // End of Game Loop LaunchedEffect

        // --- Drawing ---
        Canvas(modifier = Modifier.fillMaxSize().then(inputModifier)) {
            // Draw Obstacles
            obstacles.forEach { obstacle ->
                drawRect(
                    color = obstacle.color,
                    topLeft = obstacle.position,
                    size = obstacle.size
                )
            }

            // Draw Gear
            if (gameState == GameState.Running) {
                drawGear(
                    center = gearPosition,
                    radius = gearRadiusPx,
                    color = Color.Black
                )
            }
        } // End of Canvas

        // --- Game Over UI ---
        if (gameState == GameState.GameOver) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Game Over!",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Text(
                    text = "Tap to Restart",
                    fontSize = 24.sp,
                    color = Color.DarkGray,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    } // End of BoxWithConstraints
}

// *** FIX: Remove unused circleBounds parameter (Line ~275) ***
fun checkCircleRectOverlap(circleRadius: Float, circleCenter: Offset, rectBounds: Rect): Boolean {
    val closestX = clamp(circleCenter.x, rectBounds.left, rectBounds.right)
    val closestY = clamp(circleCenter.y, rectBounds.top, rectBounds.bottom)
    val distanceX = circleCenter.x - closestX
    val distanceY = circleCenter.y - closestY
    val distanceSquared = (distanceX * distanceX) + (distanceY * distanceY)
    return distanceSquared < (circleRadius * circleRadius)
}

// Helper clamp function
fun clamp(value: Float, min: Float, max: Float): Float {
    return max(min, min(value, max))
}


// Helper function to convert DP to Pixels
fun Dp.toPx(density: Float): Float = this.value * density

// Gear Drawing Function (same as before)
fun DrawScope.drawGear(
    center: Offset,
    radius: Float,
    color: Color,
    teethCount: Int = 8,
    toothHeight: Float = radius * 0.25f
) {
    // ... (drawing code remains the same) ...
    val innerRadius = radius - toothHeight
    val angleStep = (2 * PI / teethCount).toFloat()
    drawCircle(color, radius = innerRadius, center = center, style = Stroke(width = radius * 0.1f))
    drawCircle(color = Color.White, radius = radius * 0.3f, center = center)
    drawCircle(color = color, radius = radius * 0.3f, center = center, style = Stroke(width = radius * 0.05f))
    for (i in 0 until teethCount) {
        val angle = i * angleStep
        val startX = center.x + innerRadius * cos(angle)
        val startY = center.y + innerRadius * sin(angle)
        val endX = center.x + radius * cos(angle)
        val endY = center.y + radius * sin(angle)
        drawLine(
            color = color,
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = radius * 0.2f
        )
    }
}
package uk.hairyfred.libreshock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Which puzzle types are enabled — at least one must be true. */
data class PuzzleSettings(
    val memory: Boolean = true,
    val equation: Boolean = true,
    /** When true, a wrong answer keeps the same puzzle so the user retries
     *  exactly what they just got wrong. Default: false (regenerate fresh). */
    val keepOnWrong: Boolean = false,
) {
    init {
        require(memory || equation) { "at least one puzzle type must be enabled" }
    }
}

/** Full-screen alarm-puzzle gate. Picks one of the enabled puzzle types at
 *  random; on success calls [onSolved], on a wrong answer regenerates a new
 *  puzzle of the same type. Snooze is always available. */
@Composable
fun AlarmPuzzleScreen(
    settings: PuzzleSettings,
    alarmId: Int,
    padding: PaddingValues,
    onSolved: () -> Unit,
    onSnooze: () -> Unit,
) {
    val rng = remember { Random.Default }
    var memoryNonce by remember { mutableStateOf(0) }
    var equationNonce by remember { mutableStateOf(0) }

    // Pick a puzzle type for this round. Recomputed when a nonce changes.
    val showMemory = remember(memoryNonce, equationNonce) {
        when {
            settings.memory && settings.equation -> rng.nextBoolean()
            settings.memory -> true
            else -> false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Alarm #$alarmId — solve to stop",
            style = MaterialTheme.typography.titleMedium,
        )
        if (showMemory) {
            MemoryPuzzleBoard(
                nonce = memoryNonce,
                rng = rng,
                keepOnWrong = settings.keepOnWrong,
                onCorrect = onSolved,
                onRegenerate = { memoryNonce++ },
            )
        } else {
            EquationPuzzleBoard(
                nonce = equationNonce,
                rng = rng,
                keepOnWrong = settings.keepOnWrong,
                onCorrect = onSolved,
                onRegenerate = { equationNonce++ },
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onSnooze,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Snooze") }
    }
}

@Composable
private fun EquationPuzzleBoard(
    nonce: Int,
    rng: Random,
    keepOnWrong: Boolean,
    onCorrect: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val puzzle = remember(nonce) { generateEquationPuzzle(rng) }
    var error by remember(nonce) { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                puzzle.question,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // 2x2 grid of choice buttons.
            for (row in 0..1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (col in 0..1) {
                        val choice = puzzle.choices[row * 2 + col]
                        Button(
                            onClick = {
                                if (choice == puzzle.answer) onCorrect()
                                else {
                                    error = if (keepOnWrong) "Wrong — try again"
                                            else "Wrong — fresh one rolled"
                                    if (!keepOnWrong) onRegenerate()
                                }
                            },
                            modifier = Modifier.weight(1f).height(72.dp),
                        ) {
                            Text(
                                choice.toString(),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class MemoryPhase { SHOW, INPUT }

@Composable
private fun MemoryPuzzleBoard(
    nonce: Int,
    rng: Random,
    keepOnWrong: Boolean,
    onCorrect: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val puzzle = remember(nonce) { generateMemoryPuzzle(rng) }
    var phase by remember(nonce) { mutableStateOf(MemoryPhase.SHOW) }
    var selection by remember(nonce) { mutableStateOf<Set<Int>>(emptySet()) }
    var error by remember(nonce) { mutableStateOf<String?>(null) }
    var remainingMs by remember(nonce) { mutableStateOf(MemoryPuzzle.SHOW_DURATION_MS) }

    // SHOW phase: tick down in 50ms steps so the progress bar animates
    // smoothly. Flip to INPUT when remaining hits zero.
    LaunchedEffect(nonce) {
        phase = MemoryPhase.SHOW
        selection = emptySet()
        error = null
        remainingMs = MemoryPuzzle.SHOW_DURATION_MS
        val tick = 50L
        while (remainingMs > 0) {
            delay(tick)
            remainingMs = (remainingMs - tick).coerceAtLeast(0)
        }
        phase = MemoryPhase.INPUT
    }

    // Auto-submit when the user has selected PATTERN_SIZE cells.
    LaunchedEffect(selection, phase) {
        if (phase == MemoryPhase.INPUT && selection.size == MemoryPuzzle.PATTERN_SIZE) {
            if (puzzle.isCorrect(selection)) onCorrect()
            else {
                if (keepOnWrong) {
                    error = "Wrong pattern — try again"
                    selection = emptySet()
                } else {
                    error = "Wrong pattern — fresh one rolled"
                    onRegenerate()
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when (phase) {
                    MemoryPhase.SHOW -> {
                        val secs = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(1)
                        "Memorize the highlighted tiles — ${secs}s"
                    }
                    MemoryPhase.INPUT -> "Tap the tiles you saw (${selection.size}/${MemoryPuzzle.PATTERN_SIZE})"
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (phase == MemoryPhase.SHOW) {
                LinearProgressIndicator(
                    progress = { remainingMs.toFloat() / MemoryPuzzle.SHOW_DURATION_MS },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            MemoryGrid(
                puzzle = puzzle,
                phase = phase,
                selection = selection,
                onToggle = { idx ->
                    if (phase != MemoryPhase.INPUT) return@MemoryGrid
                    selection = if (idx in selection) selection - idx
                                else if (selection.size < MemoryPuzzle.PATTERN_SIZE) selection + idx
                                else selection
                },
            )
        }
    }
}

@Composable
private fun MemoryGrid(
    puzzle: MemoryPuzzle,
    phase: MemoryPhase,
    selection: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until puzzle.rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (col in 0 until puzzle.columns) {
                    val idx = row * puzzle.columns + col
                    val highlighted = when (phase) {
                        MemoryPhase.SHOW -> idx in puzzle.pattern
                        MemoryPhase.INPUT -> idx in selection
                    }
                    MemoryCell(
                        highlighted = highlighted,
                        clickable = phase == MemoryPhase.INPUT,
                        onClick = { onToggle(idx) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryCell(
    highlighted: Boolean,
    clickable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val bg = if (highlighted) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

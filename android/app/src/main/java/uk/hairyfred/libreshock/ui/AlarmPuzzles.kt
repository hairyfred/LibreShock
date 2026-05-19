package uk.hairyfred.libreshock.ui

import kotlin.random.Random

/**
 *  Pure-logic puzzle generators for the alarm Puzzle guarantor.
 *  No Compose dependencies so they're trivially unit-testable.
 */

/** A single arithmetic challenge with one correct answer plus 3 distractors. */
data class EquationPuzzle(
    val question: String,
    val answer: Int,
    /** The 4 multiple-choice options, shuffled. Includes [answer] exactly once. */
    val choices: List<Int>,
)

enum class EquationOp(val symbol: String) {
    ADD("+"), SUB("-"), MUL("×"), DIV("÷")
}

/** Generate a random equation matching the project spec:
 *  - +/-: both operands < 100, subtraction non-negative
 *  - *: both operands < 10
 *  - /: dividend < 100, divisor chosen so result is a whole positive number
 *  Distractors are close to the correct answer (±1..±5), unique, non-negative. */
fun generateEquationPuzzle(rng: Random = Random.Default): EquationPuzzle {
    val op = EquationOp.values().random(rng)
    val (a, b, answer) = when (op) {
        EquationOp.ADD -> {
            val x = rng.nextInt(1, 100)
            val y = rng.nextInt(1, 100)
            Triple(x, y, x + y)
        }
        EquationOp.SUB -> {
            val x = rng.nextInt(2, 100)
            val y = rng.nextInt(1, x + 1)  // ensures x >= y, non-negative
            Triple(x, y, x - y)
        }
        EquationOp.MUL -> {
            val x = rng.nextInt(2, 10)
            val y = rng.nextInt(2, 10)
            Triple(x, y, x * y)
        }
        EquationOp.DIV -> {
            // Pick the divisor and quotient first, then derive the dividend so
            // it always divides cleanly. Keeps dividend < 100.
            val divisor = rng.nextInt(2, 10)
            val maxQuotient = (99 / divisor).coerceAtLeast(2)
            val quotient = rng.nextInt(2, maxQuotient + 1)
            val dividend = divisor * quotient
            Triple(dividend, divisor, quotient)
        }
    }
    val question = "$a ${op.symbol} $b"
    val choices = buildChoices(answer, rng)
    return EquationPuzzle(question, answer, choices)
}

/** 1 correct + 3 distractors within ±5 of the answer. Non-negative, unique. */
private fun buildChoices(answer: Int, rng: Random): List<Int> {
    val pool = mutableSetOf(answer)
    while (pool.size < 4) {
        val offset = rng.nextInt(-5, 6).let { if (it == 0) 1 else it }
        val candidate = answer + offset
        if (candidate >= 0) pool.add(candidate)
    }
    return pool.toList().shuffled(rng)
}

/** A memory puzzle: a 3-column × 4-row grid (12 cells) with [PATTERN_SIZE]
 *  lit cells the user must reproduce. */
data class MemoryPuzzle(
    val pattern: Set<Int>,
) {
    val columns: Int get() = COLUMNS
    val rows: Int get() = ROWS

    fun isCorrect(selection: Set<Int>): Boolean = selection == pattern

    companion object {
        const val COLUMNS = 3
        const val ROWS = 4
        const val CELL_COUNT = COLUMNS * ROWS
        const val PATTERN_SIZE = 5
        const val SHOW_DURATION_MS = 5000L
    }
}

fun generateMemoryPuzzle(rng: Random = Random.Default): MemoryPuzzle {
    val cells = (0 until MemoryPuzzle.CELL_COUNT).shuffled(rng).take(MemoryPuzzle.PATTERN_SIZE)
    return MemoryPuzzle(cells.toSet())
}

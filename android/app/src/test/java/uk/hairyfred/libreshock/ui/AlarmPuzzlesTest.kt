package uk.hairyfred.libreshock.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AlarmPuzzlesTest {

    @Test
    fun equationGeneratorMeetsSpec() {
        val rng = Random(42)
        repeat(2000) {
            val p = generateEquationPuzzle(rng)
            assertEquals("4 choices", 4, p.choices.size)
            assertEquals("unique choices", 4, p.choices.toSet().size)
            assertTrue("answer present", p.answer in p.choices)
            assertTrue("non-negative answer", p.answer >= 0)
            assertTrue("non-negative choices", p.choices.all { it >= 0 })

            // Operands respect the documented ranges. Re-parse from question.
            val (lhs, op, rhs) = parseEquation(p.question)
            when (op) {
                "+" -> {
                    assertTrue("+ a<100", lhs < 100)
                    assertTrue("+ b<100", rhs < 100)
                    assertEquals(lhs + rhs, p.answer)
                }
                "-" -> {
                    assertTrue("- a<100", lhs < 100)
                    assertTrue("- b<100", rhs < 100)
                    assertTrue("- non-negative", lhs >= rhs)
                    assertEquals(lhs - rhs, p.answer)
                }
                "×" -> {
                    assertTrue("× a<10", lhs < 10)
                    assertTrue("× b<10", rhs < 10)
                    assertEquals(lhs * rhs, p.answer)
                }
                "÷" -> {
                    assertTrue("÷ dividend<100", lhs < 100)
                    assertTrue("÷ no remainder", lhs % rhs == 0)
                    assertEquals(lhs / rhs, p.answer)
                }
                else -> error("unexpected op $op")
            }
        }
    }

    @Test
    fun memoryPuzzleHasExpectedShape() {
        val rng = Random(7)
        repeat(500) {
            val p = generateMemoryPuzzle(rng)
            assertEquals(MemoryPuzzle.PATTERN_SIZE, p.pattern.size)
            assertTrue(
                "all indices in range",
                p.pattern.all { it in 0 until MemoryPuzzle.CELL_COUNT },
            )
            assertTrue(p.isCorrect(p.pattern))
            assertTrue(!p.isCorrect(p.pattern - p.pattern.first()))
        }
    }

    private fun parseEquation(q: String): Triple<Int, String, Int> {
        // Format "a OP b" with a single-char op and spaces.
        val parts = q.split(" ")
        require(parts.size == 3) { "bad equation: $q" }
        return Triple(parts[0].toInt(), parts[1], parts[2].toInt())
    }
}

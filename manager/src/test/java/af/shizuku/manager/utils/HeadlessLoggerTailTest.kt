package af.shizuku.manager.utils

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class HeadlessLoggerTailTest :
    FunSpec({
        val log = (1..5000).map { "line $it" }

        test("returns the requested number of newest lines, newest last") {
            val tail = HeadlessLogger.tail(log, 3)

            tail.text shouldBe "line 4998\nline 4999\nline 5000"
            tail.lines shouldBe 3
            tail.truncated shouldBe false
        }

        test("a missing or non-positive count means the default") {
            HeadlessLogger.tail(log, 0).lines shouldBe HeadlessLogger.DEFAULT_TAIL_LINES
            HeadlessLogger.tail(log, -7).lines shouldBe HeadlessLogger.DEFAULT_TAIL_LINES
        }

        test("the count is capped") {
            HeadlessLogger.tail(log, 1_000_000).lines shouldBe HeadlessLogger.MAX_TAIL_LINES
        }

        test("a short log is returned whole") {
            val tail = HeadlessLogger.tail(listOf("a", "b"), 200)

            tail.text shouldBe "a\nb"
            tail.truncated shouldBe false
        }

        test("the size cap drops the oldest lines and says so") {
            val wide = (1..1000).map { "$it ".padEnd(100, 'x') }

            val tail = HeadlessLogger.tail(wide, 1000)

            tail.truncated shouldBe true
            tail.text.length shouldBeLessThanOrEqual HeadlessLogger.MAX_TAIL_CHARS
            tail.text shouldStartWith HeadlessLogger.TRUNCATED_MARK + "\n"
            tail.text.lines().last() shouldBe wide.last()
            tail.lines shouldBe tail.text.lines().size - 1
        }

        test("a newest line too long on its own keeps its start, within the cap") {
            val tail = HeadlessLogger.tail(listOf("old", "start " + "y".repeat(100_000)), 2, maxChars = 1000)

            tail.truncated shouldBe true
            tail.lines shouldBe 1
            tail.text.length shouldBeLessThanOrEqual 1000
            tail.text.lines()[1] shouldStartWith "start y"
        }
    })

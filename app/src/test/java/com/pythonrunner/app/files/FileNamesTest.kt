package com.pythonrunner.app.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileNamesTest {
    @Test fun badNamesAreRefusedWithAReason() {
        listOf("a/b", "a:b", "", ".", "..", "a?b", "a\\b", "a*b", "a\"b", "a<b", "a>b", "a|b")
            .forEach { assertNotNull(FileNames.problem(it, emptyList()), it) }
        assertNotNull(FileNames.problem("main.py", listOf("Main.py")))
        assertNull(FileNames.problem("main.py", listOf("other.py")))
    }

    @Test fun renamingOnlyTheCaseOfItselfIsFine() =
        assertNull(FileNames.problem("A.py", listOf("a.py", "b.py"), renaming = "a.py"))

    @Test fun uniqueAndCopyNamesFollowWindows() {
        assertEquals("新しいフォルダー (2)", FileNames.unique("新しいフォルダー", listOf("新しいフォルダー")))
        assertEquals("新しいフォルダー", FileNames.unique("新しいフォルダー", emptyList()))
        assertEquals("a (3).py", FileNames.unique("a.py", listOf("a.py", "A (2).py")))
        assertEquals("main - コピー.py", FileNames.copyName("main.py", listOf("main.py")))
        assertEquals("main - コピー (2).py", FileNames.copyName("main.py", listOf("main.py", "main - コピー.py")))
        assertEquals("src - コピー", FileNames.copyName("src", listOf("src")))
        assertEquals(".env - コピー", FileNames.copyName(".env", listOf(".env")))
    }

    @Test fun extensionChangeIsNoticed() {
        assertTrue(FileNames.extensionChanged("a.py", "a.txt"))
        assertFalse(FileNames.extensionChanged("a.py", "b.py"))
        assertFalse(FileNames.extensionChanged("src", "lib"))
        assertFalse(FileNames.extensionChanged(".env", ".envrc"))
    }

    @Test fun sameNameIgnoresCase() {
        assertTrue(FileNames.sameName("Main.py", "main.PY"))
        assertFalse(FileNames.sameName("main.py", "main.py "))
    }
}

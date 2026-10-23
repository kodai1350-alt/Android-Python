package com.pythonrunner.app.bot

/** Names (trimmed, non-blank) that appear more than once; only the last value of each would be used. */
fun duplicateNames(names: List<String>): Set<String> =
    names.map { it.trim() }.filter { it.isNotEmpty() }
        .groupingBy { it }.eachCount()
        .filterValues { it > 1 }.keys

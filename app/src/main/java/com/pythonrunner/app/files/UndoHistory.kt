package com.pythonrunner.app.files

/** A bot's 元に戻す list: the last [max] operations, newest on top. */
class UndoHistory(private val max: Int = 20) {
    private val ops = ArrayDeque<FileOp>()

    @Synchronized
    fun push(op: FileOp) {
        ops.addLast(op)
        while (ops.size > max) ops.removeFirst()
    }

    @Synchronized
    fun pop(): FileOp? = ops.removeLastOrNull()

    @Synchronized
    fun peek(): FileOp? = ops.lastOrNull()

    /** Drops [op] wherever it is (the one an undo just reversed). */
    @Synchronized
    fun remove(op: FileOp): Boolean = ops.remove(op)

    @Synchronized
    fun contains(op: FileOp): Boolean = op in ops

    val size: Int @Synchronized get() = ops.size
}

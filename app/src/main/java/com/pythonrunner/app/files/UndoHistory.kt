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

    val size: Int @Synchronized get() = ops.size
}

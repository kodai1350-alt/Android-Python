package com.pythonrunner.app.bot

enum class BotStatus { STOPPED, STARTING, RUNNING, CRASHED }

fun statusForExit(exitCode: Int, stoppedByUser: Boolean): BotStatus =
    if (stoppedByUser || exitCode == 0) BotStatus.STOPPED else BotStatus.CRASHED

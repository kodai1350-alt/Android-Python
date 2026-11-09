package com.pythonrunner.app.bot

/** What [BotFleet] needs from one bot's runner; [BotRunner] is the real one. */
interface FleetRunner {
    /** False if it is already starting or running. */
    fun start(): Boolean
    /** Blocks until the process has stopped. */
    fun stop()
    val isActive: Boolean
}

/**
 * Every bot's runner, by bot id, with the "start again once stopped" requests kept per bot. Stops run
 * outside the lock: they block, and the finished callback of the stopping bot comes back in here.
 */
class BotFleet(private val create: (id: String) -> FleetRunner) {
    private val runners = LinkedHashMap<String, FleetRunner>()
    private val restarts = HashSet<String>()

    fun start(id: String): Boolean = runner(id, create = true)!!.start()

    /** Stops bot [id] and drops a pending restart. Call it off the main thread. */
    fun stop(id: String) {
        val r = synchronized(this) {
            restarts.remove(id)
            runners[id]
        }
        r?.stop()
    }

    /**
     * True if [id] is active: the restart is now pending and the caller runs [stopForRestart] on a
     * thread. False if it is stopped: the caller just starts it.
     */
    @Synchronized
    fun requestRestart(id: String): Boolean {
        if (runners[id]?.isActive != true) return false
        restarts += id
        return true
    }

    /** Stops [id] keeping its restart request. Call it off the main thread. */
    fun stopForRestart(id: String) {
        runner(id, create = false)?.stop()
    }

    /** Whether [id] should start again now that it has finished; clears the request. */
    @Synchronized
    fun takeRestart(id: String): Boolean = restarts.remove(id)

    @Synchronized
    fun isActive(id: String): Boolean = runners[id]?.isActive == true

    @Synchronized
    fun activeIds(): List<String> = runners.filterValues { it.isActive }.keys.toList()

    /** Drops a stopped bot's runner (the bot was deleted). */
    @Synchronized
    fun forget(id: String) {
        runners.remove(id)
        restarts.remove(id)
    }

    @Synchronized
    private fun runner(id: String, create: Boolean): FleetRunner? =
        runners[id] ?: if (create) create(id).also { runners[id] = it } else null
}

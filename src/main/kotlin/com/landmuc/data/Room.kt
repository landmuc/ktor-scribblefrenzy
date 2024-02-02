package com.landmuc.data

import com.landmuc.data.models.Announcement
import com.landmuc.data.models.ChosenWord
import com.landmuc.data.models.PhaseChange
import com.landmuc.gson
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.*

class Room(
    val name: String,
    val maxPlayers: Int,
    var players: List<Player> = listOf()
) {
    private var timerJob: Job? = null
    private var drawingPlayer: Player? = null
    private var winningPlayers = listOf<String>()
    private var word: String? = null

    private var phaseChangedListener: ((Phase) -> Unit)? = null
    var phase = Phase.WAITING_FOR_PLAYERS
        set(value) {
            // synchronized -> so only one thread at a time sets this var
            synchronized(field) {
                field = value
                phaseChangedListener?.let { change ->
                    change(value)
                }
            }
        }
    private fun setPhaseChangedListener(listener: (Phase) -> Unit) {
        phaseChangedListener = listener
    }

    init {
        setPhaseChangedListener { phase ->
            when(phase) {
                Phase.WAITING_FOR_PLAYERS -> waitingForPlayers()
                Phase.WAITING_FOR_START -> waitingForStart()
                Phase.NEW_ROUND -> newRound()
                Phase.GAME_RUNNING -> gameRunning()
                Phase.SHOW_WORD -> showWord()
            }
        }
    }

    suspend fun addPlayer(
        clientId: String,
        username: String,
        socket: WebSocketSession
        ): Player {
        val player = Player(
            clientId = clientId,
            username = username,
            socket = socket
        )
        // in a multi threaded environment mutable objects should be avoided
        players = players + player

        if (players.size == 1) {
            phase = Phase.WAITING_FOR_PLAYERS
        } else if (players.size == 2 && phase == Phase.WAITING_FOR_PLAYERS) {
            phase = Phase.WAITING_FOR_START
            // shuffle the players list because the first player in the list will be the drawing player
            players = players.shuffled()
        } else if (phase == Phase.WAITING_FOR_START && players.size == maxPlayers) {
            phase = Phase.NEW_ROUND
            players = players.shuffled()
        }

        val announcement = Announcement(
            message = "$username joined the party!",
            timestamp = System.currentTimeMillis(),
            announcementType = Announcement.TYPE_PLAYER_JOINED
        )
        broadcast( message = gson.toJson(announcement))

        return player
    }

    private fun timeAndNotify(ms: Long) {
        timerJob?.cancel()
        timerJob = GlobalScope.launch {
            val phaseChange = PhaseChange(
                phase = phase,
                time = ms,
                drawingPlayer = drawingPlayer?.username
            )
            repeat((ms / UPDATE_TIME_FREQUENCY).toInt()) {
                if (it != 0) {
                    phaseChange.phase = null
                }
                broadcast(gson.toJson(phaseChange))
                phaseChange.time -= UPDATE_TIME_FREQUENCY
                delay(UPDATE_TIME_FREQUENCY)
            }
            phase = when(phase) {
                Phase.WAITING_FOR_START -> Phase.NEW_ROUND
                Phase.GAME_RUNNING -> Phase.SHOW_WORD
                Phase.SHOW_WORD -> Phase.NEW_ROUND
                Phase.NEW_ROUND -> Phase.GAME_RUNNING
                else -> Phase.WAITING_FOR_PLAYERS
            }
        }
    }

    suspend fun broadcast(message: String) {
        players.forEach {player ->
            if (player.socket.isActive) {
                player.socket.send(Frame.Text(message))
            }
        }
    }

    suspend fun broadcastToAllExcept(
        message: String,
        clientID: String
    ) {
        players.forEach { player ->
            if (player.clientId != clientID && player.socket.isActive) {
                player.socket.send(Frame.Text(message))
            }
        }
    }

    fun containsPlayer(username: String): Boolean {
        return players.find { player ->
            player.username == username } != null
    }

    fun setWordsAndSwitchToGameRunning(word: String) {
        this.word = word
        phase = Phase.GAME_RUNNING
    }

    private fun waitingForPlayers() {
        GlobalScope.launch {
            val phaseChange = PhaseChange(
                phase = Phase.WAITING_FOR_PLAYERS,
                time = DELAY_WAITING_FOR_START_TO_NEW_ROUND,
                drawingPlayer = null
            )
            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun waitingForStart() {
        GlobalScope.launch {
            timeAndNotify(DELAY_WAITING_FOR_START_TO_NEW_ROUND)
            val phaseChange = PhaseChange(
                phase = Phase.WAITING_FOR_START,
                time = DELAY_WAITING_FOR_START_TO_NEW_ROUND,
                drawingPlayer = null
            )
            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun newRound() {}

    private fun gameRunning() {}

    private fun showWord() {
        GlobalScope.launch {
            if (winningPlayers.isEmpty()) {
                drawingPlayer?.let {drawingPlayer ->
                    drawingPlayer.score -= PENALTY_NOBODY_GUESSED_IT
                }
            }

            word?.let {
                val chosenWord = ChosenWord(
                    chosenWord = it,
                    roomName = name
                )
                broadcast(gson.toJson(chosenWord))
            }

            timeAndNotify(DELAY_SHOW_WORD_TO_NEW_ROUND)
            val phaseChange = PhaseChange(
                phase = Phase.SHOW_WORD,
                time = DELAY_SHOW_WORD_TO_NEW_ROUND,
                drawingPlayer = null
            )
            broadcast(gson.toJson(phaseChange))
        }
    }

    enum class Phase {
        WAITING_FOR_PLAYERS,
        WAITING_FOR_START,
        NEW_ROUND,
        GAME_RUNNING,
        SHOW_WORD
    }

    companion object {
        const val UPDATE_TIME_FREQUENCY = 1000L

        const val DELAY_WAITING_FOR_START_TO_NEW_ROUND = 10000L
        const val DELAY_NEW_ROUND_TO_GAME_RUNNING = 20000L
        const val DELAY_GAME_RUNNING_TO_SHOW_WORD = 60000L
        const val DELAY_SHOW_WORD_TO_NEW_ROUND = 10000L

        // Penalty for the drawingPlayer if too badly drawn
        const val PENALTY_NOBODY_GUESSED_IT = 50
    }
}
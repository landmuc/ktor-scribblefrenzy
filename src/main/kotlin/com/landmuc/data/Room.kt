package com.landmuc.data

import com.landmuc.data.models.*
import com.landmuc.gson
import com.landmuc.util.getRandomWords
import com.landmuc.util.matchesWord
import com.landmuc.util.transformToUnderscore
import com.landmuc.util.words
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
    private var curWords: List<String>? = null
    private var drawingPlayerIndex = 0
    private var startTime = 0L

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
        // in a multithreaded environment mutable objects should be avoided
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
            startTime = System.currentTimeMillis()
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

    private fun isGuessCorrect(guess: ChatMessage): Boolean {
        return guess.matchesWord(word ?: return false) && !winningPlayers.contains(guess.from) &&
                guess.from != drawingPlayer?.username && phase == Phase.GAME_RUNNING
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

    private fun newRound() {
        curWords = getRandomWords(3)
        val newWords = NewWords(curWords!!)
        nextDrawingPlayer()
        GlobalScope.launch {
            drawingPlayer?.socket?.send(Frame.Text(gson.toJson(newWords)))
            timeAndNotify(DELAY_NEW_ROUND_TO_GAME_RUNNING)
        }
    }

    private fun gameRunning() {
        winningPlayers = listOf()
        val wordToSend = word ?: curWords?.random() ?: words.random()
        val wordWithUnderscores = wordToSend.transformToUnderscore()
        val drawingUserName = (drawingPlayer ?: players.random()).username
        val gameStateForDrawingPlayer = GameState(
            drawingPlayer = drawingUserName,
            word = wordToSend
        )
        val gameStateForGuessingPlayers = GameState(
            drawingPlayer = drawingUserName,
            word = wordWithUnderscores
        )
        GlobalScope.launch {
            broadcastToAllExcept(
                message = gson.toJson(gameStateForGuessingPlayers),
                clientID = drawingPlayer?.clientId ?: players.random().clientId
            )
            drawingPlayer?.socket?.send(Frame.Text(gson.toJson(gameStateForDrawingPlayer)))

            timeAndNotify(DELAY_GAME_RUNNING_TO_SHOW_WORD)
            // Serverlog
            println("Drawing phase in room $name started. It'll last ${DELAY_GAME_RUNNING_TO_SHOW_WORD / 1000}s")
        }

    }

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

    private fun addWinningPlayer(username: String): Boolean {
        winningPlayers = winningPlayers + username
        if (winningPlayers.size == players.size -1) {
            phase = Phase.NEW_ROUND
            return true
        }
        return false
    }

    suspend fun checkWordAndNotifyPlayers(message: ChatMessage): Boolean {
        if (isGuessCorrect(message)) {
            val guessingTime = System.currentTimeMillis() - startTime
            val timePercentageLeft = 1f - guessingTime.toFloat() / DELAY_GAME_RUNNING_TO_SHOW_WORD
            val score = GUESS_SCORE_DEFAULT + GUESS_SCORE_PERCENTAGE_MULTIPLIER * timePercentageLeft
            val player = players.find { it.username == message.from }

            player?.let { player ->
                player.score += score.toInt()
            }
            drawingPlayer?.let { drawingPlayer ->
                drawingPlayer.score += GUESS_SCORE_FOR_DRAWING_PLAYER / players.size
            }

            val announcement = Announcement(
                message = "${message.from} has guessed it!",
                timestamp = System.currentTimeMillis(),
                announcementType = Announcement.TYPE_PLAYER_GUESSED_WORD
            )
            broadcast(gson.toJson(announcement))
            val isRoundOver = addWinningPlayer(message.from)
            if (isRoundOver) {
                val roundOverAnnouncement = Announcement(
                    message = "Everybody guessed it! New round is starting...",
                    timestamp = System.currentTimeMillis(),
                    announcementType = Announcement.TYPE_EVERYBODY_GUESSED_IT
                )
                broadcast(gson.toJson(roundOverAnnouncement))
            }
        }
        return false
    }

    private fun nextDrawingPlayer() {
        drawingPlayer?.isDrawing = false
        if (players.isEmpty()) {
            return
        }

        drawingPlayer = if (drawingPlayerIndex <= players.size - 1) {
            players[drawingPlayerIndex]
        } else players.last()

        if (drawingPlayerIndex < players.size - 1) drawingPlayerIndex++
        else drawingPlayerIndex = 0
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
        const val GUESS_SCORE_DEFAULT = 50
        const val GUESS_SCORE_PERCENTAGE_MULTIPLIER = 50
        const val GUESS_SCORE_FOR_DRAWING_PLAYER = 50
    }
}
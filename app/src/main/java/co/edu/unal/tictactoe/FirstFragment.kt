
package co.edu.unal.tictactoe

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import co.edu.unal.tictactoe.databinding.FragmentFirstBinding
import com.google.firebase.firestore.ListenerRegistration

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val game = TicTacToeGame()
    private val onlineGameManager = OnlineGameManager()

    private var gameOver = false
    private var computerThinking = false
    private var onlineOperationInProgress = false

    private var soundEnabled = true
    private var mediaPlayer: MediaPlayer? = null

    private var humanWins = 0
    private var computerWins = 0
    private var ties = 0
    private var scoreAlreadyCounted = false

    // Variables online
    private var onlineGameId: String? = null
    private var onlinePlayerId: String? = null
    private var onlinePlayerMark = 0
    private var onlineTurn = 1
    private var onlineStatus = "waiting"
    private var onlineWinner = 0
    private var onlineBoard = List(9) { 0 }
    private var onlineMovePending = false
    private var onlineSnapshotReceived = false

    // Variables de revancha
    private var rematchRequestedBy = ""
    private var rematchRound = 0L
    private var rematchDialogRound = -1L
    private var rematchDialog: AlertDialog? = null

    private var gameListener: ListenerRegistration? = null

    companion object {
        private const val MENU_CREATE_ONLINE = 0x7001
        private const val MENU_JOIN_ONLINE = 0x7002

        private const val KEY_BOARD = "board_state"
        private const val KEY_CURRENT_PLAYER = "current_player"
        private const val KEY_GAME_OVER = "game_over"
        private const val KEY_DIFFICULTY = "difficulty"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_SCORE_COUNTED = "score_counted"

        private const val KEY_ONLINE_GAME_ID = "online_game_id"
        private const val KEY_ONLINE_PLAYER_ID = "online_player_id"
        private const val KEY_ONLINE_MARK = "online_mark"

        private const val PREFS_NAME = "tic_tac_toe_preferences"
        private const val PREF_HUMAN_WINS = "human_wins"
        private const val PREF_COMPUTER_WINS = "computer_wins"
        private const val PREF_TIES = "ties"
        private const val PREF_DIFFICULTY = "difficulty_level"
    }

    // ==========================================
    // CREACIÓN DE LA VISTA
    // ==========================================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(
            inflater, container, false
        )
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        loadScores()
        loadDifficulty()
        displayScores()

        binding.boardView.onCellClickListener = { position ->
            if (onlineGameId != null) {
                makeOnlineMove(position)
            } else {
                humanMove(position)
            }
        }

        if (savedInstanceState == null) {
            startNewGame()
        } else {
            restoreGameState(savedInstanceState)
        }

        binding.restartButton.setOnClickListener {
            if (onlineGameId == null) {
                startNewGame()
            } else if (onlineStatus == "finished") {
                when {
                    rematchRequestedBy.isEmpty() ->
                        requestOnlineRematch()

                    rematchRequestedBy != onlinePlayerId ->
                        acceptOnlineRematch()

                    else ->
                        showOnlineMessage(
                            "Esperando la respuesta del rival"
                        )
                }
            } else {
                showExitOnlineDialog()
            }
        }

        setupMenu()
    }

    // ==========================================
    // MENÚ
    // ==========================================

    private fun setupMenu() {
        requireActivity().addMenuProvider(
            object : MenuProvider {

                override fun onCreateMenu(
                    menu: Menu,
                    menuInflater: MenuInflater
                ) {
                    menuInflater.inflate(
                        R.menu.options_menu,
                        menu
                    )

                    menu.add(
                        Menu.NONE,
                        MENU_CREATE_ONLINE,
                        Menu.NONE,
                        "Crear partida online"
                    )

                    menu.add(
                        Menu.NONE,
                        MENU_JOIN_ONLINE,
                        Menu.NONE,
                        "Unirse a partida online"
                    )

                    val soundItem =
                        menu.findItem(R.id.sound)

                    soundItem?.isChecked = soundEnabled
                    soundItem?.title =
                        if (soundEnabled) {
                            "Sound: ON"
                        } else {
                            "Sound: OFF"
                        }
                }

                override fun onMenuItemSelected(
                    menuItem: MenuItem
                ): Boolean {
                    return when (menuItem.itemId) {

                        R.id.new_game -> {
                            if (onlineGameId != null) {
                                showExitOnlineDialog()
                            } else {
                                startNewGame()
                            }
                            true
                        }

                        R.id.ai_difficulty -> {
                            showDifficultyDialog()
                            true
                        }

                        R.id.sound -> {
                            soundEnabled = !soundEnabled
                            menuItem.isChecked = soundEnabled
                            menuItem.title =
                                if (soundEnabled) {
                                    "Sound: ON"
                                } else {
                                    "Sound: OFF"
                                }
                            true
                        }

                        R.id.reset_scores -> {
                            resetScores()
                            true
                        }

                        MENU_CREATE_ONLINE -> {
                            createOnlineGame()
                            true
                        }

                        MENU_JOIN_ONLINE -> {
                            showJoinGameDialog()
                            true
                        }

                        else -> false
                    }
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )
    }

    // ==========================================
    // CREAR PARTIDA ONLINE
    // ==========================================

    private fun createOnlineGame() {

        if (onlineOperationInProgress) return

        if (onlineGameId != null) {
            showOnlineMessage(
                "Ya estás en una partida online. " +
                        "Sal de ella antes de crear otra."
            )
            return
        }

        onlineOperationInProgress = true

        onlineGameManager.signInAnonymously(
            onSuccess = { playerId ->

                onlineGameManager.createGame(
                    playerId = playerId,

                    onSuccess = { gameId ->
                        onlineOperationInProgress = false

                        if (!isAdded || _binding == null) {
                            return@createGame
                        }

                        activateOnlineGame(
                            gameId = gameId,
                            playerId = playerId,
                            mark = 1
                        )

                        AlertDialog.Builder(requireContext())
                            .setTitle("Partida online creada")
                            .setMessage(
                                "Eres el jugador X.\n\n" +
                                        "ID de partida:\n$gameId\n\n" +
                                        "Comparte este ID con el " +
                                        "segundo jugador.\n\n" +
                                        "Esperando al jugador O."
                            )
                            .setPositiveButton(
                                "Aceptar",
                                null
                            )
                            .show()
                    },

                    onError = { error ->
                        onlineOperationInProgress = false
                        showOnlineMessage(
                            "Error al crear partida:\n$error"
                        )
                    }
                )
            },

            onError = { error ->
                onlineOperationInProgress = false
                showOnlineMessage(
                    "Error de autenticación:\n$error"
                )
            }
        )
    }

    // ==========================================
    // UNIRSE A PARTIDA ONLINE
    // ==========================================

    private fun showJoinGameDialog() {

        if (onlineOperationInProgress) return

        if (onlineGameId != null) {
            showOnlineMessage(
                "Ya estás en una partida online. " +
                        "Sal de ella antes de unirte a otra."
            )
            return
        }

        val input = EditText(requireContext()).apply {
            hint = "ID de partida"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }

        val padding =
            (24 * resources.displayMetrics.density).toInt()

        val container =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, 8, padding, 0)
                addView(input)
            }

        AlertDialog.Builder(requireContext())
            .setTitle("Unirse a partida online")
            .setMessage(
                "Introduce el ID compartido " +
                        "por el jugador X."
            )
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Unirse") { _, _ ->

                val gameId =
                    input.text.toString().trim()

                if (gameId.isBlank()) {
                    showOnlineMessage(
                        "Debes introducir un ID."
                    )
                } else {
                    joinOnlineGame(gameId)
                }
            }
            .show()
    }

    private fun joinOnlineGame(gameId: String) {

        if (onlineOperationInProgress) return

        onlineOperationInProgress = true

        onlineGameManager.signInAnonymously(
            onSuccess = { playerId ->

                onlineGameManager.joinGame(
                    gameId = gameId,
                    playerId = playerId,

                    onSuccess = {
                        onlineOperationInProgress = false

                        if (!isAdded || _binding == null) {
                            return@joinGame
                        }

                        activateOnlineGame(
                            gameId = gameId,
                            playerId = playerId,
                            mark = 2
                        )

                        showOnlineMessage(
                            "¡Te uniste correctamente!\n\n" +
                                    "Eres el jugador O.\n\n" +
                                    "ID: $gameId\n\n" +
                                    "El jugador X comienza."
                        )
                    },

                    onError = { error ->
                        onlineOperationInProgress = false
                        showOnlineMessage(
                            "No se pudo unir:\n$error"
                        )
                    }
                )
            },

            onError = { error ->
                onlineOperationInProgress = false
                showOnlineMessage(
                    "Error de autenticación:\n$error"
                )
            }
        )
    }

    // ==========================================
    // ACTIVAR MODO ONLINE
    // ==========================================

    private fun activateOnlineGame(
        gameId: String,
        playerId: String,
        mark: Int
    ) {

        gameListener?.remove()
        gameListener = null

        stopSound()

        onlineGameId = gameId
        onlinePlayerId = playerId
        onlinePlayerMark = mark
        onlineTurn = 1
        onlineStatus = "waiting"
        onlineWinner = 0
        onlineBoard = List(9) { 0 }
        onlineMovePending = false
        onlineSnapshotReceived = false

        rematchRequestedBy = ""
        rematchRound = 0L
        rematchDialogRound = -1L

        rematchDialog?.dismiss()
        rematchDialog = null

        gameOver = false
        computerThinking = false

        binding.boardView.clearBoard()
        binding.information.text =
            "Conectando a la partida online..."

        listenToOnlineGame()
    }

    // ==========================================
    // ESCUCHAR FIRESTORE
    // ==========================================

    private fun listenToOnlineGame() {

        val gameId = onlineGameId ?: return

        gameListener?.remove()

        gameListener = onlineGameManager.listenGame(
            gameId = gameId,

            onUpdate = { data ->
                if (
                    _binding == null ||
                    onlineGameId != gameId
                ) {
                    return@listenGame
                }

                updateOnlineGame(data)
            },

            onError = { error ->
                if (
                    _binding != null &&
                    onlineGameId == gameId
                ) {
                    binding.information.text =
                        "Error de conexión online"

                    showOnlineMessage(
                        "Error de sincronización:\n$error"
                    )
                }
            }
        )
    }

    // ==========================================
    // ACTUALIZAR TABLERO ONLINE
    // ==========================================

    private fun updateOnlineGame(
        data: Map<String, Any?>
    ) {

        val board = (data["board"] as? List<*>)
            ?.mapNotNull { value ->
                (value as? Number)?.toInt()
            }
            ?: return

        if (
            board.size != 9 ||
            board.any { it !in 0..2 }
        ) {
            return
        }

        // Detectar solamente jugadas del rival.
        // Las propias suenan al confirmar Firebase.

        val previousBoard = onlineBoard

        val newMove = if (onlineSnapshotReceived) {
            (0..8).firstOrNull { position ->
                previousBoard[position] == 0 &&
                        board[position] in 1..2 &&
                        board[position] != onlinePlayerMark
            }
        } else {
            null
        }

        val previousRound = rematchRound

        onlineBoard = board
        onlineSnapshotReceived = true

        onlineTurn =
            (data["turn"] as? Number)?.toInt() ?: 1

        onlineStatus =
            data["status"] as? String ?: "waiting"

        onlineWinner =
            (data["winner"] as? Number)?.toInt() ?: 0

        rematchRequestedBy =
            data["rematchRequestedBy"] as? String ?: ""

        rematchRound =
            (data["round"] as? Number)?.toLong() ?: 0L

        // Cerrar diálogos de revancha cuando inicia
        // una nueva ronda.

        if (rematchRound != previousRound) {
            rematchDialog?.dismiss()
            rematchDialog = null
            rematchDialogRound = -1L
        }

        // Sonido de la jugada recibida del rival.
        // No sonar al reiniciar el tablero.

        if (
            newMove != null &&
            rematchRound == previousRound
        ) {
            val resource =
                if (board[newMove] == 1) {
                    R.raw.human_move
                } else {
                    R.raw.computer_move
                }

            playSound(resource)
        }

        // Dibujar tablero

        binding.boardView.clearBoard()

        for (position in 0..8) {

            when (board[position]) {

                1 -> binding.boardView.setCell(
                    position,
                    'X'
                )

                2 -> binding.boardView.setCell(
                    position,
                    'O'
                )
            }
        }

        updateOnlineInformation()
        showRematchRequestIfNeeded()
    }

    // ==========================================
    // ESTADO ONLINE Y BOTÓN DE REVANCHA
    // ==========================================

    private fun updateOnlineInformation() {

        val myMark =
            if (onlinePlayerMark == 1) "X" else "O"

        val message = when (onlineStatus) {

            "waiting" -> {
                "Eres X. Esperando al jugador O..."
            }

            "playing" -> {
                if (onlineTurn == onlinePlayerMark) {
                    "Jugador $myMark: ¡es tu turno!"
                } else {
                    "Esperando el turno del rival..."
                }
            }

            "finished" -> {
                when (onlineWinner) {

                    1 -> {
                        if (onlinePlayerMark == 1) {
                            "¡Ganaste! X completó el Triqui."
                        } else {
                            "Perdiste. Ganó el jugador X."
                        }
                    }

                    2 -> {
                        if (onlinePlayerMark == 2) {
                            "¡Ganaste! O completó el Triqui."
                        } else {
                            "Perdiste. Ganó el jugador O."
                        }
                    }

                    3 -> "¡Empate online!"

                    else -> "Partida finalizada"
                }
            }

            else -> "Estado online: $onlineStatus"
        }

        binding.information.text = when {

            onlineStatus == "finished" &&
                    rematchRequestedBy == onlinePlayerId -> {
                "$message\nEsperando que el rival acepte la revancha..."
            }

            onlineStatus == "finished" &&
                    rematchRequestedBy.isNotEmpty() -> {
                "$message\nEl rival solicita una revancha."
            }

            else -> message
        }

        binding.restartButton.text = when {

            onlineStatus == "finished" &&
                    rematchRequestedBy.isEmpty() -> {
                "Solicitar revancha"
            }

            onlineStatus == "finished" &&
                    rematchRequestedBy == onlinePlayerId -> {
                "Esperando revancha..."
            }

            onlineStatus == "finished" &&
                    rematchRequestedBy.isNotEmpty() -> {
                "Aceptar revancha"
            }

            else -> "Salir de partida online"
        }
    }

    // ==========================================
    // MOSTRAR SOLICITUD DE REVANCHA
    // ==========================================

    private fun showRematchRequestIfNeeded() {

        if (
            onlineStatus != "finished" ||
            rematchRequestedBy.isEmpty() ||
            rematchRequestedBy == onlinePlayerId ||
            rematchDialogRound == rematchRound ||
            _binding == null
        ) {
            return
        }

        rematchDialogRound = rematchRound

        rematchDialog =
            AlertDialog.Builder(requireContext())
                .setTitle("Solicitud de revancha")
                .setMessage(
                    "El otro jugador quiere jugar " +
                            "otra partida. ¿Aceptas?"
                )
                .setPositiveButton("Aceptar") { _, _ ->
                    acceptOnlineRematch()
                }
                .setNegativeButton("Ahora no", null)
                .create()

        rematchDialog?.show()
    }

    // ==========================================
    // SOLICITAR REVANCHA
    // ==========================================

    private fun requestOnlineRematch() {

        val id = onlineGameId ?: return
        val player = onlinePlayerId ?: return

        if (
            onlineStatus != "finished" ||
            rematchRequestedBy.isNotEmpty()
        ) {
            return
        }

        onlineGameManager.requestRematch(
            gameId = id,
            playerId = player,

            onSuccess = {
                // El listener actualizará la interfaz.
            },

            onError = { error ->
                showOnlineMessage(
                    "Error al solicitar revancha:\n$error"
                )
            }
        )
    }

    // ==========================================
    // ACEPTAR REVANCHA
    // ==========================================

    private fun acceptOnlineRematch() {

        val id = onlineGameId ?: return
        val player = onlinePlayerId ?: return

        onlineGameManager.acceptRematch(
            gameId = id,
            playerId = player,

            onSuccess = {
                // Firestore sincronizará el nuevo tablero.
            },

            onError = { error ->
                showOnlineMessage(
                    "Error al aceptar revancha:\n$error"
                )
            }
        )
    }

    // ==========================================
    // ENVIAR MOVIMIENTO ONLINE
    // ==========================================

    private fun makeOnlineMove(position: Int) {

        val gameId = onlineGameId ?: return
        val playerId = onlinePlayerId ?: return

        if (onlineMovePending) return
        if (onlineStatus != "playing") return
        if (onlineTurn != onlinePlayerMark) return
        if (position !in 0..8) return
        if (onlineBoard[position] != 0) return

        onlineMovePending = true

        onlineGameManager.makeOnlineMove(
            gameId = gameId,
            playerId = playerId,
            position = position,

            onSuccess = {
                onlineMovePending = false

                // Reproducir sonido de la jugada propia
                // cuando Firebase confirme que se guardó.

                if (
                    _binding != null &&
                    onlineGameId == gameId
                ) {
                    playSound(
                        if (onlinePlayerMark == 1) {
                            R.raw.human_move
                        } else {
                            R.raw.computer_move
                        }
                    )
                }
            },

            onError = { error ->
                onlineMovePending = false

                if (
                    _binding != null &&
                    onlineGameId == gameId
                ) {
                    showOnlineMessage(
                        "No se pudo realizar la jugada:\n$error"
                    )
                }
            }
        )
    }

    // ==========================================
    // SALIR DEL MODO ONLINE
    // ==========================================

    private fun showExitOnlineDialog() {

        AlertDialog.Builder(requireContext())
            .setTitle("Salir de partida online")
            .setMessage(
                "¿Quieres volver al juego contra Android?\n\n" +
                        "La partida online permanecerá " +
                        "guardada en Firestore."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salir") { _, _ ->
                exitOnlineGame()
                startNewGame()
            }
            .show()
    }

    private fun exitOnlineGame() {

        gameListener?.remove()
        gameListener = null

        onlineGameId = null
        onlinePlayerId = null
        onlinePlayerMark = 0
        onlineTurn = 1
        onlineStatus = "waiting"
        onlineWinner = 0
        onlineBoard = List(9) { 0 }
        onlineMovePending = false
        onlineSnapshotReceived = false

        rematchRequestedBy = ""
        rematchRound = 0L
        rematchDialogRound = -1L

        rematchDialog?.dismiss()
        rematchDialog = null

        binding.restartButton.text = "New Game"
    }

    private fun showOnlineMessage(message: String) {

        if (!isAdded || _binding == null) return

        AlertDialog.Builder(requireContext())
            .setTitle("Triqui online")
            .setMessage(message)
            .setPositiveButton("Aceptar", null)
            .show()
    }

    // ==========================================
    // JUEGO LOCAL: NUEVA PARTIDA
    // ==========================================

    private fun startNewGame() {

        if (onlineGameId != null) return

        stopSound()

        game.resetGame()
        binding.boardView.clearBoard()

        gameOver = false
        computerThinking = false
        scoreAlreadyCounted = false

        binding.restartButton.text = "New Game"

        binding.information.setText(
            R.string.first_human
        )

        displayScores()
    }

    // ==========================================
    // JUEGO LOCAL: MOVIMIENTO HUMANO
    // ==========================================

    private fun humanMove(position: Int) {

        if (onlineGameId != null) return
        if (gameOver || computerThinking) return

        if (binding.boardView.getCell(position) != ' ') {
            return
        }

        if (!game.makeMove(position)) return

        binding.boardView.setCell(position, 'X')

        playSound(R.raw.human_move)

        val result = game.checkWinner()

        if (result != 0) {
            updateGameStatus(result)
            return
        }

        computerThinking = true

        binding.information.setText(
            R.string.turn_computer
        )

        scheduleComputerMove(1000)
    }

    private fun scheduleComputerMove(delay: Long) {

        val boardView = binding.boardView

        boardView.postDelayed({

            if (
                _binding == null ||
                onlineGameId != null ||
                gameOver ||
                !computerThinking ||
                game.getCurrentPlayer() !=
                TicTacToeGame.NOUGHT
            ) {
                return@postDelayed
            }

            computerMove()

            val result = game.checkWinner()

            computerThinking = false

            updateGameStatus(result)

        }, delay)
    }

    // ==========================================
    // JUEGO LOCAL: MOVIMIENTO COMPUTADOR
    // ==========================================

    private fun computerMove() {

        val position = game.getComputerMove()

        if (position == -1) return

        if (game.makeMove(position)) {

            binding.boardView.setCell(
                position,
                'O'
            )

            playSound(R.raw.computer_move)
        }
    }

    // ==========================================
    // RESULTADOS LOCALES
    // ==========================================

    private fun updateGameStatus(result: Int) {

        when (result) {

            0 -> {
                binding.information.setText(
                    R.string.turn_human
                )
            }

            1 -> {
                binding.information.setText(
                    R.string.result_human_wins
                )

                if (!scoreAlreadyCounted) {
                    humanWins++
                    scoreAlreadyCounted = true
                    saveScores()
                    displayScores()
                }

                finishGame()
            }

            2 -> {
                binding.information.setText(
                    R.string.result_computer_wins
                )

                if (!scoreAlreadyCounted) {
                    computerWins++
                    scoreAlreadyCounted = true
                    saveScores()
                    displayScores()
                }

                finishGame()
            }

            3 -> {
                binding.information.setText(
                    R.string.result_tie
                )

                if (!scoreAlreadyCounted) {
                    ties++
                    scoreAlreadyCounted = true
                    saveScores()
                    displayScores()
                }

                finishGame()
            }
        }
    }

    private fun finishGame() {
        gameOver = true
        computerThinking = false
    }

    // ==========================================
    // MARCADORES PERSISTENTES
    // ==========================================

    private fun loadScores() {

        val prefs =
            requireContext().getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        humanWins = prefs.getInt(PREF_HUMAN_WINS, 0)
        computerWins = prefs.getInt(PREF_COMPUTER_WINS, 0)
        ties = prefs.getInt(PREF_TIES, 0)
    }

    private fun saveScores() {

        requireContext().getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putInt(PREF_HUMAN_WINS, humanWins)
            .putInt(PREF_COMPUTER_WINS, computerWins)
            .putInt(PREF_TIES, ties)
            .apply()
    }

    private fun displayScores() {

        binding.humanScore.text =
            humanWins.toString()

        binding.computerScore.text =
            computerWins.toString()

        binding.tieScore.text =
            ties.toString()
    }

    private fun resetScores() {

        humanWins = 0
        computerWins = 0
        ties = 0

        saveScores()
        displayScores()
    }

    // ==========================================
    // DIFICULTAD PERSISTENTE
    // ==========================================

    private fun saveDifficulty() {

        requireContext().getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(
                PREF_DIFFICULTY,
                game.getDifficultyLevel().name
            )
            .apply()
    }

    private fun loadDifficulty() {

        val prefs =
            requireContext().getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        val name = prefs.getString(
            PREF_DIFFICULTY,
            TicTacToeGame.DifficultyLevel.EXPERT.name
        )

        val level = try {
            TicTacToeGame.DifficultyLevel.valueOf(
                name
                    ?: TicTacToeGame.DifficultyLevel.EXPERT.name
            )
        } catch (e: IllegalArgumentException) {
            TicTacToeGame.DifficultyLevel.EXPERT
        }

        game.setDifficultyLevel(level)
    }

    private fun showDifficultyDialog() {

        val levels = arrayOf(
            "Easy",
            "Harder",
            "Expert"
        )

        val selected = when (
            game.getDifficultyLevel()
        ) {
            TicTacToeGame.DifficultyLevel.EASY -> 0
            TicTacToeGame.DifficultyLevel.HARDER -> 1
            TicTacToeGame.DifficultyLevel.EXPERT -> 2
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Choose difficulty")
            .setSingleChoiceItems(
                levels,
                selected
            ) { dialog, which ->

                val level = when (which) {
                    0 -> TicTacToeGame.DifficultyLevel.EASY
                    1 -> TicTacToeGame.DifficultyLevel.HARDER
                    else -> TicTacToeGame.DifficultyLevel.EXPERT
                }

                game.setDifficultyLevel(level)
                saveDifficulty()
                dialog.dismiss()
            }
            .show()
    }

    // ==========================================
    // GUARDAR ESTADO
    // ==========================================

    override fun onSaveInstanceState(
        outState: Bundle
    ) {

        super.onSaveInstanceState(outState)

        outState.putIntArray(
            KEY_BOARD,
            game.getBoardState()
        )

        outState.putInt(
            KEY_CURRENT_PLAYER,
            game.getCurrentPlayer()
        )

        outState.putBoolean(
            KEY_GAME_OVER,
            gameOver
        )

        outState.putBoolean(
            KEY_SCORE_COUNTED,
            scoreAlreadyCounted
        )

        outState.putBoolean(
            KEY_SOUND_ENABLED,
            soundEnabled
        )

        outState.putString(
            KEY_DIFFICULTY,
            game.getDifficultyLevel().name
        )

        outState.putString(
            KEY_ONLINE_GAME_ID,
            onlineGameId
        )

        outState.putString(
            KEY_ONLINE_PLAYER_ID,
            onlinePlayerId
        )

        outState.putInt(
            KEY_ONLINE_MARK,
            onlinePlayerMark
        )
    }

    // ==========================================
    // RESTAURAR ESTADO
    // ==========================================

    private fun restoreGameState(state: Bundle) {

        val savedBoard =
            state.getIntArray(KEY_BOARD)

        if (savedBoard == null) {
            startNewGame()
            return
        }

        game.setBoardState(savedBoard)

        game.setCurrentPlayer(
            state.getInt(
                KEY_CURRENT_PLAYER,
                TicTacToeGame.CROSS
            )
        )

        gameOver = state.getBoolean(
            KEY_GAME_OVER,
            false
        )

        scoreAlreadyCounted = state.getBoolean(
            KEY_SCORE_COUNTED,
            false
        )

        soundEnabled = state.getBoolean(
            KEY_SOUND_ENABLED,
            true
        )

        val difficultyName = state.getString(
            KEY_DIFFICULTY,
            TicTacToeGame.DifficultyLevel.EXPERT.name
        )

        val difficulty = try {
            TicTacToeGame.DifficultyLevel.valueOf(
                difficultyName
                    ?: TicTacToeGame.DifficultyLevel.EXPERT.name
            )
        } catch (e: IllegalArgumentException) {
            TicTacToeGame.DifficultyLevel.EXPERT
        }

        game.setDifficultyLevel(difficulty)

        val savedOnlineId =
            state.getString(KEY_ONLINE_GAME_ID)

        val savedPlayerId =
            state.getString(KEY_ONLINE_PLAYER_ID)

        val savedMark =
            state.getInt(KEY_ONLINE_MARK, 0)

        if (
            !savedOnlineId.isNullOrBlank() &&
            !savedPlayerId.isNullOrBlank() &&
            savedMark in 1..2
        ) {

            activateOnlineGame(
                savedOnlineId,
                savedPlayerId,
                savedMark
            )

            return
        }

        binding.boardView.clearBoard()

        for (i in 0..8) {

            when (game.getCell(i)) {

                TicTacToeGame.CROSS ->
                    binding.boardView.setCell(i, 'X')

                TicTacToeGame.NOUGHT ->
                    binding.boardView.setCell(i, 'O')
            }
        }

        displayScores()

        val result = game.checkWinner()

        if (result != 0) {
            updateGameStatus(result)
            return
        }

        if (
            game.getCurrentPlayer() ==
            TicTacToeGame.NOUGHT
        ) {

            computerThinking = true

            binding.information.setText(
                R.string.turn_computer
            )

            scheduleComputerMove(500)

        } else {

            computerThinking = false

            binding.information.setText(
                R.string.turn_human
            )
        }
    }

    // ==========================================
    // SONIDOS
    // ==========================================

    private fun playSound(soundResource: Int) {

        if (!soundEnabled) return
        if (!isAdded) return

        stopSound()

        mediaPlayer = MediaPlayer.create(
            requireContext(),
            soundResource
        )

        mediaPlayer?.setOnCompletionListener { player ->
            if (mediaPlayer === player) {
                mediaPlayer = null
            }
            player.release()
        }

        mediaPlayer?.start()
    }

    private fun stopSound() {

        mediaPlayer?.let { player ->

            if (player.isPlaying) {
                player.stop()
            }

            player.release()
        }

        mediaPlayer = null
    }

    // ==========================================
    // LIBERAR RECURSOS
    // ==========================================

    override fun onDestroyView() {

        gameListener?.remove()
        gameListener = null

        rematchDialog?.dismiss()
        rematchDialog = null

        binding.boardView.onCellClickListener = null

        stopSound()

        super.onDestroyView()

        _binding = null
    }
}

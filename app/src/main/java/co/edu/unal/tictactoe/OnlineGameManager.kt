
package co.edu.unal.tictactoe

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class OnlineGameManager {

    // ==========================================
    // CONEXIÓN CON FIREBASE
    // ==========================================

    private val auth = FirebaseAuth.getInstance()

    private val db = FirebaseFirestore.getInstance()

    private val gamesCollection = db.collection("games")

    // ==========================================
    // 1. AUTENTICACIÓN ANÓNIMA
    // ==========================================

    fun signInAnonymously(
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        val currentUser = auth.currentUser

        if (currentUser != null) {
            onSuccess(currentUser.uid)
            return
        }

        auth.signInAnonymously()
            .addOnSuccessListener { result ->

                val uid = result.user?.uid

                if (uid != null) {
                    onSuccess(uid)
                } else {
                    onError("No se obtuvo el ID del jugador")
                }
            }
            .addOnFailureListener { exception ->

                onError(
                    exception.message
                        ?: "Error de autenticación"
                )
            }
    }

    // ==========================================
    // 2. CREAR PARTIDA ONLINE
    // ==========================================

    fun createGame(
        playerId: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        if (!isAuthenticatedPlayer(playerId)) {
            onError("El jugador no está autenticado")
            return
        }

        val gameData = hashMapOf<String, Any>(
            "playerX" to playerId,
            "playerO" to "",
            "board" to List(9) { 0 },
            "turn" to 1,
            "status" to "waiting",
            "winner" to 0,

            // Campos nuevos para la revancha
            "rematchRequestedBy" to "",
            "round" to 1
        )

        gamesCollection.add(gameData)
            .addOnSuccessListener { document ->
                onSuccess(document.id)
            }
            .addOnFailureListener { exception ->
                onError(
                    exception.message
                        ?: "Error al crear partida"
                )
            }
    }

    // ==========================================
    // 3. UNIRSE A UNA PARTIDA EXISTENTE
    // ==========================================

    fun joinGame(
        gameId: String,
        playerId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {

        val cleanGameId = gameId.trim()

        if (!isValidGameId(cleanGameId)) {
            onError("Introduce un ID de partida válido")
            return
        }

        if (!isAuthenticatedPlayer(playerId)) {
            onError("El jugador no está autenticado")
            return
        }

        val gameRef = gamesCollection.document(cleanGameId)

        db.runTransaction { transaction ->

            val snapshot = transaction.get(gameRef)

            if (!snapshot.exists()) {
                throw IllegalStateException(
                    "La partida no existe"
                )
            }

            val playerX =
                snapshot.getString("playerX") ?: ""

            val playerO =
                snapshot.getString("playerO") ?: ""

            val status =
                snapshot.getString("status") ?: ""

            if (playerX == playerId) {
                throw IllegalStateException(
                    "Ya eres el jugador X de esta partida"
                )
            }

            if (playerO.isNotEmpty()) {
                throw IllegalStateException(
                    "La partida ya tiene dos jugadores"
                )
            }

            if (status != "waiting") {
                throw IllegalStateException(
                    "La partida no está disponible"
                )
            }

            transaction.update(
                gameRef,
                mapOf(
                    "playerO" to playerId,
                    "status" to "playing"
                )
            )

            true
        }
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { exception ->
                onError(
                    exception.message
                        ?: "Error al unirse a la partida"
                )
            }
    }

    // ==========================================
    // 4. ESCUCHAR CAMBIOS EN TIEMPO REAL
    // ==========================================

    fun listenGame(
        gameId: String,
        onUpdate: (Map<String, Any?>) -> Unit,
        onError: (String) -> Unit
    ): ListenerRegistration {

        return gamesCollection
            .document(gameId.trim())
            .addSnapshotListener { snapshot, error ->

                if (error != null) {
                    onError(
                        error.message
                            ?: "Error de sincronización"
                    )
                    return@addSnapshotListener
                }

                if (
                    snapshot != null &&
                    snapshot.exists()
                ) {
                    onUpdate(
                        snapshot.data ?: emptyMap()
                    )
                }
            }
    }

    // ==========================================
    // 5. ENVIAR MOVIMIENTO A FIRESTORE
    // ==========================================

    fun makeOnlineMove(
        gameId: String,
        playerId: String,
        position: Int,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {

        val cleanGameId = gameId.trim()

        if (!isValidGameId(cleanGameId)) {
            onError("ID de partida inválido")
            return
        }

        if (position !in 0..8) {
            onError("Posición inválida")
            return
        }

        if (!isAuthenticatedPlayer(playerId)) {
            onError("Jugador no autenticado")
            return
        }

        val gameRef = gamesCollection.document(cleanGameId)

        db.runTransaction { transaction ->

            val snapshot = transaction.get(gameRef)

            if (!snapshot.exists()) {
                throw IllegalStateException(
                    "La partida no existe"
                )
            }

            val playerX =
                snapshot.getString("playerX") ?: ""

            val playerO =
                snapshot.getString("playerO") ?: ""

            val status =
                snapshot.getString("status") ?: ""

            val turn =
                snapshot.getLong("turn")?.toInt() ?: 1

            val playerMark = when (playerId) {

                playerX -> 1

                playerO -> 2

                else -> throw IllegalStateException(
                    "No perteneces a esta partida"
                )
            }

            if (status != "playing") {
                throw IllegalStateException(
                    "La partida no está activa"
                )
            }

            if (turn != playerMark) {
                throw IllegalStateException(
                    "No es tu turno"
                )
            }

            val board =
                (snapshot.get("board") as? List<*>)
                    ?.map { value ->

                        (value as? Number)?.toInt()
                            ?: throw IllegalStateException(
                                "El tablero contiene datos inválidos"
                            )
                    }
                    ?.toMutableList()
                    ?: throw IllegalStateException(
                        "No se encontró el tablero"
                    )

            if (board.size != 9) {
                throw IllegalStateException(
                    "El tablero debe tener 9 casillas"
                )
            }

            if (board.any { it !in 0..2 }) {
                throw IllegalStateException(
                    "El tablero contiene valores inválidos"
                )
            }

            if (board[position] != 0) {
                throw IllegalStateException(
                    "La casilla ya está ocupada"
                )
            }

            if (checkOnlineWinner(board) != 0) {
                throw IllegalStateException(
                    "La partida ya terminó"
                )
            }

            // Guardar la marca del jugador
            board[position] = playerMark

            // Comprobar ganador o empate
            val winner = checkOnlineWinner(board)

            val nextTurn =
                if (turn == 1) 2 else 1

            val newStatus =
                if (winner == 0) "playing"
                else "finished"

            transaction.update(
                gameRef,
                mapOf(
                    "board" to board,
                    "turn" to nextTurn,
                    "winner" to winner,
                    "status" to newStatus
                )
            )

            true
        }
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { exception ->
                onError(
                    exception.message
                        ?: "No se pudo guardar la jugada"
                )
            }
    }

    // ==========================================
    // 6. SOLICITAR REVANCHA ONLINE
    // ==========================================

    fun requestRematch(
        gameId: String,
        playerId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {

        val cleanGameId = gameId.trim()

        if (!isValidGameId(cleanGameId)) {
            onError("ID de partida inválido")
            return
        }

        if (!isAuthenticatedPlayer(playerId)) {
            onError("Jugador no autenticado")
            return
        }

        val gameRef = gamesCollection.document(cleanGameId)

        db.runTransaction { transaction ->

            val snapshot = transaction.get(gameRef)

            if (!snapshot.exists()) {
                throw IllegalStateException(
                    "La partida no existe"
                )
            }

            requireParticipant(
                playerId,
                snapshot.getString("playerX") ?: "",
                snapshot.getString("playerO") ?: ""
            )

            val status =
                snapshot.getString("status") ?: ""

            if (status != "finished") {
                throw IllegalStateException(
                    "La partida debe haber terminado " +
                            "para solicitar revancha"
                )
            }

            val requestedBy =
                snapshot.getString("rematchRequestedBy") ?: ""

            if (requestedBy.isNotEmpty()) {
                throw IllegalStateException(
                    if (requestedBy == playerId) {
                        "Ya solicitaste una revancha"
                    } else {
                        "El rival ya solicitó una revancha. " +
                                "Puedes aceptarla."
                    }
                )
            }

            transaction.update(
                gameRef,
                "rematchRequestedBy",
                playerId
            )

            true
        }
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { exception ->
                onError(
                    exception.message
                        ?: "No se pudo solicitar revancha"
                )
            }
    }

    // ==========================================
    // 7. ACEPTAR REVANCHA ONLINE
    // ==========================================

    fun acceptRematch(
        gameId: String,
        playerId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {

        val cleanGameId = gameId.trim()

        if (!isValidGameId(cleanGameId)) {
            onError("ID de partida inválido")
            return
        }

        if (!isAuthenticatedPlayer(playerId)) {
            onError("Jugador no autenticado")
            return
        }

        val gameRef = gamesCollection.document(cleanGameId)

        db.runTransaction { transaction ->

            val snapshot = transaction.get(gameRef)

            if (!snapshot.exists()) {
                throw IllegalStateException(
                    "La partida no existe"
                )
            }

            val playerX =
                snapshot.getString("playerX") ?: ""

            val playerO =
                snapshot.getString("playerO") ?: ""

            requireParticipant(playerId, playerX, playerO)

            val status =
                snapshot.getString("status") ?: ""

            if (status != "finished") {
                throw IllegalStateException(
                    "La partida no ha terminado"
                )
            }

            val requestedBy =
                snapshot.getString("rematchRequestedBy") ?: ""

            if (requestedBy.isEmpty()) {
                throw IllegalStateException(
                    "No hay ninguna revancha pendiente"
                )
            }

            if (requestedBy == playerId) {
                throw IllegalStateException(
                    "No puedes aceptar tu propia solicitud"
                )
            }

            val currentRound =
                snapshot.getLong("round")?.toInt() ?: 1

            // Reiniciar el tablero en ambos dispositivos.
            // Se conservan playerX, playerO y el ID.
            transaction.update(
                gameRef,
                mapOf(
                    "board" to List(9) { 0 },
                    "turn" to 1,
                    "status" to "playing",
                    "winner" to 0,
                    "rematchRequestedBy" to "",
                    "round" to (currentRound + 1)
                )
            )

            true
        }
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { exception ->
                onError(
                    exception.message
                        ?: "No se pudo aceptar la revancha"
                )
            }
    }

    // ==========================================
    // 8. RECHAZAR REVANCHA ONLINE
    // ==========================================

    fun rejectRematch(
        gameId: String,
        playerId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {

        updateRematchRequest(
            gameId = gameId,
            playerId = playerId,
            mustBeRequester = false,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    // ==========================================
    // 9. CANCELAR SOLICITUD DE REVANCHA
    // ==========================================

    fun cancelRematch(
        gameId: String,
        playerId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {

        updateRematchRequest(
            gameId = gameId,
            playerId = playerId,
            mustBeRequester = true,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    // ==========================================
    // 10. RECHAZAR O CANCELAR SOLICITUD
    // ==========================================

    private fun updateRematchRequest(
        gameId: String,
        playerId: String,
        mustBeRequester: Boolean,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {

        val cleanGameId = gameId.trim()

        if (!isValidGameId(cleanGameId)) {
            onError("ID de partida inválido")
            return
        }

        if (!isAuthenticatedPlayer(playerId)) {
            onError("Jugador no autenticado")
            return
        }

        val gameRef = gamesCollection.document(cleanGameId)

        db.runTransaction { transaction ->

            val snapshot = transaction.get(gameRef)

            if (!snapshot.exists()) {
                throw IllegalStateException(
                    "La partida no existe"
                )
            }

            requireParticipant(
                playerId,
                snapshot.getString("playerX") ?: "",
                snapshot.getString("playerO") ?: ""
            )

            val status =
                snapshot.getString("status") ?: ""

            if (status != "finished") {
                throw IllegalStateException(
                    "La partida no ha terminado"
                )
            }

            val requestedBy =
                snapshot.getString("rematchRequestedBy") ?: ""

            if (requestedBy.isEmpty()) {
                throw IllegalStateException(
                    "No hay ninguna solicitud pendiente"
                )
            }

            if (mustBeRequester && requestedBy != playerId) {
                throw IllegalStateException(
                    "Solo quien solicitó la revancha " +
                            "puede cancelarla"
                )
            }

            if (!mustBeRequester && requestedBy == playerId) {
                throw IllegalStateException(
                    "No puedes rechazar tu propia solicitud"
                )
            }

            transaction.update(
                gameRef,
                "rematchRequestedBy",
                ""
            )

            true
        }
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { exception ->
                onError(
                    exception.message
                        ?: "No se pudo actualizar la solicitud"
                )
            }
    }

    // ==========================================
    // 11. VALIDAR AUTENTICACIÓN
    // ==========================================

    private fun isAuthenticatedPlayer(
        playerId: String
    ): Boolean {

        return playerId.isNotBlank() &&
                auth.currentUser?.uid == playerId
    }

    // ==========================================
    // 12. VALIDAR ID DE PARTIDA
    // ==========================================

    private fun isValidGameId(
        gameId: String
    ): Boolean {

        return gameId.isNotBlank() &&
                !gameId.contains("/")
    }

    // ==========================================
    // 13. VALIDAR PARTICIPANTE
    // ==========================================

    private fun requireParticipant(
        playerId: String,
        playerX: String,
        playerO: String
    ) {

        if (
            playerId != playerX &&
            playerId != playerO
        ) {
            throw IllegalStateException(
                "No perteneces a esta partida"
            )
        }
    }

    // ==========================================
    // 14. COMPROBAR GANADOR ONLINE
    // ==========================================

    private fun checkOnlineWinner(
        board: List<Int>
    ): Int {

        val winningLines = arrayOf(

            // Filas
            intArrayOf(0, 1, 2),
            intArrayOf(3, 4, 5),
            intArrayOf(6, 7, 8),

            // Columnas
            intArrayOf(0, 3, 6),
            intArrayOf(1, 4, 7),
            intArrayOf(2, 5, 8),

            // Diagonales
            intArrayOf(0, 4, 8),
            intArrayOf(2, 4, 6)
        )

        for (line in winningLines) {

            val a = line[0]
            val b = line[1]
            val c = line[2]

            if (
                board[a] != 0 &&
                board[a] == board[b] &&
                board[b] == board[c]
            ) {
                // 1 = gana X
                // 2 = gana O
                return board[a]
            }
        }

        // 3 = empate
        if (board.all { it != 0 }) {
            return 3
        }

        // 0 = partida en curso
        return 0
    }
}

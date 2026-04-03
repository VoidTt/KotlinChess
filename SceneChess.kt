package Chess

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.scene.addColorMesh
import de.fabmax.kool.scene.defaultOrbitCamera
import de.fabmax.kool.util.Color

fun main() = KoolApplication {
    val game = ChessGame.start()
    val controller = ChessSceneController(game)

    addScene {
        defaultOrbitCamera()

        lighting.singleDirectionalLight {
            setup(Vec3f(-0.7f, -1.0f, -0.5f))
            setColor(Color.WHITE, 7.5f)
        }

        controller.attach3D(this, game.snapshotPosition())
    }

    addScene {
        setupUiScene()

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(10.dp)
                .padding(10.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.35f), 12.dp))

            Column {
                Text(controller.statusText.value) {
                    modifier.margin(bottom = sizes.gap)
                }

                Text("Click a piece, then a target square.") {
                    modifier.margin(bottom = sizes.gap)
                }

                attachMiniBoard(controller, game)
            }
        }
    }
}

private class ChessSceneController(
    private val game: ChessGame
) {
    val selectedSquare = mutableStateOf<Int?>(null)
    val legalTargets = mutableStateOf(emptySet<Int>())
    val statusText = mutableStateOf("White to move")

    private val visuals = mutableListOf<PieceVisual>()
    private lateinit var scene: Scene

    fun attach3D(scene: Scene, position: ChessPosition) {
        this.scene = scene

        scene.addBoardBase3D()
        scene.addBoardSquares3D()
        spawnVisuals(position)

        refreshStatus()
        rebuildTargets()
    }

    fun onSquareClick(square: Int) {
        if (game.currentResult() !is GameResult.Ongoing) return

        val clickedPiece = game.pieceAt(square)
        val selected = selectedSquare.value

        if (selected == null) {
            if (clickedPiece?.side == game.sideToMove) {
                selectedSquare.value = square
                rebuildTargets()
            }
            return
        }

        if (selected == square) {
            selectedSquare.value = null
            rebuildTargets()
            return
        }

        val legalMove = game.legalMovesFrom(selected).firstOrNull { it.to == square }
        if (legalMove != null) {
            val movingSide = game.sideToMove
            if (game.makeMove(legalMove)) {
                applyVisualMove(legalMove, movingSide)
                selectedSquare.value = null
                rebuildTargets()
                refreshStatus()
            }
            return
        }

        if (clickedPiece?.side == game.sideToMove) {
            selectedSquare.value = square
            rebuildTargets()
        } else {
            selectedSquare.value = null
            rebuildTargets()
        }
    }

    fun rebuildTargets() {
        legalTargets.value = selectedSquare.value?.let { sq ->
            game.legalMovesFrom(sq).map { it.to }.toSet()
        } ?: emptySet()
    }

    fun refreshStatus() {
        statusText.value = when (val result = game.currentResult()) {
            GameResult.Ongoing -> {
                val side = if (game.sideToMove == Side.White) "White" else "Black"
                if (game.isKingInCheck(game.sideToMove)) {
                    "$side to move — check"
                } else {
                    "$side to move"
                }
            }

            is GameResult.Checkmate -> {
                "Checkmate! Winner: ${if (result.winner == Side.White) "White" else "Black"}"
            }

            GameResult.Stalemate -> "Stalemate"
            GameResult.DrawByRepetition -> "Draw by repetition"
            GameResult.DrawByFiftyMoveRule -> "Draw by 50-move rule"
            GameResult.DrawByInsufficientMaterial -> "Draw by insufficient material"
        }
    }

    fun syncVisualsFromGame() {
        for (v in visuals) {
            v.capture()
        }
        visuals.clear()
        spawnVisuals(game.snapshotPosition())
    }

    private fun spawnVisuals(position: ChessPosition) {
        visuals.clear()

        for (square in 0 until 64) {
            val piece = position.board[square] ?: continue
            visuals += PieceVisual(scene, piece.side, piece.type, square)
        }
    }

    private fun applyVisualMove(move: ChessMove, movingSide: Side) {
        val mover = visuals.lastOrNull { !it.captured && it.square == move.from && it.side == movingSide }
        val capturedAtTarget = visuals.lastOrNull { !it.captured && it.square == move.to && it !== mover }

        if (move.isEnPassant) {
            val capturedSquare = if (movingSide == Side.White) move.to - 8 else move.to + 8
            visuals.lastOrNull { !it.captured && it.square == capturedSquare }?.capture()
        } else {
            capturedAtTarget?.capture()
        }

        mover?.moveTo(move.to)

        if (move.isCastle) {
            when (move.to) {
                index("g1") -> visuals.lastOrNull { !it.captured && it.square == index("h1") }?.moveTo(index("f1"))
                index("c1") -> visuals.lastOrNull { !it.captured && it.square == index("a1") }?.moveTo(index("d1"))
                index("g8") -> visuals.lastOrNull { !it.captured && it.square == index("h8") }?.moveTo(index("f8"))
                index("c8") -> visuals.lastOrNull { !it.captured && it.square == index("a8") }?.moveTo(index("d8"))
            }
        }

        if (move.promotion != null && mover != null) {
            mover.capture()
            visuals += PieceVisual(scene, movingSide, move.promotion, move.to)
        }
    }

    private class PieceVisual(
        private val scene: Scene,
        val side: Side,
        var type: PieceType,
        var square: Int
    ) {
        private val parts = mutableListOf<PiecePart>()
        var captured: Boolean = false
            private set

        init {
            spawn()
        }

        fun moveTo(newSquare: Int) {
            if (captured) return

            val dx = squareX(fileOf(newSquare)) - squareX(fileOf(square))
            val dz = squareZ(rankOf(newSquare)) - squareZ(rankOf(square))

            for (part in parts) {
                part.translate(dx, 0f, dz)
            }

            square = newSquare
        }

        fun capture() {
            if (captured) return
            captured = true
            for (part in parts) {
                part.hide()
            }
        }

        private fun spawn() {
            val color = if (side == Side.White) WHITE_PIECE else BLACK_PIECE
            val x = squareX(fileOf(square))
            val z = squareZ(rankOf(square))
            val lift = 0.06f

            when (type) {
                PieceType.Pawn -> {
                    parts += scene.addPiecePart(color, x, lift + 0.08f, z, 0.50f, 0.14f, 0.50f)
                    parts += scene.addPiecePart(color, x, lift + 0.32f, z, 0.38f, 0.40f, 0.38f)
                    parts += scene.addPiecePart(color, x, lift + 0.60f, z, 0.25f, 0.16f, 0.25f)
                    parts += scene.addPiecePart(color, x, lift + 0.80f, z, 0.16f, 0.16f, 0.16f)
                }

                PieceType.Rook -> {
                    parts += scene.addPiecePart(color, x, lift + 0.08f, z, 0.58f, 0.14f, 0.58f)
                    parts += scene.addPiecePart(color, x, lift + 0.42f, z, 0.50f, 0.56f, 0.50f)
                    parts += scene.addPiecePart(color, x, lift + 0.82f, z, 0.56f, 0.10f, 0.56f)
                    val topY = lift + 0.94f
                    val w = 0.11f
                    val d = 0.11f
                    parts += scene.addPiecePart(color, x - 0.22f, topY, z - 0.22f, w, 0.12f, d)
                    parts += scene.addPiecePart(color, x + 0.22f, topY, z - 0.22f, w, 0.12f, d)
                    parts += scene.addPiecePart(color, x - 0.22f, topY, z + 0.22f, w, 0.12f, d)
                    parts += scene.addPiecePart(color, x + 0.22f, topY, z + 0.22f, w, 0.12f, d)
                }

                PieceType.Knight -> {
                    parts += scene.addPiecePart(color, x, lift + 0.08f, z, 0.58f, 0.14f, 0.58f)
                    parts += scene.addPiecePart(color, x, lift + 0.35f, z, 0.46f, 0.50f, 0.46f)
                    parts += scene.addPiecePart(color, x + 0.03f, lift + 0.72f, z - 0.02f, 0.34f, 0.30f, 0.28f)
                    parts += scene.addPiecePart(color, x + 0.11f, lift + 0.98f, z - 0.03f, 0.26f, 0.26f, 0.20f)
                    parts += scene.addPiecePart(color, x + 0.17f, lift + 1.18f, z - 0.04f, 0.18f, 0.18f, 0.16f)
                    parts += scene.addPiecePart(color, x + 0.20f, lift + 1.00f, z + 0.03f, 0.14f, 0.16f, 0.20f)
                    parts += scene.addPiecePart(color, x + 0.12f, lift + 1.12f, z + 0.02f, 0.10f, 0.10f, 0.14f)
                }

                PieceType.Bishop -> {
                    parts += scene.addPiecePart(color, x, lift + 0.08f, z, 0.56f, 0.14f, 0.56f)
                    parts += scene.addPiecePart(color, x, lift + 0.34f, z, 0.42f, 0.50f, 0.42f)
                    parts += scene.addPiecePart(color, x, lift + 0.78f, z, 0.26f, 0.52f, 0.26f)
                    parts += scene.addPiecePart(color, x, lift + 1.08f, z, 0.32f, 0.10f, 0.10f)
                    parts += scene.addPiecePart(color, x, lift + 1.08f, z, 0.10f, 0.10f, 0.32f)
                }

                PieceType.Queen -> {
                    parts += scene.addPiecePart(color, x, lift + 0.08f, z, 0.60f, 0.14f, 0.60f)
                    parts += scene.addPiecePart(color, x, lift + 0.34f, z, 0.46f, 0.56f, 0.46f)
                    parts += scene.addPiecePart(color, x, lift + 0.74f, z, 0.30f, 0.18f, 0.30f)
                    parts += scene.addPiecePart(color, x, lift + 0.94f, z, 0.38f, 0.12f, 0.38f)
                    val spikeY = lift + 1.08f
                    parts += scene.addPiecePart(color, x, spikeY, z - 0.20f, 0.08f, 0.16f, 0.08f)
                    parts += scene.addPiecePart(color, x - 0.18f, spikeY, z, 0.08f, 0.16f, 0.08f)
                    parts += scene.addPiecePart(color, x + 0.18f, spikeY, z, 0.08f, 0.16f, 0.08f)
                    parts += scene.addPiecePart(color, x, spikeY, z + 0.20f, 0.08f, 0.16f, 0.08f)
                    parts += scene.addPiecePart(color, x, spikeY + 0.12f, z, 0.10f, 0.10f, 0.10f)
                }

                PieceType.King -> {
                    parts += scene.addPiecePart(color, x, lift + 0.08f, z, 0.62f, 0.14f, 0.62f)
                    parts += scene.addPiecePart(color, x, lift + 0.34f, z, 0.48f, 0.58f, 0.48f)
                    parts += scene.addPiecePart(color, x, lift + 0.78f, z, 0.34f, 0.16f, 0.34f)
                    parts += scene.addPiecePart(color, x, lift + 1.02f, z, 0.12f, 0.42f, 0.12f)
                    parts += scene.addPiecePart(color, x, lift + 1.18f, z, 0.28f, 0.10f, 0.10f)
                    parts += scene.addPiecePart(color, x, lift + 1.18f, z, 0.10f, 0.10f, 0.28f)
                }
            }
        }
    }

    class PiecePart(
        scene: Scene,
        x: Float,
        y: Float,
        z: Float,
        sx: Float,
        sy: Float,
        sz: Float
    ) {
        private val node = scene.addColorMesh {
            generate { cube { colored() } }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
            transform.scale(Vec3f(sx, sy, sz))
            transform.translate(Vec3f(x, y, z))
        }

        fun translate(dx: Float, dy: Float, dz: Float) {
            node.transform.translate(Vec3f(dx, dy, dz))
        }

        fun hide() {
            node.transform.translate(Vec3f(50f, -50f, 50f))
        }
    }
}

private fun ColumnScope.attachMiniBoard(controller: ChessSceneController, game: ChessGame) {
    for (rank in 7 downTo 0) {
        Row {
            for (file in 0..7) {
                val sq = index(file, rank)
                val piece = game.pieceAt(sq)

                val label = when (piece?.type) {
                    null -> " "
                    PieceType.Pawn -> if (piece.side == Side.White) "♙" else "♟"
                    PieceType.Knight -> if (piece.side == Side.White) "♘" else "♞"
                    PieceType.Bishop -> if (piece.side == Side.White) "♗" else "♝"
                    PieceType.Rook -> if (piece.side == Side.White) "♖" else "♜"
                    PieceType.Queen -> if (piece.side == Side.White) "♕" else "♛"
                    PieceType.King -> if (piece.side == Side.White) "♔" else "♚"
                }

                val bg = when {
                    controller.selectedSquare.value == sq -> Color(0.28f, 0.53f, 0.95f, 1f)
                    sq in controller.legalTargets.value -> Color(0.31f, 0.73f, 0.42f, 1f)
                    else -> if ((file + rank) % 2 == 0) Color(0.90f, 0.82f, 0.67f, 1f) else Color(0.43f, 0.27f, 0.15f, 1f)
                }

                Button(label) {
                    modifier
                        .size(36.dp, 36.dp)
                        .margin(1.dp)
                        .background(RoundRectBackground(bg, 4.dp))
                        .onClick { controller.onSquareClick(sq) }
                }
            }
        }
    }

    Row {
        Button("Undo") {
            modifier.margin(top = sizes.gap).onClick {
                if (game.undo()) {
                    controller.selectedSquare.value = null
                    controller.rebuildTargets()
                    controller.refreshStatus()
                    controller.syncVisualsFromGame()
                }
            }
        }

        Button("Reset") {
            modifier.margin(start = sizes.gap, top = sizes.gap).onClick {
                game.loadPosition(ChessPosition.start())
                controller.selectedSquare.value = null
                controller.rebuildTargets()
                controller.refreshStatus()
                controller.syncVisualsFromGame()
            }
        }
    }
}

private val BOARD_LIGHT = Color(0.90f, 0.82f, 0.67f, 1f)
private val BOARD_DARK = Color(0.43f, 0.27f, 0.15f, 1f)
private val WHITE_PIECE = Color(0.96f, 0.96f, 0.93f, 1f)
private val BLACK_PIECE = Color(0.10f, 0.10f, 0.10f, 1f)
private val BOARD_FRAME = Color(0.18f, 0.12f, 0.08f, 1f)

private fun Scene.addBoardBase3D() {
    addCube(BOARD_FRAME, 0f, -0.13f, 0f, 8.6f, 0.22f, 8.6f)
    addCube(Color(0.22f, 0.15f, 0.09f, 1f), 0f, -0.02f, 0f, 8.15f, 0.06f, 8.15f)
}

private fun Scene.addBoardSquares3D() {
    for (rank in 0..7) {
        for (file in 0..7) {
            val isLight = (file + rank) % 2 == 0
            addCube(
                if (isLight) BOARD_LIGHT else BOARD_DARK,
                squareX(file),
                0.0f,
                squareZ(rank),
                0.96f,
                0.08f,
                0.96f
            )
        }
    }
}

private fun Scene.addPiecePart(
    color: Color,
    x: Float,
    y: Float,
    z: Float,
    sx: Float,
    sy: Float,
    sz: Float
): ChessSceneController.PiecePart {
    return ChessSceneController.PiecePart(this, x, y, z, sx, sy, sz)
}

private fun Scene.addCube(
    color: Color,
    x: Float,
    y: Float,
    z: Float,
    sx: Float,
    sy: Float,
    sz: Float
) {
    addColorMesh {
        generate { cube { colored() } }
        shader = KslPbrShader {
            color { vertexColor() }
            metallic(0f)
            roughness(0.25f)
        }
        transform.scale(Vec3f(sx, sy, sz))
        transform.translate(Vec3f(x, y, z))
    }
}

private fun squareX(file: Int): Float = file - 3.5f
private fun squareZ(rank: Int): Float = rank - 3.5f

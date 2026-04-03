package Chess


import kotlinx.serialization.Serializable
import kotlin.math.abs


// =========================================================
//  CHESS CORE (your logic, kept in the same file/package)
// =========================================================

@Serializable
enum class Side {
    White, Black;
    fun opposite(): Side = if (this == White) Black else White
}

@Serializable
enum class PieceType {
    Pawn, Knight, Bishop, Rook, Queen, King
}

@Serializable
data class Piece(
    val side: Side,
    val type: PieceType
)

@Serializable
data class CastlingRights(
    val whiteKingSide: Boolean = true,
    val whiteQueenSide: Boolean = true,
    val blackKingSide: Boolean = true,
    val blackQueenSide: Boolean = true
)

@Serializable
data class ChessPosition(
    val board: List<Piece?>,
    val sideToMove: Side,
    val castlingRights: CastlingRights,
    val enPassantTarget: Int? = null,
    val halfmoveClock: Int = 0,
    val fullmoveNumber: Int = 1
) {
    init { require(board.size == 64) { "Board must contain exactly one 64-square board." } }

    companion object {
        fun start(): ChessPosition {
            val board = MutableList<Piece?>(64) { null }
            fun set(idx: Int, side: Side, type: PieceType) { board[idx] = Piece(side, type) }

            // White
            set(index("a1"), Side.White, PieceType.Rook)
            set(index("b1"), Side.White, PieceType.Knight)
            set(index("c1"), Side.White, PieceType.Bishop)
            set(index("d1"), Side.White, PieceType.Queen)
            set(index("e1"), Side.White, PieceType.King)
            set(index("f1"), Side.White, PieceType.Bishop)
            set(index("g1"), Side.White, PieceType.Knight)
            set(index("h1"), Side.White, PieceType.Rook)
            for (file in 'a'..'h') set(index("$file" + "2"), Side.White, PieceType.Pawn)

            // Black
            set(index("a8"), Side.Black, PieceType.Rook)
            set(index("b8"), Side.Black, PieceType.Knight)
            set(index("c8"), Side.Black, PieceType.Bishop)
            set(index("d8"), Side.Black, PieceType.Queen)
            set(index("e8"), Side.Black, PieceType.King)
            set(index("f8"), Side.Black, PieceType.Bishop)
            set(index("g8"), Side.Black, PieceType.Knight)
            set(index("h8"), Side.Black, PieceType.Rook)
            for (file in 'a'..'h') set(index("$file" + "7"), Side.Black, PieceType.Pawn)

            return ChessPosition(board, Side.White, CastlingRights(), null, 0, 1)
        }
    }
}

@Serializable
data class ChessMove(
    val from: Int,
    val to: Int,
    val promotion: PieceType? = null,
    val isEnPassant: Boolean = false,
    val isCastle: Boolean = false
) {
    fun toUci(): String {
        val promo = promotion?.let {
            when (it) {
                PieceType.Queen -> "q"
                PieceType.Rook -> "r"
                PieceType.Bishop -> "b"
                PieceType.Knight -> "n"
                else -> ""
            }
        } ?: ""
        return "${squareName(from)}${squareName(to)}$promo"
    }
}

sealed interface GameResult {
    data object Ongoing : GameResult
    data class Checkmate(val winner: Side) : GameResult
    data object Stalemate : GameResult
    data object DrawByRepetition : GameResult
    data object DrawByFiftyMoveRule : GameResult
    data object DrawByInsufficientMaterial : GameResult
}

class ChessGame private constructor(initial: ChessPosition) {
    private data class GameSnapshot(
        val board: Array<Piece?>,
        val sideToMove: Side,
        val castlingRights: CastlingRights,
        val enPassantTarget: Int?,
        val halfmoveClock: Int,
        val fullmoveNumber: Int,
        val repetitionCounts: Map<String, Int>
    )

    private val board: Array<Piece?> = arrayOfNulls(64)
    var sideToMove: Side = Side.White; private set
    var castlingRights: CastlingRights = CastlingRights(); private set
    var enPassantTarget: Int? = null; private set
    var halfmoveClock: Int = 0; private set
    var fullmoveNumber: Int = 1; private set
    private val history = mutableListOf<GameSnapshot>()
    private val repetitionCounts = mutableMapOf<String, Int>()

    init { loadPosition(initial) }

    companion object {
        fun start(): ChessGame = ChessGame(ChessPosition.start())
        fun fromFen(fen: String): ChessGame = ChessGame(ChessPosition.fromFEN(fen))
        fun fromPosition(position: ChessPosition): ChessGame = ChessGame(position)
    }

    fun snapshotPosition(): ChessPosition = ChessPosition(
        board = board.toList(),
        sideToMove = sideToMove,
        castlingRights = castlingRights,
        enPassantTarget = enPassantTarget,
        halfmoveClock = halfmoveClock,
        fullmoveNumber = fullmoveNumber
    )

    fun loadPosition(position: ChessPosition) {
        require(position.board.size == 64)
        for (i in 0 until 64) board[i] = position.board[i]
        sideToMove = position.sideToMove
        castlingRights = position.castlingRights
        enPassantTarget = position.enPassantTarget
        halfmoveClock = position.halfmoveClock
        fullmoveNumber = position.fullmoveNumber
        history.clear(); repetitionCounts.clear(); repetitionCounts[positionKey()] = 1
        validateKings()
    }

    fun toFen(includeMoveCounters: Boolean = true): String {
        val boardPart = buildString {
            for (rank in 7 downTo 0) {
                var empty = 0
                for (file in 0..7) {
                    val p = board[index(file, rank)]
                    if (p == null) empty++ else {
                        if (empty > 0) { append(empty); empty = 0 }
                        append(pieceToFenChar(p))
                    }
                }
                if (empty > 0) append(empty)
                if (rank > 0) append('/')
            }
        }
        val sidePart = if (sideToMove == Side.White) "w" else "b"
        val castlingPart = buildString {
            if (castlingRights.whiteKingSide) append('K')
            if (castlingRights.whiteQueenSide) append('Q')
            if (castlingRights.blackKingSide) append('k')
            if (castlingRights.blackQueenSide) append('q')
            if (isEmpty()) append('-')
        }
        val epPart = enPassantTarget?.let(::squareName) ?: "-"
        return if (includeMoveCounters) "$boardPart $sidePart $castlingPart $epPart $halfmoveClock $fullmoveNumber"
        else "$boardPart $sidePart $castlingPart $epPart"
    }

    fun legalMoves(): List<ChessMove> {
        val pseudo = generatePseudoLegalMoves(sideToMove)
        return pseudo.filter { move ->
            val clone = cloneGame()
            clone.applyMoveUnchecked(move)
            !clone.isKingInCheck(sideToMove)
        }
    }

    fun legalMovesFrom(square: Int): List<ChessMove> = legalMoves().filter { it.from == square }
    fun legalMovesFrom(squareName: String): List<ChessMove> = legalMovesFrom(parseSquare(squareName))
    fun isKingInCheck(side: Side): Boolean = findKingSquare(side)?.let { isSquareAttacked(it, side.opposite()) } ?: true

    fun currentResult(): GameResult {
        if (isFiftyMoveDraw()) return GameResult.DrawByFiftyMoveRule
        if (isThreefoldRepetition()) return GameResult.DrawByRepetition
        if (isInsufficientMaterial()) return GameResult.DrawByInsufficientMaterial
        val moves = legalMoves()
        if (moves.isNotEmpty()) return GameResult.Ongoing
        return if (isKingInCheck(sideToMove)) GameResult.Checkmate(sideToMove.opposite()) else GameResult.Stalemate
    }

    fun makeMove(move: ChessMove): Boolean {
        val legal = legalMoves().firstOrNull {
            it.from == move.from && it.to == move.to && it.promotion == move.promotion
        } ?: return false
        history.add(snapshotForUndo())
        applyMoveUnchecked(legal)
        repetitionCounts[positionKey()] = (repetitionCounts[positionKey()] ?: 0) + 1
        validateKings()
        return true
    }

    fun makeMove(from: Int, to: Int, promotion: PieceType? = null): Boolean = makeMove(ChessMove(from, to, promotion))
    fun makeMove(from: String, to: String, promotion: PieceType? = null): Boolean = makeMove(parseSquare(from), parseSquare(to), promotion)
    fun makeMoveUci(uci: String): Boolean = parseUci(uci)?.let(::makeMove) ?: false

    fun undo(): Boolean {
        if (history.isEmpty()) return false
        restoreSnapshot(history.removeAt(history.lastIndex))
        return true
    }

    fun pieceAt(square: Int): Piece? = board[square]
    fun pieceAt(square: String): Piece? = board[parseSquare(square)]
    fun allPieces(): List<Pair<Int, Piece>> = buildList { for (i in 0 until 64) board[i]?.let { add(i to it) } }
    fun positionKey(): String = toFen(includeMoveCounters = false)

    private fun cloneGame(): ChessGame = fromPosition(snapshotPosition())
    private fun snapshotForUndo(): GameSnapshot = GameSnapshot(board.copyOf(), sideToMove, castlingRights, enPassantTarget, halfmoveClock, fullmoveNumber, repetitionCounts.toMap())
    private fun restoreSnapshot(snapshot: GameSnapshot) { for (i in 0 until 64) board[i] = snapshot.board[i]; sideToMove = snapshot.sideToMove; castlingRights = snapshot.castlingRights; enPassantTarget = snapshot.enPassantTarget; halfmoveClock = snapshot.halfmoveClock; fullmoveNumber = snapshot.fullmoveNumber; repetitionCounts.clear(); repetitionCounts.putAll(snapshot.repetitionCounts) }

    private fun applyMoveUnchecked(move: ChessMove) {
        val movingPiece = board[move.from] ?: error("No piece on from-square.")
        val targetPiece = board[move.to]
        var captureOccurred = targetPiece != null
        var captureSquare = move.to

        if (move.isEnPassant) {
            val capturedPawnSquare = if (movingPiece.side == Side.White) move.to - 8 else move.to + 8
            board[capturedPawnSquare] = null
            captureOccurred = true
            captureSquare = capturedPawnSquare
        }

        board[move.from] = null
        board[move.to] = movingPiece
        if (movingPiece.type == PieceType.Pawn && move.promotion != null) board[move.to] = Piece(movingPiece.side, move.promotion)

        if (move.isCastle && movingPiece.type == PieceType.King) {
            when (move.to) {
                index("g1") -> { board[index("h1")] = null; board[index("f1")] = Piece(Side.White, PieceType.Rook) }
                index("c1") -> { board[index("a1")] = null; board[index("d1")] = Piece(Side.White, PieceType.Rook) }
                index("g8") -> { board[index("h8")] = null; board[index("f8")] = Piece(Side.Black, PieceType.Rook) }
                index("c8") -> { board[index("a8")] = null; board[index("d8")] = Piece(Side.Black, PieceType.Rook) }
            }
        }

        castlingRights = updateCastlingRightsAfterMove(castlingRights, movingPiece, move.from, if (move.isEnPassant) captureSquare else move.to, targetPiece)
        enPassantTarget = if (movingPiece.type == PieceType.Pawn && abs(move.to - move.from) == 16) (move.from + move.to) / 2 else null
        halfmoveClock = if (movingPiece.type == PieceType.Pawn || captureOccurred) 0 else halfmoveClock + 1
        if (sideToMove == Side.Black) fullmoveNumber++
        sideToMove = sideToMove.opposite()
    }

    private fun generatePseudoLegalMoves(side: Side): List<ChessMove> {
        val result = ArrayList<ChessMove>()
        for (from in 0 until 64) {
            val piece = board[from] ?: continue
            if (piece.side != side) continue
            when (piece.type) {
                PieceType.Pawn -> generatePawnMoves(from, piece, result)
                PieceType.Knight -> generateKnightMoves(from, piece, result)
                PieceType.Bishop -> generateSlidingMoves(from, piece, result, bishopDirections)
                PieceType.Rook -> generateSlidingMoves(from, piece, result, rookDirections)
                PieceType.Queen -> { generateSlidingMoves(from, piece, result, bishopDirections); generateSlidingMoves(from, piece, result, rookDirections) }
                PieceType.King -> generateKingMoves(from, piece, result)
            }
        }
        return result
    }

    private fun generatePawnMoves(from: Int, piece: Piece, out: MutableList<ChessMove>) {
        val file = fileOf(from)
        val rank = rankOf(from)
        val dir = if (piece.side == Side.White) 1 else -1
        val startRank = if (piece.side == Side.White) 1 else 6
        val promotionRank = if (piece.side == Side.White) 7 else 0

        val oneRank = rank + dir
        if (inBoard(file, oneRank)) {
            val oneForward = index(file, oneRank)
            if (board[oneForward] == null) {
                if (oneRank == promotionRank) addPromotionMoves(from, oneForward, out) else out.add(ChessMove(from, oneForward))
                val twoRank = rank + dir * 2
                if (rank == startRank && inBoard(file, twoRank)) {
                    val twoForward = index(file, twoRank)
                    if (board[twoForward] == null) out.add(ChessMove(from, twoForward))
                }
            }
        }

        for (df in intArrayOf(-1, 1)) {
            val cf = file + df
            val cr = rank + dir
            if (!inBoard(cf, cr)) continue
            val target = index(cf, cr)
            val targetPiece = board[target]
            val isEnPassantCapture = enPassantTarget == target && targetPiece == null && run {
                val capturedPawnSquare = if (piece.side == Side.White) target - 8 else target + 8
                board.getOrNull(capturedPawnSquare)?.type == PieceType.Pawn && board.getOrNull(capturedPawnSquare)?.side == piece.side.opposite()
            }
            if (targetPiece != null && targetPiece.side != piece.side) {
                if (cr == promotionRank) addPromotionMoves(from, target, out) else out.add(ChessMove(from, target))
            } else if (isEnPassantCapture) {
                out.add(ChessMove(from, target, isEnPassant = true))
            }
        }
    }

    private fun addPromotionMoves(from: Int, to: Int, out: MutableList<ChessMove>) {
        out.add(ChessMove(from, to, PieceType.Queen))
        out.add(ChessMove(from, to, PieceType.Rook))
        out.add(ChessMove(from, to, PieceType.Bishop))
        out.add(ChessMove(from, to, PieceType.Knight))
    }

    private fun generateKnightMoves(from: Int, piece: Piece, out: MutableList<ChessMove>) {
        val file = fileOf(from); val rank = rankOf(from)
        for ((df, dr) in knightOffsets) {
            val nf = file + df; val nr = rank + dr
            if (!inBoard(nf, nr)) continue
            val to = index(nf, nr)
            val target = board[to]
            if (target == null || target.side != piece.side) out.add(ChessMove(from, to))
        }
    }

    private fun generateSlidingMoves(from: Int, piece: Piece, out: MutableList<ChessMove>, directions: List<Pair<Int, Int>>) {
        val startFile = fileOf(from); val startRank = rankOf(from)
        for ((df, dr) in directions) {
            var f = startFile; var r = startRank
            while (true) {
                f += df; r += dr
                if (!inBoard(f, r)) break
                val to = index(f, r)
                val target = board[to]
                if (target == null) out.add(ChessMove(from, to)) else {
                    if (target.side != piece.side) out.add(ChessMove(from, to))
                    break
                }
            }
        }
    }

    private fun generateKingMoves(from: Int, piece: Piece, out: MutableList<ChessMove>) {
        val file = fileOf(from); val rank = rankOf(from)
        for ((df, dr) in kingOffsets) {
            val nf = file + df; val nr = rank + dr
            if (!inBoard(nf, nr)) continue
            val to = index(nf, nr)
            val target = board[to]
            if (target == null || target.side != piece.side) out.add(ChessMove(from, to))
        }
        if (piece.side == Side.White && from == index("e1")) {
            if (castlingRights.whiteKingSide && board[index("f1")] == null && board[index("g1")] == null && board[index("h1")]?.side == Side.White && board[index("h1")]?.type == PieceType.Rook && !isSquareAttacked(index("e1"), Side.Black) && !isSquareAttacked(index("f1"), Side.Black) && !isSquareAttacked(index("g1"), Side.Black)) out.add(ChessMove(from, index("g1"), isCastle = true))
            if (castlingRights.whiteQueenSide && board[index("d1")] == null && board[index("c1")] == null && board[index("b1")] == null && board[index("a1")]?.side == Side.White && board[index("a1")]?.type == PieceType.Rook && !isSquareAttacked(index("e1"), Side.Black) && !isSquareAttacked(index("d1"), Side.Black) && !isSquareAttacked(index("c1"), Side.Black)) out.add(ChessMove(from, index("c1"), isCastle = true))
        }
        if (piece.side == Side.Black && from == index("e8")) {
            if (castlingRights.blackKingSide && board[index("f8")] == null && board[index("g8")] == null && board[index("h8")]?.side == Side.Black && board[index("h8")]?.type == PieceType.Rook && !isSquareAttacked(index("e8"), Side.White) && !isSquareAttacked(index("f8"), Side.White) && !isSquareAttacked(index("g8"), Side.White)) out.add(ChessMove(from, index("g8"), isCastle = true))
            if (castlingRights.blackQueenSide && board[index("d8")] == null && board[index("c8")] == null && board[index("b8")] == null && board[index("a8")]?.side == Side.Black && board[index("a8")]?.type == PieceType.Rook && !isSquareAttacked(index("e8"), Side.White) && !isSquareAttacked(index("d8"), Side.White) && !isSquareAttacked(index("c8"), Side.White)) out.add(ChessMove(from, index("c8"), isCastle = true))
        }
    }

    private fun isSquareAttacked(square: Int, bySide: Side): Boolean {
        val file = fileOf(square); val rank = rankOf(square)
        val pawnSourceRank = rank - if (bySide == Side.White) 1 else -1
        if (inBoard(file - 1, pawnSourceRank)) {
            val p = board[index(file - 1, pawnSourceRank)]
            if (p?.side == bySide && p.type == PieceType.Pawn) return true
        }
        if (inBoard(file + 1, pawnSourceRank)) {
            val p = board[index(file + 1, pawnSourceRank)]
            if (p?.side == bySide && p.type == PieceType.Pawn) return true
        }
        for ((df, dr) in knightOffsets) {
            val nf = file + df; val nr = rank + dr
            if (!inBoard(nf, nr)) continue
            val p = board[index(nf, nr)]
            if (p?.side == bySide && p.type == PieceType.Knight) return true
        }
        if (isAttackedBySliding(square, bySide, bishopDirections, setOf(PieceType.Bishop, PieceType.Queen))) return true
        if (isAttackedBySliding(square, bySide, rookDirections, setOf(PieceType.Rook, PieceType.Queen))) return true
        for ((df, dr) in kingOffsets) {
            val nf = file + df; val nr = rank + dr
            if (!inBoard(nf, nr)) continue
            val p = board[index(nf, nr)]
            if (p?.side == bySide && p.type == PieceType.King) return true
        }
        return false
    }

    private fun isAttackedBySliding(square: Int, bySide: Side, directions: List<Pair<Int, Int>>, validTypes: Set<PieceType>): Boolean {
        val startFile = fileOf(square); val startRank = rankOf(square)
        for ((df, dr) in directions) {
            var f = startFile; var r = startRank
            while (true) {
                f += df; r += dr
                if (!inBoard(f, r)) break
                val p = board[index(f, r)] ?: continue
                if (p.side == bySide && p.type in validTypes) return true
                break
            }
        }
        return false
    }

    private fun isFiftyMoveDraw(): Boolean = halfmoveClock >= 100
    private fun isThreefoldRepetition(): Boolean = (repetitionCounts[positionKey()] ?: 0) >= 3
    private fun isInsufficientMaterial(): Boolean {
        val pieces = allPieces()
        val nonKings = pieces.filter { it.second.type != PieceType.King }.map { it.second }
        if (nonKings.isEmpty()) return true
        if (nonKings.any { it.type == PieceType.Pawn || it.type == PieceType.Rook || it.type == PieceType.Queen }) return false
        if (nonKings.size == 1) return true
        if (nonKings.size == 2 && nonKings.all { it.type == PieceType.Knight }) return true
        if (nonKings.size == 2 && nonKings.all { it.type == PieceType.Bishop }) {
            val bishopSquares = pieces.filter { it.second.type == PieceType.Bishop }.map { it.first }
            if (bishopSquares.size == 2) {
                val c1 = (fileOf(bishopSquares[0]) + rankOf(bishopSquares[0])) % 2
                val c2 = (fileOf(bishopSquares[1]) + rankOf(bishopSquares[1])) % 2
                if (c1 == c2) return true
            }
        }
        return false
    }

    private fun validateKings() {
        var whiteKing = 0; var blackKing = 0
        for (p in board) when (p) {
            null -> Unit
            else -> if (p.type == PieceType.King) if (p.side == Side.White) whiteKing++ else blackKing++
        }
        require(whiteKing == 1) { "Position must contain exactly one white king." }
        require(blackKing == 1) { "Position must contain exactly one black king." }
    }

    private fun findKingSquare(side: Side): Int? {
        for (i in 0 until 64) {
            val p = board[i]
            if (p?.side == side && p.type == PieceType.King) return i
        }
        return null
    }

    private fun updateCastlingRightsAfterMove(rights: CastlingRights, movingPiece: Piece, from: Int, captureSquare: Int, capturedPiece: Piece?): CastlingRights {
        var wk = rights.whiteKingSide; var wq = rights.whiteQueenSide; var bk = rights.blackKingSide; var bq = rights.blackQueenSide
        when (movingPiece.type) {
            PieceType.King -> if (movingPiece.side == Side.White) { wk = false; wq = false } else { bk = false; bq = false }
            PieceType.Rook -> when (from) {
                index("h1") -> wk = false
                index("a1") -> wq = false
                index("h8") -> bk = false
                index("a8") -> bq = false
            }
            else -> Unit
        }
        if (capturedPiece?.type == PieceType.Rook) when (captureSquare) {
            index("h1") -> wk = false
            index("a1") -> wq = false
            index("h8") -> bk = false
            index("a8") -> bq = false
        }
        return CastlingRights(wk, wq, bk, bq)
    }

    private fun parseUci(uci: String): ChessMove? {
        val text = uci.trim()
        if (text.length !in 4..5) return null
        val from = parseSquare(text.substring(0, 2))
        val to = parseSquare(text.substring(2, 4))
        val promotion = text.getOrNull(4)?.let {
            when (it.lowercaseChar()) {
                'q' -> PieceType.Queen
                'r' -> PieceType.Rook
                'b' -> PieceType.Bishop
                'n' -> PieceType.Knight
                else -> null
            }
        }
        return ChessMove(from, to, promotion)
    }
}

private val knightOffsets = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
private val bishopDirections = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
private val rookDirections = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
private val kingOffsets = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)

fun fileOf(index: Int): Int = index % 8
fun rankOf(index: Int): Int = index / 8
fun index(file: Int, rank: Int): Int = rank * 8 + file
fun inBoard(file: Int, rank: Int): Boolean = file in 0..7 && rank in 0..7
fun index(square: String): Int = parseSquare(square)

fun parseSquare(square: String): Int {
    require(square.length == 2) { "Invalid square: $square" }
    val fileChar = square[0].lowercaseChar(); val rankChar = square[1]
    require(fileChar in 'a'..'h'); require(rankChar in '1'..'8')
    return index(fileChar - 'a', rankChar - '1')
}

fun squareName(index: Int): String {
    require(index in 0..63)
    val file = ('a'.code + fileOf(index)).toChar()
    val rank = ('1'.code + rankOf(index)).toChar()
    return "$file$rank"
}

private fun pieceToFenChar(piece: Piece): Char {
    val c = when (piece.type) {
        PieceType.Pawn -> 'p'; PieceType.Knight -> 'n'; PieceType.Bishop -> 'b'; PieceType.Rook -> 'r'; PieceType.Queen -> 'q'; PieceType.King -> 'k'
    }
    return if (piece.side == Side.White) c.uppercaseChar() else c
}

private fun ChessPosition.Companion.fromFEN(fen: String): ChessPosition {
    val parts = fen.trim().split(Regex("\\s+"))
    require(parts.size in 4..6)
    val boardPart = parts[0]; val sidePart = parts[1]; val castlingPart = parts[2]; val epPart = parts[3]
    val halfmovePart = parts.getOrNull(4)?.toIntOrNull() ?: 0
    val fullmovePart = parts.getOrNull(5)?.toIntOrNull() ?: 1

    val board = MutableList<Piece?>(64) { null }
    val rows = boardPart.split("/")
    require(rows.size == 8)
    for ((fenRankIndex, row) in rows.withIndex()) {
        var file = 0
        val rank = 7 - fenRankIndex
        for (ch in row) {
            if (ch.isDigit()) file += ch.digitToInt() else {
                require(file in 0..7)
                board[index(file, rank)] = pieceFromFenChar(ch)
                file++
            }
        }
        require(file == 8)
    }

    val sideToMove = when (sidePart) { "w" -> Side.White; "b" -> Side.Black; else -> error("Invalid FEN side") }
    val castlingRights = CastlingRights('K' in castlingPart, 'Q' in castlingPart, 'k' in castlingPart, 'q' in castlingPart)
    val enPassantTarget = if (epPart == "-") null else parseSquare(epPart)
    return ChessPosition(board, sideToMove, castlingRights, enPassantTarget, halfmovePart, fullmovePart)
}

private fun pieceFromFenChar(ch: Char): Piece {
    val side = if (ch.isUpperCase()) Side.White else Side.Black
    val type = when (ch.lowercaseChar()) {
        'p' -> PieceType.Pawn; 'n' -> PieceType.Knight; 'b' -> PieceType.Bishop; 'r' -> PieceType.Rook; 'q' -> PieceType.Queen; 'k' -> PieceType.King
        else -> error("Unknown FEN piece: $ch")
    }
    return Piece(side, type)
}


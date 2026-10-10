package com.jotchess.recorder

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Legal-move validation and PGN state manager.
 * Matches vision-detected changed squares against the legal move tree.
 */
class ChessGameEngine {

    data class MatchResult(
        val move: Move?,
        val san: String?,
        val uci: String?,
        val ambiguous: Boolean = false,
    )

    private val board = Board()
    private val sanHistory = mutableListOf<String>()
    private var startFen: String = START_FEN

    val fen: String get() = board.fen
    val movesSan: List<String> get() = sanHistory.toList()
    val moveCount: Int get() = sanHistory.size
    val sideToMove: Side get() = board.sideToMove

    fun reset() {
        loadFen(START_FEN)
    }

    fun loadFen(fen: String) {
        startFen = fen
        board.loadFromFen(fen)
        sanHistory.clear()
    }

    fun undoLast(): Boolean {
        if (sanHistory.isEmpty()) return false
        board.undoMove()
        sanHistory.removeAt(sanHistory.lastIndex)
        return true
    }

    /**
     * Find the legal move whose board-delta squares exactly match [changedSquares].
     * Falls back to unique best overlap when exact match is unavailable.
     */
    fun matchMove(changedSquares: Set<String>): MatchResult {
        if (changedSquares.isEmpty()) {
            return MatchResult(null, null, null)
        }
        val normalized = changedSquares.map { it.lowercase() }.toSet()
        val legal = board.legalMoves()

        val exact = mutableListOf<Move>()
        var bestScore = 0
        val best = mutableListOf<Move>()

        for (move in legal) {
            val affected = affectedSquares(board, move)
            if (affected == normalized) {
                exact.add(move)
            }
            val score = (affected intersect normalized).size
            if (score > bestScore) {
                bestScore = score
                best.clear()
                best.add(move)
            } else if (score == bestScore && score > 0) {
                best.add(move)
            }
        }

        val candidates = when {
            exact.isNotEmpty() -> exact
            bestScore >= 2 && best.size == 1 -> best
            else -> emptyList()
        }

        if (candidates.isEmpty()) {
            return MatchResult(null, null, null)
        }

        val chosen = resolveAmbiguity(candidates) ?: return MatchResult(
            move = null,
            san = null,
            uci = null,
            ambiguous = candidates.size > 1,
        )

        return MatchResult(
            move = chosen,
            san = sanFor(chosen),
            uci = chosen.toString(),
        )
    }

    fun commit(move: Move): String {
        val san = sanFor(move)
        board.doMove(move)
        sanHistory.add(san)
        return san
    }

    fun pgn(
        white: String = "White",
        black: String = "Black",
        event: String = "JotChess Recording",
    ): String {
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
        val headers = buildString {
            appendLine("[Event \"$event\"]")
            appendLine("[Site \"JotChess\"]")
            appendLine("[Date \"$date\"]")
            appendLine("[Round \"1\"]")
            appendLine("[White \"$white\"]")
            appendLine("[Black \"$black\"]")
            appendLine("[Result \"*\"]")
            appendLine()
        }
        val body = buildString {
            sanHistory.forEachIndexed { index, san ->
                if (index % 2 == 0) {
                    append("${index / 2 + 1}. ")
                }
                append(san)
                append(' ')
            }
            append('*')
        }
        return headers + body.trim()
    }

    fun numberedMoveList(): List<String> {
        val lines = mutableListOf<String>()
        var i = 0
        while (i < sanHistory.size) {
            val number = i / 2 + 1
            val white = sanHistory[i]
            val black = sanHistory.getOrNull(i + 1)
            lines += if (black != null) "$number. $white $black" else "$number. $white"
            i += 2
        }
        return lines
    }

    private fun resolveAmbiguity(candidates: List<Move>): Move? {
        if (candidates.size == 1) return candidates.first()
        val promotions = candidates.filter { it.promotion != Piece.NONE }
        if (promotions.isNotEmpty()) {
            val queen = promotions.firstOrNull {
                it.promotion.pieceType == PieceType.QUEEN
            }
            if (queen != null) return queen
        }
        return null
    }

    private fun sanFor(move: Move): String {
        return try {
            val temp = MoveList(board.fen)
            temp.add(move)
            val arr = temp.toSanArray()
            arr.lastOrNull()?.trim()?.ifBlank { null } ?: move.toString()
        } catch (_: Exception) {
            move.toString()
        }
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun affectedSquares(board: Board, move: Move): Set<String> {
            val before = HashMap<Square, Piece>(64)
            for (sq in Square.values()) {
                if (sq == Square.NONE) continue
                before[sq] = board.getPiece(sq)
            }
            val clone = board.clone()
            clone.doMove(move)
            val changed = mutableSetOf<String>()
            for (sq in Square.values()) {
                if (sq == Square.NONE) continue
                if (before[sq] != clone.getPiece(sq)) {
                    changed += sq.name.lowercase()
                }
            }
            return changed
        }
    }

    init {
        reset()
    }
}

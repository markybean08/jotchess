package com.jotchess.recorder

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChessGameEngineTest {

    private lateinit var engine: ChessGameEngine

    @Before
    fun setUp() {
        engine = ChessGameEngine()
    }

    @Test
    fun matchMove_e2e4() {
        val result = engine.matchMove(setOf("e2", "e4"))
        assertNotNull(result.move)
        assertEquals("e2e4", result.uci)
        assertEquals("e4", result.san)
    }

    @Test
    fun matchMove_capture() {
        engine.commit(Move(Square.E2, Square.E4))
        engine.commit(Move(Square.D7, Square.D5))
        val result = engine.matchMove(setOf("e4", "d5"))
        assertNotNull(result.move)
        assertEquals("e4d5", result.uci)
        assertTrue(result.san!!.contains("xd5"))
    }

    @Test
    fun matchMove_castlingKingside() {
        engine.loadFen("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1")
        val result = engine.matchMove(setOf("e1", "f1", "g1", "h1"))
        assertNotNull(result.move)
        assertTrue(result.san == "O-O" || result.uci == "e1g1")
    }

    @Test
    fun matchMove_enPassant() {
        engine.commit(Move(Square.E2, Square.E4))
        engine.commit(Move(Square.A7, Square.A6))
        engine.commit(Move(Square.E4, Square.E5))
        engine.commit(Move(Square.D7, Square.D5))
        val result = engine.matchMove(setOf("e5", "d5", "d6"))
        assertNotNull(result.move)
        assertEquals("e5d6", result.uci)
    }

    @Test
    fun matchMove_promotionAssumesQueen() {
        engine.loadFen("8/P7/8/8/8/8/8/4K2k w - - 0 1")
        val result = engine.matchMove(setOf("a7", "a8"))
        assertNotNull(result.move)
        assertEquals(Piece.WHITE_QUEEN, result.move!!.promotion)
        assertTrue(result.san!!.contains("=Q") || result.uci!!.endsWith("q"))
    }

    @Test
    fun affectedSquares_castlingTouchesFourSquares() {
        val board = Board()
        board.loadFromFen("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1")
        val affected = ChessGameEngine.affectedSquares(board, Move(Square.E1, Square.G1))
        assertEquals(setOf("e1", "f1", "g1", "h1"), affected)
    }

    @Test
    fun matchMove_knockedPiecePatternMatchesNothing() {
        val result = engine.matchMove(setOf("a1", "c4", "h8"))
        assertNull(result.move)
    }

    @Test
    fun pgn_containsCommittedMoves() {
        engine.commit(Move(Square.E2, Square.E4))
        engine.commit(Move(Square.E7, Square.E5))
        val pgn = engine.pgn()
        assertTrue(pgn.contains("1. e4 e5"))
        assertTrue(pgn.contains("[Result \"*\"]"))
        assertTrue(pgn.trimEnd().endsWith("*"))
        assertEquals("*", engine.resultTag())
    }

    @Test
    fun resultTag_checkmateIsOneZero() {
        // Fool's mate: 1.f3 e5 2.g4 Qh4#
        engine.commit(Move(Square.F2, Square.F3))
        engine.commit(Move(Square.E7, Square.E5))
        engine.commit(Move(Square.G2, Square.G4))
        engine.commit(Move(Square.D8, Square.H4))
        assertEquals("0-1", engine.resultTag())
        val pgn = engine.pgn()
        assertTrue(pgn.contains("[Result \"0-1\"]"))
        assertTrue(pgn.trimEnd().endsWith("0-1"))
    }

    @Test
    fun undoLast_restoresPosition() {
        engine.commit(Move(Square.E2, Square.E4))
        assertTrue(engine.undoLast())
        assertEquals(0, engine.moveCount)
        val again = engine.matchMove(setOf("e2", "e4"))
        assertNotNull(again.move)
    }
}

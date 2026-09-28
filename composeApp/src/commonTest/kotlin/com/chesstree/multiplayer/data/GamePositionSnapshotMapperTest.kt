package com.chesstree.multiplayer.data

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.scenario.StandardGame
import com.chesstree.game.domain.session.CapturedPiece
import com.chesstree.multiplayer.contract.GamePositionSnapshotResponse
import com.chesstree.multiplayer.contract.SnapshotArmyResponse
import com.chesstree.multiplayer.contract.SnapshotCapturedPieceResponse
import com.chesstree.multiplayer.contract.SnapshotCastlingRightResponse
import com.chesstree.multiplayer.contract.SnapshotCoordinateResponse
import com.chesstree.multiplayer.contract.SnapshotEnPassantTargetResponse
import com.chesstree.multiplayer.contract.SnapshotParticipantResponse
import com.chesstree.multiplayer.contract.SnapshotParticipantStatusResponse
import com.chesstree.multiplayer.contract.SnapshotPieceResponse
import com.chesstree.multiplayer.contract.SnapshotTurnResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GamePositionSnapshotMapperTest {
    @Test
    fun snapshotRestoresGamePositionAndCapturedPieceTrophies() {
        val state = StandardGame.scenario.initialState
        val captures = listOf(
            CapturedPiece("red-pawn-1", PieceType.PAWN, ArmyColor.RED, ArmyColor.BLACK, ArmyColor.WHITE),
        )
        val snapshot = GamePositionSnapshotResponse(
            moveCount = 0,
            pieces = state.position.pieces.values.map {
                SnapshotPieceResponse(
                    it.id.value,
                    it.type.name,
                    it.army.name,
                    SnapshotCoordinateResponse(it.coordinate.vertex, it.coordinate.column, it.coordinate.row),
                    it.hasMoved,
                )
            },
            castlingRights = state.position.castlingRights.map {
                SnapshotCastlingRightResponse(it.army.name, it.side.name, it.rookId?.value)
            },
            enPassantTargets = state.position.enPassantTargets.values.map {
                SnapshotEnPassantTargetResponse(
                    it.pawnId.value,
                    SnapshotCoordinateResponse(it.captureCoordinate.vertex, it.captureCoordinate.column, it.captureCoordinate.row),
                    it.eligiblePlayers.map(PlayerId::name),
                )
            },
            participants = state.participants.values.map {
                SnapshotParticipantResponse(it.id.name, SnapshotParticipantStatusResponse("ACTIVE"))
            },
            armies = state.armies.values.map { SnapshotArmyResponse(it.army.name, it.controller.name) },
            turn = state.turn?.let { SnapshotTurnResponse(it.player.name, it.ply) },
            outcome = null,
            capturedPieces = captures.map {
                SnapshotCapturedPieceResponse(it.id, it.type.name, it.army.name, it.bodyArmy.name, it.capturedByArmy.name)
            },
        )

        val restored = assertNotNull(snapshot.toSession(StandardGame.scenario, emptyList()))

        assertEquals(state, restored.state)
        assertEquals(captures, restored.capturedPieces)
    }
}

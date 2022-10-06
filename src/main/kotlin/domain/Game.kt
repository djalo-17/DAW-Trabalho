package domain

import java.sql.Timestamp

enum class GamePhase { LAYOUT, SHOOTING, COMPLETED }

enum class Player {
    PLAYER1,
    PLAYER2;

    fun opponent() = if (this == PLAYER1) PLAYER2 else PLAYER1
}

/**
 * GameStates:
 *
 * - New Game
 * GameState(
 *  id, rules, p1fleet=FleetLayout(ships = empty), p2fleet=..., p1missed=empty, p2missed=empty, turn=player1,
 *  layout_phase_deadline=now+rules.layout_timeout, winner=null, phase=LAYOUT, turn_deadline=null
 * )
 *
 * - When on Shooting Phase
 * GameState(
 *  id, rules, p1fleet=FleetLayout(ships = [...]), p2fleet=..., p1missed=[...], p2missed=[...], turn=player2,
 *  layout_phase_deadline=now+rules.layout_timeout, winner=null, phase=SHOOTING, turn_deadline=...
 * )
 *
 * Lost By Layout timeout
 * GameState(
 *  id, rules, p1fleet=FleetLayout(ships = [...]), p2fleet=..., p1missed=[...], p2missed=[...], turn=player2,
 *  layout_phase_deadline=now+rules.layout_timeout, winner=null, phase=COMPLETED, turn_deadline=null
 * )
 *
 * Lost By Shots timeout
 * GameState(
 *  id, rules, p1fleet=FleetLayout(ships = [...]), p2fleet=..., p1missed=[...], p2missed=[...], turn=player2,
 *  layout_phase_deadline=now+rules.layout_timeout, winner=null, phase=COMPLETED, turn_deadline=null
 * )
 *
 * - Player Won
 * GameState(
 *  id, rules, p1fleet=FleetLayout(ships = [...]), p2fleet=..., p1missed=[...], p2missed=[...], turn=player2,
 *  layout_phase_deadline=now+rules.layout_timeout, winner=player1, phase=COMPLETED, turn_deadline=null
 * )
 * */

data class Game(
    val game_id: String,
    val rules: GameRules,

    val player1: String,
    val player2: String,

    val player1_fleet: FleetLayout,
    val player2_fleet: FleetLayout,

    val player1_missed_shots: Set<Shot> = setOf(),
    val player2_missed_shots: Set<Shot> = setOf(),

    val turn: Player = Player.PLAYER1,

    // Default value only used when game is created
    val layout_phase_deadline: Timestamp? = Timestamp(System.currentTimeMillis() + rules.layout_timeout_s * 1000)
) {

    val winner: Player? =
        if (player1_fleet.ships.all { it.isDestroyed })
            Player.PLAYER2
        else if (player2_fleet.ships.all { it.isDestroyed })
            Player.PLAYER1
        else null

    val game_phase =
        if (
            winner != null || turnDeadlineExpired() || layoutPhaseExpired()
        )
            GamePhase.COMPLETED
        else if (player1_fleet.ships.isEmpty() || player2_fleet.ships.isEmpty())
            GamePhase.LAYOUT
        else
            GamePhase.SHOOTING

    val turn_deadline: Timestamp? = calculateNewTurnDeadline()

    fun makeShots(me: Player, shots: Set<Shot>): Game {
        require(game_phase == GamePhase.SHOOTING)
        require(shots.size == rules.shots_per_round)

        val opponent = me.opponent()

        val opponentShips = if (me == Player.PLAYER1) player2_fleet.ships else player1_fleet.ships
        val opponentShipPartsHit = opponentShips.flatMap { ship -> ship.parts.filter { it.isHit } }

        val myMissedShots = (if (me == Player.PLAYER1) player1_missed_shots else player2_missed_shots).toMutableSet()

        require(shots.none { shot ->
            myMissedShots.contains(shot) ||
            opponentShipPartsHit.any { part -> part.coordinates.row == shot.coordinates.row && part.coordinates.col == shot.coordinates.col }
        })

        // Calculate opponent ships_configurations after [shots] have been fired
        val shotsHit = mutableSetOf<Shot>()
        val newOpponentShips = opponentShips.map { ship ->
            ship.copy(
                parts = ship.parts.map { part ->
                    val shot: Shot? = hitShipPart(part, shots)
                    if (shot == null)
                    { // Didn't hit
                        part
                    } else
                    { // Hit
                        shotsHit.add(shot)
                        part.copy(isHit = true)
                    }
                })
        }.toSet()

        // Calculate my missed shots after getting feedback from [shots]
        val shotsMissed = shots - shotsHit
        myMissedShots += shotsMissed

        val newPlayer1_fleet = if (me == Player.PLAYER1) player1_fleet else FleetLayout(newOpponentShips)
        val newPlayer2_fleet = if (me == Player.PLAYER2) player2_fleet else FleetLayout(newOpponentShips)
        val newPlayer1_missed_shots = if (me == Player.PLAYER1) myMissedShots.toSet() else player1_missed_shots
        val newPlayer2_missed_shots = if (me == Player.PLAYER2) myMissedShots.toSet() else player2_missed_shots

        return this.copy(
            player1_fleet = newPlayer1_fleet,
            player2_fleet = newPlayer2_fleet,
            player1_missed_shots = newPlayer1_missed_shots,
            player2_missed_shots = newPlayer2_missed_shots,
            turn = opponent
        )
    }

    private fun hitShipPart(part: ShipPart, shot: Set<Shot>): Shot? =
        shot.find { it.coordinates.row == part.coordinates.row && it.coordinates.col == part.coordinates.col }

    private fun turnDeadlineExpired(): Boolean =
        Timestamp(System.currentTimeMillis()) > turn_deadline

    private fun calculateNewTurnDeadline() =
        if (game_phase == GamePhase.SHOOTING) {
            Timestamp(System.currentTimeMillis() + rules.shots_timeout_s * 1000)
        } else null

    private fun layoutPhaseExpired(): Boolean =
        layout_phase_deadline != null
        && Timestamp(System.currentTimeMillis()) > layout_phase_deadline
        && (player1_fleet.ships.isEmpty() || player2_fleet.ships.isEmpty())
}

data class Shots_Response(
    val opponentShips: List<OpponentShip>,
    val myMissedShots: List<Shot>
)
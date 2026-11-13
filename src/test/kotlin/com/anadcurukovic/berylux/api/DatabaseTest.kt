package com.anadcurukovic.berylux.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/** The schema after every migration, against PostgreSQL. Each test rolls back. */
@SpringBootTest
@Import(TestDatabase::class)
@Transactional
class DatabaseTest(@Autowired private val db: JdbcClient) {
    private val thursday = LocalDate.of(2026, 11, 12)

    private fun user(onLeaderboard: Boolean = true, name: String = "Sam"): UUID {
        val id = UUID.randomUUID()
        db.sql("insert into users (id, email, password_hash) values (?, ?, 'hash')")
            .params(id, "$id@example.com")
            .update()
        db.sql(
            "insert into profiles (user_id, name, sigil_seed, pick_seed, time_zone, on_leaderboard) " +
                "values (?, ?, 1, 42, 'Europe/Sarajevo', ?)",
        ).params(id, name, onLeaderboard).update()
        return id
    }

    private fun ownChallenge(user: UUID): UUID {
        val id = UUID.randomUUID()
        db.sql("insert into custom_challenges (id, user_id, title, updated_at) values (?, ?, 'Own', now())")
            .params(id, user)
            .update()
        return id
    }

    private fun firstChallenge(difficulty: String): Long =
        db.sql("select min(id) from challenges where difficulty = ?")
            .param(difficulty)
            .query(Long::class.java)
            .single()

    /** One row of `cases`; the defaults make a case closed on Thursday the 12th. */
    private data class CaseRow(
        val user: UUID,
        val challenge: Long? = null,
        val custom: UUID? = null,
        val status: String = "CLOSED",
        val daily: Boolean = false,
        val closedOn: LocalDate? = LocalDate.of(2026, 11, 12),
    )

    private fun insert(row: CaseRow) {
        db.sql(
            "insert into cases (id, user_id, challenge_id, custom_challenge_id, status, is_daily, " +
                "accepted_at, accepted_on, closed_on, updated_at) values (?, ?, ?, ?, ?, ?, now(), ?, ?, now())",
        ).params(
            listOf(
                UUID.randomUUID(),
                row.user,
                row.challenge,
                row.custom,
                row.status,
                row.daily,
                row.closedOn ?: thursday,
                row.closedOn,
            ),
        ).update()
    }

    @Test
    fun `the built-in challenges are the app's 56, with their difficulties`() {
        // Counted in the app's challenges.json: 18 Easy, 18 Medium, 20 Hard; id 1 is "Stretch break".
        val counts = db.sql("select difficulty, count(*) from challenges group by difficulty")
            .query { rs, _ -> rs.getString(1) to rs.getInt(2) }
            .list()
            .toMap()
        assertEquals(mapOf("EASY" to 18, "MEDIUM" to 18, "HARD" to 20), counts)
        val first = db.sql("select title from challenges where id = 1").query(String::class.java).single()
        assertEquals("Stretch break", first)
    }

    @Test
    fun `a case names a built-in challenge or an own one, never both`() {
        val sam = user()
        val own = ownChallenge(sam)
        assertThrows<DataIntegrityViolationException> {
            insert(CaseRow(sam, challenge = firstChallenge("EASY"), custom = own))
        }
    }

    @Test
    fun `a case names at least one challenge`() {
        assertThrows<DataIntegrityViolationException> { insert(CaseRow(user())) }
    }

    @Test
    fun `a closed case has the day it was closed on`() {
        assertThrows<DataIntegrityViolationException> {
            insert(CaseRow(user(), challenge = firstChallenge("EASY"), closedOn = null))
        }
    }

    @Test
    fun `an open case has no closing day`() {
        assertThrows<DataIntegrityViolationException> {
            insert(CaseRow(user(), challenge = firstChallenge("EASY"), status = "OPEN", closedOn = thursday))
        }
    }

    @Test
    fun `deleting an account deletes everything that was theirs`() {
        val sam = user()
        val own = ownChallenge(sam)
        insert(CaseRow(sam, challenge = firstChallenge("EASY")))
        insert(CaseRow(sam, custom = own))
        db.sql(
            "insert into refresh_tokens (id, user_id, family_id, token_hash, expires_at) values (?, ?, ?, 'h', now())",
        )
            .params(UUID.randomUUID(), sam, UUID.randomUUID())
            .update()
        db.sql("insert into achievement_unlocks (user_id, key, unlocked_at) values (?, 'first_closed', now())")
            .param(sam)
            .update()
        db.sql("insert into sync_changes (user_id, entity, entity_id, op) values (?, 'case', 'x', 'UPSERT')")
            .param(sam)
            .update()

        db.sql("delete from users where id = ?").param(sam).update()

        val tables = listOf(
            "profiles",
            "custom_challenges",
            "cases",
            "refresh_tokens",
            "achievement_unlocks",
            "sync_changes",
        )
        for (table in tables) {
            val left = db.sql("select count(*) from $table where user_id = ?")
                .param(sam)
                .query(Int::class.java)
                .single()
            assertEquals(0, left, table)
        }
    }

    @Test
    fun `the weekly board counts the Lux opted-in players earned, by the week they closed`() {
        val sam = user(onLeaderboard = true, name = "Sam")
        val kit = user(onLeaderboard = false, name = "Kit")

        // The week of Monday 9 November: a Medium daily (25 x 1.5 = 37.5, rounded up to 38) on Thursday the
        // 12th and an Easy (10) on Sunday the 15th: 48. An own challenge pays nothing.
        insert(CaseRow(sam, challenge = firstChallenge("MEDIUM"), daily = true, closedOn = thursday))
        insert(CaseRow(sam, challenge = firstChallenge("EASY"), closedOn = LocalDate.of(2026, 11, 15)))
        insert(CaseRow(sam, custom = ownChallenge(sam), closedOn = LocalDate.of(2026, 11, 13)))
        // Monday the 16th starts the next week: a Hard, 50. Dropped and open cases count for nothing.
        insert(CaseRow(sam, challenge = firstChallenge("HARD"), closedOn = LocalDate.of(2026, 11, 16)))
        insert(CaseRow(sam, challenge = firstChallenge("HARD"), status = "DROPPED", closedOn = null))
        insert(CaseRow(sam, challenge = firstChallenge("HARD"), status = "OPEN", closedOn = null))
        // Kit hasn't opted in.
        insert(CaseRow(kit, challenge = firstChallenge("HARD"), closedOn = thursday))

        val board = db.sql("select name, week_start, lux from weekly_leaderboard order by week_start")
            .query { rs, _ -> Triple(rs.getString(1), rs.getObject(2, LocalDate::class.java), rs.getInt(3)) }
            .list()
        assertEquals(
            listOf(
                Triple("Sam", LocalDate.of(2026, 11, 9), 48),
                Triple("Sam", LocalDate.of(2026, 11, 16), 50),
            ),
            board,
        )
    }
}

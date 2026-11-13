package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import tools.jackson.databind.json.JsonMapper

/**
 * Loads the built-in challenges from `seed/challenges.json`, a copy of the app's own seed, with the
 * same ids. When the app's seed gets a new version, a new migration loads the changes.
 */
@Suppress("ClassName")
class V2__Load_challenges : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val file = requireNotNull(javaClass.getResourceAsStream(SEED)) { "$SEED is missing" }
        val seed = file.use { JsonMapper.builder().build().readTree(it) }
        val insert = "insert into challenges (id, category_id, title, description, difficulty) values (?, ?, ?, ?, ?)"
        context.connection.prepareStatement(insert).use { statement ->
            seed["challenges"].forEach { challenge ->
                // In the order of the insert's columns; JDBC counts them from 1.
                listOf(
                    challenge["id"].asLong(),
                    challenge["categoryId"].asLong(),
                    challenge["title"].asString(),
                    challenge["description"].asString(),
                    challenge["difficulty"].asString(),
                ).forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private companion object {
        const val SEED = "/seed/challenges.json"
    }
}

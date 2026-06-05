package entkt.flyway

/**
 * Configuration for the disposable Docker-backed shadow database used
 * by the Flyway migration workflow. A fresh container is started for
 * each workflow invocation and destroyed when it completes, so there
 * is no persistent database to accidentally wipe.
 */
data class ShadowDockerConfig(
    /**
     * Docker image to use for the shadow Postgres container. Defaults to a
     * pgvector-capable image: it is a drop-in superset of the official
     * Postgres image, so plain schemas are unaffected, while schemas with a
     * native extension column (e.g. pgvector's `vector(n)`, which emits
     * `CREATE EXTENSION "vector"`) can be applied in the shadow DB. A plain
     * `postgres:*` image lacks the extension package and would fail the
     * shadow migration. Override for a different Postgres major version.
     */
    val image: String = "pgvector/pgvector:pg16",
    /** Database name to create inside the container. */
    val databaseName: String = "entkt_shadow",
    /** Postgres user. */
    val user: String = "postgres",
    /** Postgres password. */
    val password: String = "postgres",
)

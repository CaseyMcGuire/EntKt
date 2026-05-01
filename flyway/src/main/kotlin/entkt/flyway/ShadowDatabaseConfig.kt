package entkt.flyway

/**
 * Configuration for the disposable Docker-backed shadow database used
 * by the Flyway migration workflow. A fresh container is started for
 * each workflow invocation and destroyed when it completes, so there
 * is no persistent database to accidentally wipe.
 */
data class ShadowDockerConfig(
    /** Docker image to use for the shadow Postgres container. */
    val image: String = "postgres:16-alpine",
    /** Database name to create inside the container. */
    val databaseName: String = "entkt_shadow",
    /** Postgres user. */
    val user: String = "postgres",
    /** Postgres password. */
    val password: String = "postgres",
)

package entkt.flyway

import kotlin.test.Test
import kotlin.test.assertEquals

class ShadowDockerConfigTest {

    @Test
    fun `defaults are sensible`() {
        val config = ShadowDockerConfig()
        assertEquals("postgres:16-alpine", config.image)
        assertEquals("entkt_shadow", config.databaseName)
        assertEquals("postgres", config.user)
        assertEquals("postgres", config.password)
    }

    @Test
    fun `custom values are preserved`() {
        val config = ShadowDockerConfig(
            image = "postgres:15",
            databaseName = "my_shadow",
            user = "admin",
            password = "secret",
        )
        assertEquals("postgres:15", config.image)
        assertEquals("my_shadow", config.databaseName)
        assertEquals("admin", config.user)
        assertEquals("secret", config.password)
    }
}

package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.EntClientConfig
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer

/**
 * Build an [EntClient] with the [Viewer.PrivacyBypass] viewer, which bypasses
 * fail-closed privacy. For tests that exercise non-privacy behavior (hooks,
 * locking, transactions, M2M traversal) on entities without policies — these
 * ran effectively without privacy before it became fail-closed (an unpolicied
 * entity was implicitly allow-all), and System preserves that.
 */
internal fun sysClient(driver: DatabaseDriver, config: EntClientConfig.() -> Unit = {}): EntClient =
    EntClient(driver) {
        privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        config()
    }

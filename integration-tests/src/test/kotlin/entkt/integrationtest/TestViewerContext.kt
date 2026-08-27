package entkt.integrationtest

import entkt.runtime.privacy.ViewerContext

/** A fresh explicit bypass context for setup or identity-sensitive test operations. */
internal fun testBypassContext(reason: String = "integration test"): ViewerContext =
    ViewerContext.privacyBypass_DANGEROUS(reason)

/** Explicit operation context for integration tests that are not exercising privacy. */
internal val testViewerContext: ViewerContext =
    testBypassContext()

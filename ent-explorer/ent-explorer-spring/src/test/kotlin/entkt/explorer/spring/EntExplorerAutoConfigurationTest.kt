package entkt.explorer.spring

import entkt.explorer.EntExplorer
import entkt.explorer.EntExplorerEntity
import entkt.runtime.privacy.ViewerContext
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The auto-configuration's safety contract: nothing happens from the
 * classpath alone — routes exist only when the application declares an
 * [EntExplorer] bean (which is where authorize/viewerContext live).
 * Behavioral coverage over HTTP runs in example-spring, which consumes this
 * module for its `/_ent` endpoint.
 */
class EntExplorerAutoConfigurationTest {

    private class FakeViewer

    private class FakeClient

    @Configuration(proxyBeanMethods = false)
    private class ExplorerDeclaringApp {
        @Bean
        fun entExplorer(): EntExplorer<FakeViewer, FakeClient> =
            EntExplorer(FakeClient(), NO_ENTITIES) {
                authorize { true }
                viewerContext { ViewerContext.privacyBypass_DANGEROUS("auto-configuration test") }
            }
    }

    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EntExplorerAutoConfiguration::class.java))

    @Test
    fun `no explorer bean means no routes and no resolver — the classpath alone changes nothing`() {
        runner.run { context ->
            assertFalse(context.containsBean("entExplorerRoutes"))
            assertFalse(context.containsBean("entExplorerPrincipalResolver"))
        }
    }

    @Test
    fun `a declared explorer bean gets routes and a default principal resolver`() {
        runner.withUserConfiguration(ExplorerDeclaringApp::class.java).run { context ->
            assertTrue(context.containsBean("entExplorerRoutes"))
            assertTrue(context.getBean(RouterFunction::class.java) != null)
            assertTrue(context.containsBean("entExplorerPrincipalResolver"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    private class WithResolverApp {
        @Bean
        fun entExplorer(): EntExplorer<FakeViewer, FakeClient> =
            EntExplorer(FakeClient(), NO_ENTITIES) {
                authorize { true }
                viewerContext { ViewerContext.privacyBypass_DANGEROUS("auto-configuration test") }
            }

        @Bean
        fun myResolver(): EntExplorerPrincipalResolver = CUSTOM_RESOLVER
    }

    @Test
    fun `an application-supplied principal resolver wins over the default`() {
        runner.withUserConfiguration(WithResolverApp::class.java).run { context ->
            assertTrue(context.getBean(EntExplorerPrincipalResolver::class.java) === CUSTOM_RESOLVER)
        }
    }

    companion object {
        private val NO_ENTITIES: List<EntExplorerEntity<FakeViewer, FakeClient>> = emptyList()
        private val CUSTOM_RESOLVER = EntExplorerPrincipalResolver { "custom" }
    }
}

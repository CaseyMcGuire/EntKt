package entkt.viewer.spring

import entkt.runtime.privacy.PrivacyContext
import entkt.viewer.EntViewer
import entkt.viewer.EntViewerEntity
import entkt.viewer.EntViewerRegistry
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
 * [EntViewer] bean (which is where authorize/privacyContext live).
 * Behavioral coverage over HTTP runs in example-spring, which consumes this
 * module for its `/_ent` endpoint.
 */
class EntViewerAutoConfigurationTest {

    private class FakeClient

    private object EmptyRegistry : EntViewerRegistry<FakeClient> {
        override val entities: List<EntViewerEntity<FakeClient>> = emptyList()
        override fun <T> withPrivacyContext(
            client: FakeClient,
            context: PrivacyContext,
            block: (FakeClient) -> T,
        ): T = block(client)
    }

    @Configuration(proxyBeanMethods = false)
    private class ViewerDeclaringApp {
        @Bean
        fun entViewer(): EntViewer<FakeClient> =
            EntViewer(FakeClient(), EmptyRegistry) { authorize { true } }
    }

    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EntViewerAutoConfiguration::class.java))

    @Test
    fun `no viewer bean means no routes and no resolver — the classpath alone changes nothing`() {
        runner.run { context ->
            assertFalse(context.containsBean("entViewerRoutes"))
            assertFalse(context.containsBean("entViewerPrincipalResolver"))
        }
    }

    @Test
    fun `a declared viewer bean gets routes and a default principal resolver`() {
        runner.withUserConfiguration(ViewerDeclaringApp::class.java).run { context ->
            assertTrue(context.containsBean("entViewerRoutes"))
            assertTrue(context.getBean(RouterFunction::class.java) != null)
            assertTrue(context.containsBean("entViewerPrincipalResolver"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    private class WithResolverApp {
        @Bean
        fun entViewer(): EntViewer<FakeClient> =
            EntViewer(FakeClient(), EmptyRegistry) { authorize { true } }

        @Bean
        fun myResolver(): EntViewerPrincipalResolver = CUSTOM_RESOLVER
    }

    @Test
    fun `an application-supplied principal resolver wins over the default`() {
        runner.withUserConfiguration(WithResolverApp::class.java).run { context ->
            assertTrue(context.getBean(EntViewerPrincipalResolver::class.java) === CUSTOM_RESOLVER)
        }
    }

    companion object {
        private val CUSTOM_RESOLVER = EntViewerPrincipalResolver { "custom" }
    }
}

package entkt.viewer.spring

import entkt.viewer.EntViewer
import entkt.viewer.EntViewerRequest
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse

/**
 * Resolves the application identity handed to the viewer's `authorize` and
 * `privacyContext` callbacks as [EntViewerRequest.principal]. The default
 * uses the servlet's `userPrincipal` (populated by Spring Security);
 * applications with custom auth declare their own bean:
 *
 * ```kotlin
 * @Bean
 * fun entViewerPrincipalResolver(auth: AuthContext) =
 *     EntViewerPrincipalResolver { auth.userId }
 * ```
 */
fun interface EntViewerPrincipalResolver {
    fun resolve(request: HttpServletRequest): Any?
}

/**
 * Auto-configuration that mounts an application-declared [EntViewer] bean at
 * its configured path. Deliberately does **not** create a viewer: the
 * security-critical configuration (`authorize`, `privacyContext`,
 * exclusions, redaction) stays application-supplied, exactly as the
 * ent-viewer security model requires — having this module on the classpath
 * changes nothing until the application defines the bean.
 *
 * ```kotlin
 * @Bean
 * fun entViewer(client: EntClient): EntViewer<EntClient> =
 *     EntViewer(client, GeneratedEntViewerRegistry) {
 *         authorize { it.principal != null }
 *         privacyContext { ... }
 *     }
 * ```
 *
 * The route is registered for every HTTP method at the mount path and all
 * subpaths; the viewer itself answers non-GET with 405, unauthorized with
 * 403, and so on. The raw (still percent-encoded) `requestURI` is passed
 * through, which is what the viewer's path-segment decoding expects.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(EntViewer::class)
class EntViewerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun entViewerPrincipalResolver(): EntViewerPrincipalResolver =
        EntViewerPrincipalResolver { request -> request.userPrincipal }

    @Bean
    fun entViewerRoutes(
        viewer: EntViewer<*>,
        principalResolver: EntViewerPrincipalResolver,
    ): RouterFunction<ServerResponse> {
        val path = viewer.path.trimEnd('/')
        val predicate = RequestPredicates.path(path).or(RequestPredicates.path("$path/**"))
        return RouterFunctions.route(predicate) { serverRequest ->
            val http = serverRequest.servletRequest()
            val response = viewer.handle(
                EntViewerRequest(
                    path = http.requestURI,
                    method = http.method,
                    query = http.parameterMap.mapValues { it.value.toList() },
                    principal = principalResolver.resolve(http),
                ),
            )
            ServerResponse.status(response.status)
                .header("Content-Type", response.contentType)
                .body(response.body)
        }
    }
}

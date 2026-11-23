package entkt.explorer.spring

import entkt.explorer.EntExplorer
import entkt.explorer.EntExplorerRequest
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse

/**
 * Resolves the application identity handed to the explorer's `authorize` and
 * `viewerContext` callbacks as [EntExplorerRequest.principal]. The default
 * uses the servlet's `userPrincipal` (populated by Spring Security);
 * applications with custom auth declare their own bean:
 *
 * ```kotlin
 * @Bean
 * fun entExplorerPrincipalResolver(auth: AuthContext) =
 *     EntExplorerPrincipalResolver { auth.userId }
 * ```
 */
fun interface EntExplorerPrincipalResolver {
    fun resolve(request: HttpServletRequest): Any?
}

/**
 * Auto-configuration that mounts an application-declared [EntExplorer] bean at
 * its configured path. Deliberately does **not** create an explorer: the
 * security-critical configuration (`authorize`, `viewerContext`,
 * exclusions, redaction) stays application-supplied, exactly as the
 * ent-explorer security model requires — having this module on the classpath
 * changes nothing until the application defines the bean.
 *
 * ```kotlin
 * @Bean
 * fun entExplorer(client: EntClient): EntExplorer<AppViewer, EntClient> =
 *     EntExplorer(client, GeneratedEntExplorerRegistry) {
 *         authorize { it.principal != null }
 *         viewerContext { ... }
 *     }
 * ```
 *
 * The route is registered for every HTTP method at the mount path and all
 * subpaths; the explorer itself answers non-GET with 405, unauthorized with
 * 403, and so on. The raw (still percent-encoded) `requestURI` is passed
 * through, which is what the explorer's path-segment decoding expects.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(EntExplorer::class)
class EntExplorerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun entExplorerPrincipalResolver(): EntExplorerPrincipalResolver =
        EntExplorerPrincipalResolver { request -> request.userPrincipal }

    @Bean
    fun entExplorerRoutes(
        explorer: EntExplorer<*, *>,
        principalResolver: EntExplorerPrincipalResolver,
    ): RouterFunction<ServerResponse> {
        val path = explorer.path.trimEnd('/')
        val predicate = RequestPredicates.path(path).or(RequestPredicates.path("$path/**"))
        return RouterFunctions.route(predicate) { serverRequest ->
            val http = serverRequest.servletRequest()
            val response = explorer.handle(
                EntExplorerRequest(
                    path = http.requestURI,
                    method = http.method,
                    query = http.parameterMap.mapValues { it.value.toList() },
                    principal = principalResolver.resolve(http),
                ),
            )
            if (response.unmapped) {
                // Unauthorized cloak: surface Boot's own error handling so
                // the response is bit-identical to any unmapped route —
                // probing /_ent reveals nothing.
                throw ResponseStatusException(HttpStatus.NOT_FOUND)
            }
            ServerResponse.status(response.status)
                .header("Content-Type", response.contentType)
                .body(response.body)
        }
    }
}

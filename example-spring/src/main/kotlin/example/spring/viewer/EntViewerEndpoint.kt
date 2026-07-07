package example.spring.viewer

import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.viewer.EntViewer
import entkt.viewer.EntViewerRequest
import example.ent.EntClient
import example.ent.GeneratedEntViewerRegistry
import example.spring.auth.AuthContext
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Mounts the entkt viewer at `/_ent` — the RFC's "thin wrapper around the
 * same core" pattern: adapt the servlet request into the framework-neutral
 * [EntViewerRequest], hand it to [EntViewer.handle], write the response out.
 *
 * Access rule for this example: any authenticated user (an `X-User-Id`
 * header, via [AuthContext]). A real application would gate on an admin
 * check. Rows are whatever that user's own privacy context can read — the
 * viewer adds no privileges.
 */
@Configuration
class EntViewerConfig {

    @Bean
    fun entViewer(client: EntClient): EntViewer<EntClient> =
        EntViewer(client, GeneratedEntViewerRegistry) {
            path = "/_ent"
            authorize { request -> request.principal != null }
            privacyContext { request ->
                val userId = request.principal as? UUID
                PrivacyContext(if (userId != null) Viewer.User(userId) else Viewer.Anonymous)
            }
        }
}

@RestController
class EntViewerController(
    private val viewer: EntViewer<EntClient>,
    private val auth: AuthContext,
) {

    @RequestMapping("/_ent", "/_ent/**")
    fun handle(request: HttpServletRequest): ResponseEntity<String> {
        val response = viewer.handle(
            EntViewerRequest(
                path = request.requestURI,
                method = request.method,
                query = request.parameterMap.mapValues { it.value.toList() },
                principal = auth.userId,
            ),
        )
        return ResponseEntity.status(response.status)
            .header("Content-Type", response.contentType)
            .body(response.body)
    }
}

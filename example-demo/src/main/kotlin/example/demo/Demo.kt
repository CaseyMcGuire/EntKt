package example.demo

import entkt.query.isNotNull
import entkt.runtime.StubDriver
import example.ent.EntClient
import example.ent.Post
import example.ent.Tag
import example.ent.User
import java.time.Instant
import java.util.UUID

/**
 * Demonstrates the shape of the entkt-generated API against the example
 * schemas defined in `:example`.
 *
 * NOTE: this demo does NOT call `.save()` on any builder because there
 * is no database/runtime layer yet — generated `save()` methods bail
 * out via `TODO("ID generation")`. The demo just *constructs* the
 * create/update/query builders to illustrate the fluent API surface.
 *
 * The `EntClient` is the dependency-injection seam: production code
 * passes a real `Driver`, this demo passes the `StubDriver`. There are
 * no static entry points to intercept — every I/O operation goes
 * through `client.users` / `client.posts` / `client.tags`, so a service
 * that needs to be tested just takes an `EntClient` in its constructor.
 *
 * Run with: ./gradlew :example-demo:run
 */
fun main() {
    fun banner(title: String) {
        println("=".repeat(60))
        println(" $title")
        println("=".repeat(60))
    }

    // The DI seam: hand the client a driver. In tests, swap StubDriver
    // for an in-memory or fake driver.
    val client = EntClient(StubDriver)

    banner("entkt API demo")
    println("This demo builds generated create/update/query builders to show")
    println("the API shape. save() is never called — there is no runtime yet.")
    println()

    // ---------- Create builder ----------
    banner("client.users.create { ... }")
    val newUser = client.users.create {
        name = "Alice"
        email = "alice@example.com"
        age = 30
        active = true
        createdAt = Instant.now()
        updatedAt = Instant.now()
    }
    println("Built: $newUser")
    println("(Calling .save() here would hit TODO(\"ID generation\"))")
    println()

    // ---------- Update builder via client.users.update(entity) { ... } ----------
    banner("client.users.update(existingUser) { ... }")
    // Construct a User instance directly to simulate one loaded from a DB.
    val existingUser = User(
        id = UUID.randomUUID(),
        name = "Alice",
        email = "alice@example.com",
        age = 30,
        active = true,
        createdAt = Instant.now().minusSeconds(3600),
        updatedAt = Instant.now().minusSeconds(3600),
    )
    val updatedUser = client.users.update(existingUser) {
        age = 31
        updatedAt = Instant.now()
    }
    println("Started from: $existingUser")
    println("Update builder: $updatedUser")
    println("(name / email omitted — they fall back to the entity's value)")
    println()

    // ---------- Query builder with typed column refs ----------
    banner("client.users.query { ... } with typed column refs")
    val activeUsers = client.users.query {
        where(User.active eq true)
        where(User.age gte 18)
        where(User.email hasSuffix "@example.com")
        orderBy(User.createdAt.desc())
        limit(20)
        offset(0)
    }
    println("Query: $activeUsers")
    println("predicates=${activeUsers.predicates}")
    println()

    // ---------- Compound predicates via and/or ----------
    banner("compound predicates with and / or")
    val specialUsers = client.users.query {
        where(
            (User.active eq true) and
                ((User.age gte 65) or (User.email hasSuffix "@admin.example.com")),
        )
    }
    println("predicates=${specialUsers.predicates}")
    println()

    // ---------- isNotNull on a nullable column ----------
    banner("isNotNull on nullable column")
    // User.age is nullable (optional), so isNotNull lights up.
    // If you try `User.name.isNotNull()`, it won't compile — name is non-null.
    val usersWithAge = client.users.query {
        where(User.age.isNotNull())
    }
    println("predicates=${usersWithAge.predicates}")
    println()

    // ---------- Post with edge ----------
    banner("client.posts.create { ... } with edge convenience property")
    val post = client.posts.create {
        title = "Hello entkt"
        body = "A minimal port of Ent for Kotlin"
        published = true
        author = existingUser // convenience property — writes existingUser.id
        createdAt = Instant.now()
        updatedAt = Instant.now()
    }
    println("Built: $post")
    println("Note: `author = user` is a convenience for `authorId = user.id`")
    println()

    // ---------- Edge-aware query ----------
    banner("client.posts.query { ... } with edge FK predicate")
    val postsByAlice = client.posts.query {
        where(Post.authorId eq existingUser.id)
        where(Post.published eq true)
        where(Post.title contains "entkt")
        orderBy(Post.createdAt.desc())
    }
    println("predicates=${postsByAlice.predicates}")
    println()

    // ---------- Edge predicates: forward (User → Posts) ----------
    banner("client.users.query { where(User.posts.has { ... }) }")
    // "users with at least one published post" — the runtime later
    // lowers this to an EXISTS subquery joining User → Post on the
    // Post.author_id FK.
    val usersWithPublishedPosts = client.users.query {
        where(User.posts.has { where(Post.published eq true) })
    }
    println("predicates=${usersWithPublishedPosts.predicates}")
    println()

    // ---------- Edge predicates: backward (Post → Author) ----------
    banner("client.posts.query { where(Post.author.has { ... }) }")
    // "posts whose author is active" — the predicate hops over the
    // author edge and applies a column predicate on the User side.
    val postsByActiveAuthors = client.posts.query {
        where(Post.author.has { where(User.active eq true) })
    }
    println("predicates=${postsByActiveAuthors.predicates}")
    println()

    // ---------- Nested edge predicates ----------
    banner("nested: posts whose author has any other published post")
    val postsByAuthorsWithOtherPublishedPosts = client.posts.query {
        where(
            Post.author.has {
                where(User.posts.has { where(Post.published eq true) })
            },
        )
    }
    println("predicates=${postsByAuthorsWithOtherPublishedPosts.predicates}")
    println()

    // ---------- Edge exists ----------
    banner("client.users.query { where(User.posts.exists()) }")
    // The trivial form: "users that have at least one post."
    val usersWithAnyPost = client.users.query { where(User.posts.exists()) }
    println("predicates=${usersWithAnyPost.predicates}")
    println()

    // ---------- Traversal: client.users.query{...}.queryPosts() ----------
    banner("client.users.query { ... }.queryPosts() — traversal")
    // Filter users first, then traverse to their posts. The result is
    // a PostQuery whose where carries a HasEdgeWith naming the inverse
    // edge ("author") so the runtime can resolve it as
    //   posts WHERE author_id IN (SELECT id FROM users WHERE active = true)
    val postsOfActiveUsers = client.users
        .query { where(User.active eq true) }
        .queryPosts()
    println("predicates=${postsOfActiveUsers.predicates}")
    println()

    banner("traversal back: client.posts.query { ... }.queryAuthor()")
    val authorsOfPublishedPosts = client.posts
        .query { where(Post.published eq true) }
        .queryAuthor()
    println("predicates=${authorsOfPublishedPosts.predicates}")
    println()

    // ---------- Enum field ----------
    banner("client.tags.create { ... } with enum field")
    val tag = client.tags.create {
        name = "kotlin"
        category = "LANGUAGE"
    }
    println("Built: $tag")
    println()

    banner("done")
    println("See example-demo/build/generated/entkt/example/ent/ for the generated sources.")
}
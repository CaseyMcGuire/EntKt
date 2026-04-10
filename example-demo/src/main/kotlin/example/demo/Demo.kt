package example.demo

import entkt.query.isNotNull
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
 * Run with: ./gradlew :example-demo:run
 */
fun main() {
    fun banner(title: String) {
        println("=".repeat(60))
        println(" $title")
        println("=".repeat(60))
    }

    banner("entkt API demo")
    println("This demo builds generated create/update/query builders to show")
    println("the API shape. save() is never called — there is no runtime yet.")
    println()

    // ---------- Create builder ----------
    banner("User.create { ... }")
    val newUser = User.create {
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

    // ---------- Update builder via entity.update { ... } ----------
    banner("existingUser.update { ... }")
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
    val updatedUser = existingUser.update {
        age = 31
        updatedAt = Instant.now()
    }
    println("Started from: $existingUser")
    println("Update builder: $updatedUser")
    println("(name / email omitted — they fall back to the entity's value)")
    println()

    // ---------- Query builder with typed column refs ----------
    banner("User.query { ... } with typed column refs")
    val activeUsers = User.query {
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
    val specialUsers = User.query {
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
    val usersWithAge = User.query {
        where(User.age.isNotNull())
    }
    println("predicates=${usersWithAge.predicates}")
    println()

    // ---------- Post with edge ----------
    banner("Post.create { ... } with edge convenience property")
    val post = Post.create {
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
    banner("Post.query { ... } with edge FK predicate")
    val postsByAlice = Post.query {
        where(Post.authorId eq existingUser.id)
        where(Post.published eq true)
        where(Post.title contains "entkt")
        orderBy(Post.createdAt.desc())
    }
    println("predicates=${postsByAlice.predicates}")
    println()

    // ---------- Enum field ----------
    banner("Tag.create { ... } with enum field")
    val tag = Tag.create {
        name = "kotlin"
        category = "LANGUAGE"
    }
    println("Built: $tag")
    println()

    banner("done")
    println("See example-demo/build/generated/entkt/example/ent/ for the generated sources.")
}
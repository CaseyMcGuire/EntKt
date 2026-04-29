# entkt Spring Boot Example

A REST API built with Spring Boot and entkt, demonstrating how to wire
the Postgres driver, Flyway-managed SQL migrations, lifecycle hooks, CRUD
endpoints, and friendship management (a first-class M2M junction entity).

## Prerequisites

- JDK 17+
- A running PostgreSQL instance

## Setup

Create the database:

```bash
createdb entkt_example
```

Or adjust the connection in `src/main/resources/application.yml`.

## Migrations

The example uses one migration path:

- entkt generates versioned SQL migration files
- Flyway applies them on startup
- the application does not execute schema DDL on startup; generated repos
  only register metadata with the driver

**Generate a migration** after changing your schemas. The `entkt` Gradle
plugin provides a `generateMigrationFile` task automatically:

```bash
./gradlew generateMigrationFile
# or with a description:
./gradlew generateMigrationFile -Pdescription="add_subtitle"
```

This diffs your schemas against the latest committed snapshot in
`db/migrations/` and writes the next SQL file there. No live database
connection is required. Commit both the migration file and the updated
snapshot.

**Run the app** (Flyway applies pending migrations on startup):

```bash
./gradlew :example-spring:bootRun
```

If the planner detects destructive changes (dropped columns, type
changes, etc.), it fails and tells you what manual DDL to write. See
the [migrations docs](../docs/migrations.md) for the full workflow.

## Endpoints

### Users

```bash
# List users (optionally filter by active, include posts)
curl localhost:8080/users
curl localhost:8080/users?active=true
curl localhost:8080/users?includePosts=true

# Get a user
curl localhost:8080/users/{id}

# Get a user's posts
curl localhost:8080/users/{id}/posts

# Create a user
curl -X POST localhost:8080/users \
  -H 'Content-Type: application/json' \
  -d '{"name": "Alice", "email": "alice@example.com", "age": 30}'

# Update a user
curl -X PUT localhost:8080/users/{id} \
  -H 'Content-Type: application/json' \
  -d '{"name": "Alice Smith"}'

# Delete a user
curl -X DELETE localhost:8080/users/{id}
```

### Posts

Updating and deleting posts requires an `X-User-Id` header matching the
post's author. This is enforced by `beforeUpdate` and `beforeDelete`
hooks in `EntktConfig.kt`.

```bash
# List posts (optionally filter by published)
curl localhost:8080/posts
curl localhost:8080/posts?published=true

# Get a post
curl localhost:8080/posts/{id}

# Create a post
curl -X POST localhost:8080/posts \
  -H 'Content-Type: application/json' \
  -d '{"title": "Hello", "body": "First post!", "authorId": "{user-id}"}'

# Update a post (X-User-Id must match the author)
curl -X PUT localhost:8080/posts/{id} \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: {user-id}' \
  -d '{"published": true}'

# Delete a post (X-User-Id must match the author)
curl -X DELETE localhost:8080/posts/{id} \
  -H 'X-User-Id: {user-id}'
```

### Friendships

Friendships are a first-class junction entity between two users, with a
status (`PENDING` → `ACCEPTED`) managed via lifecycle hooks.

```bash
# Send a friend request
curl -X POST localhost:8080/users/{id}/friends \
  -H 'Content-Type: application/json' \
  -d '{"recipientId": "{other-user-id}"}'

# Accept a friend request
curl -X POST localhost:8080/friendships/{id}/accept

# List a user's accepted friends
curl localhost:8080/users/{id}/friends

# List a user's pending friend requests
curl localhost:8080/users/{id}/friend-requests
```

Friendship hooks (`FriendshipHooks.kt`) enforce:
- Requester and recipient must be different users
- Duplicate friend requests are rejected
- Only `PENDING → ACCEPTED` status transitions are allowed

## How It Works

### Configuration (`EntktConfig.kt`)

The `EntClient` is configured as a Spring bean:

1. **Schema application** is handled by Flyway from `db/migrations/`.
   entkt generates those SQL files; the application does not diff or
   apply schema changes directly.
2. **PostgresDriver** is wired with Spring's auto-configured `DataSource`.
3. **Lifecycle hooks** set `createdAt`/`updatedAt` timestamps automatically.
4. **Ownership hooks** on posts use a request-scoped `AuthContext`
   (populated from `X-User-Id` by `AuthFilter`) to prevent users from
   updating or deleting posts they don't own.
5. **Friendship hooks** (`FriendshipHooksConfig`) validate participants,
   prevent duplicate requests, and enforce status transitions.

### Controllers

Each controller injects the `EntClient` and uses the generated repos:

- `client.users.create { ... }.save()` -- type-safe builders
- `client.users.query { where(...) }.all()` -- type-safe queries
- `client.users.byId(id)` -- primary key lookup
- `client.users.update(entity) { ... }.saveOrThrow()` -- partial updates
- `client.users.deleteById(id)` -- delete by ID

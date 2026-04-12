# entkt Spring Boot Example

A simple REST API built with Spring Boot and entkt, demonstrating how to
wire the Postgres driver, migrations (dev + prod), lifecycle hooks, and
CRUD endpoints.

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

entkt supports two migration modes, selected by Spring profile.

### Dev mode (`dev` profile)

Auto-applies schema changes on startup by introspecting the live
database and diffing against the desired schemas. No migration files
needed — just change your schema and restart.

```bash
./gradlew :example-spring:bootRun --args='--spring.profiles.active=dev'
```

### Prod mode (default)

Applies versioned SQL migration files from `db/migrations/`. Files are
generated at build time and committed to version control.

**Generate a migration** after changing your schemas. The `entkt` Gradle
plugin provides a `planMigration` task automatically:

```bash
./gradlew planMigration
# or with a description:
./gradlew planMigration -Pdescription="add_subtitle"
```

This diffs your schemas against `db/schema_snapshot.json` and writes a
new SQL file to `db/migrations/`. No live database connection is
required. Commit both the migration file and the updated snapshot.

**Run the app** (applies pending migrations on startup):

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

## How It Works

### Configuration (`EntktConfig.kt`)

The `EntClient` is configured as a Spring bean:

1. **Migrations** are profile-driven: `dev` auto-applies via DB
   introspection, prod applies versioned SQL files from `db/migrations/`.
2. **PostgresDriver** is wired with Spring's auto-configured `DataSource`.
3. **Lifecycle hooks** set `createdAt`/`updatedAt` timestamps automatically.
4. **Ownership hooks** on posts use a request-scoped `AuthContext`
   (populated from `X-User-Id` by `AuthFilter`) to prevent users from
   updating or deleting posts they don't own.

### Controllers

Each controller injects the `EntClient` and uses the generated repos:

- `client.users.create { ... }.save()` -- type-safe builders
- `client.users.query { where(...) }.all()` -- type-safe queries
- `client.users.byId(id)` -- primary key lookup
- `client.users.update(entity) { ... }.saveOrThrow()` -- partial updates
- `client.users.deleteById(id)` -- delete by ID

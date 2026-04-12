# entkt Spring Boot Example

A simple REST API built with Spring Boot and entkt, demonstrating how to
wire the Postgres driver, dev-mode migrations, lifecycle hooks, and
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

## Running

```bash
./gradlew :example-spring:bootRun
```

On startup the app runs dev-mode migrations to create/update tables
automatically, then starts the Spring server on port 8080.

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

1. **Dev-mode migrations** run on startup via `PostgresMigrator.create()` --
   the database is introspected and any missing tables, columns, or indexes
   are created automatically.
2. **PostgresDriver** is wired with Spring's auto-configured `DataSource`.
3. **Lifecycle hooks** set `createdAt`/`updatedAt` timestamps automatically.
4. **Ownership hooks** on posts use a request-scoped `RequestContext`
   (populated from `X-User-Id` by `AuthFilter`) to prevent users from
   updating or deleting posts they don't own.

### Controllers

Each controller injects the `EntClient` and uses the generated repos:

- `client.users.create { ... }.save()` -- type-safe builders
- `client.users.query { where(...) }.all()` -- type-safe queries
- `client.users.byId(id)` -- primary key lookup
- `client.users.update(entity) { ... }.saveOrThrow()` -- partial updates
- `client.users.deleteById(id)` -- delete by ID

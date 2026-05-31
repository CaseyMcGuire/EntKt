package entkt.postgres

import entkt.codegen.SchemaInput
import entkt.codegen.buildEntitySchemas
import entkt.migrations.MigrationOp
import entkt.migrations.NormalizedColumn
import entkt.migrations.NormalizedSchema
import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.OnDelete
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ── Relationship pattern schemas (file-level for cross-class references) ──

// O2O Two Types
private class O2oUser : EntSchema("o2o_users") {
    override fun id() = EntId.uuid()
    val profile = hasOne<O2oProfile>("profile")
}

private class O2oProfile : EntSchema("o2o_profiles") {
    override fun id() = EntId.uuid()
    val user = belongsTo<O2oUser>("user")
        .inverse(O2oUser::profile)
        .unique()
}

// O2M Two Types
private class O2mUser : EntSchema("o2m_users") {
    override fun id() = EntId.long()
    val posts = hasMany<O2mPost>("posts")
}

private class O2mPost : EntSchema("o2m_posts") {
    override fun id() = EntId.long()
    val author = belongsTo<O2mUser>("author")
        .inverse(O2mUser::posts)
}

// M2M Two Types
private class M2mUser : EntSchema("m2m_users") {
    override fun id() = EntId.long()
    val groups = manyToMany<M2mGroup>("groups")
        .throughEntity<M2mUserGroup>(M2mUserGroup::user, M2mUserGroup::group)
}

private class M2mGroup : EntSchema("m2m_groups") {
    override fun id() = EntId.long()
}

private class M2mUserGroup : EntSchema("m2m_user_groups") {
    override fun id() = EntId.long()
    val user = belongsTo<M2mUser>("user")
    val group = belongsTo<M2mGroup>("group")
    val byUserGroup = index("idx_m2m_user_groups_user_group", user.fk, group.fk).unique()
}

// M2M Same Type
private class M2mPerson : EntSchema("m2m_people") {
    override fun id() = EntId.long()
    val friends = manyToMany<M2mPerson>("friends")
        .throughEntity<M2mFriendship>(M2mFriendship::user, M2mFriendship::friend)
}

private class M2mFriendship : EntSchema("m2m_friendships") {
    override fun id() = EntId.long()
    val user = belongsTo<M2mPerson>("user")
    val friend = belongsTo<M2mPerson>("friend")
    val byFriendPair = index("idx_m2m_friendships_user_friend", user.fk, friend.fk).unique()
}

// M2M Bidirectional
private class M2mBiUser : EntSchema("m2m_bi_users") {
    override fun id() = EntId.long()
    val groups = manyToMany<M2mBiGroup>("groups")
        .throughEntity<M2mBiMembership>(M2mBiMembership::user, M2mBiMembership::group)
}

private class M2mBiGroup : EntSchema("m2m_bi_groups") {
    override fun id() = EntId.long()
    val users = manyToMany<M2mBiUser>("users")
        .throughEntity<M2mBiMembership>(M2mBiMembership::group, M2mBiMembership::user)
}

private class M2mBiMembership : EntSchema("m2m_bi_memberships") {
    override fun id() = EntId.long()
    val user = belongsTo<M2mBiUser>("user")
    val group = belongsTo<M2mBiGroup>("group")
}

/**
 * End-to-end DDL tests: [EntSchema] → EntitySchema → NormalizedSchema → rendered SQL.
 *
 * These verify that the full pipeline from schema declarations to PostgreSQL DDL
 * produces the expected CREATE TABLE, CREATE INDEX, and ALTER TABLE statements.
 * No database container needed — pure unit tests against the renderer.
 */
class PostgresDdlTest {

    private enum class Priority { LOW, MEDIUM, HIGH }

    private val typeMapper = PostgresTypeMapper()
    private val renderer = PostgresSqlRenderer()

    /** Build EntitySchemas from EntSchema declarations, normalize, and render all DDL. */
    private fun renderDdl(vararg schemas: EntSchema): List<String> {
        val inputs = schemas.map { SchemaInput(it::class.simpleName!!, it) }
        val entitySchemas = buildEntitySchemas(inputs)
        val normalized = NormalizedSchema.fromEntitySchemas(entitySchemas, typeMapper)
        return entitySchemas.flatMap { es ->
            val table = normalized.tables[es.table]!!
            val createTable = renderer.render(MigrationOp.CreateTable(table))
            val createIndexes = table.indexes.flatMap { idx ->
                renderer.render(MigrationOp.AddIndex(table.name, idx))
            }
            val addFks = table.foreignKeys.flatMap { fk ->
                renderer.render(MigrationOp.AddForeignKey(table.name, fk))
            }
            createTable + createIndexes + addFks
        }
    }

    @Test
    fun `basic table with auto-increment int id`() {
        class Users : EntSchema("users") {
            override fun id() = EntId.int()
            val name = string("name")
            val bio = text("bio").nullable()
        }

        val ddl = renderDdl(Users())
        assertEquals(
            listOf(
                """
                CREATE TABLE "users" (
                  "id" serial PRIMARY KEY,
                  "name" text NOT NULL,
                  "bio" text
                )
                """.trimIndent(),
            ),
            ddl,
        )
    }

    @Test
    fun `bigserial id for AUTO_LONG strategy`() {
        class Events : EntSchema("events") {
            override fun id() = EntId.long()
            val name = string("name")
        }

        val ddl = renderDdl(Events())
        assertEquals(
            listOf(
                """
                CREATE TABLE "events" (
                  "id" bigserial PRIMARY KEY,
                  "name" text NOT NULL
                )
                """.trimIndent(),
            ),
            ddl,
        )
    }

    @Test
    fun `uuid id for CLIENT_UUID strategy`() {
        class Tokens : EntSchema("tokens") {
            override fun id() = EntId.uuid()
            val value = string("value")
        }

        val ddl = renderDdl(Tokens())
        assertEquals(
            listOf(
                """
                CREATE TABLE "tokens" (
                  "id" uuid PRIMARY KEY,
                  "value" text NOT NULL
                )
                """.trimIndent(),
            ),
            ddl,
        )
    }

    @Test
    fun `all field types map to correct SQL types`() {
        class AllTypes : EntSchema("all_types") {
            override fun id() = EntId.int()
            val a_string = string("a_string")
            val a_text = text("a_text")
            val a_bool = bool("a_bool")
            val an_int = int("an_int")
            val a_long = long("a_long")
            val a_float = float("a_float")
            val a_double = double("a_double")
            val a_time = time("a_time")
            val a_uuid = uuid("a_uuid")
            val some_bytes = bytes("some_bytes")
            val an_enum = enum<Priority>("an_enum")
        }

        val ddl = renderDdl(AllTypes())
        assertEquals(
            listOf(
                """
                CREATE TABLE "all_types" (
                  "id" serial PRIMARY KEY,
                  "a_string" text NOT NULL,
                  "a_text" text NOT NULL,
                  "a_bool" boolean NOT NULL,
                  "an_int" integer NOT NULL,
                  "a_long" bigint NOT NULL,
                  "a_float" real NOT NULL,
                  "a_double" double precision NOT NULL,
                  "a_time" timestamptz NOT NULL,
                  "a_uuid" uuid NOT NULL,
                  "some_bytes" bytea NOT NULL,
                  "an_enum" text NOT NULL
                )
                """.trimIndent(),
            ),
            ddl,
        )
    }

    @Test
    fun `column defaults are emitted in CREATE TABLE`() {
        class Defaults : EntSchema("defaults") {
            override fun id() = EntId.int()
            val statusStr = string("status_str").default("active")
            val isActive = bool("is_active").default(true)
            val count = int("count").default(5)
            val big = long("big").default(100L)
            val ratio = double("ratio").default(1.5)
            val priority = enum<Priority>("priority").default(Priority.MEDIUM)
            val createdAt = time("created_at").defaultNow()
            val note = string("note").default("n/a").nullable()
        }

        val ddl = renderDdl(Defaults())
        assertEquals(
            listOf(
                """
                CREATE TABLE "defaults" (
                  "id" serial PRIMARY KEY,
                  "status_str" text DEFAULT 'active' NOT NULL,
                  "is_active" boolean DEFAULT true NOT NULL,
                  "count" integer DEFAULT 5 NOT NULL,
                  "big" bigint DEFAULT 100 NOT NULL,
                  "ratio" double precision DEFAULT 1.5 NOT NULL,
                  "priority" text DEFAULT 'MEDIUM' NOT NULL,
                  "created_at" timestamptz DEFAULT now() NOT NULL,
                  "note" text DEFAULT 'n/a'
                )
                """.trimIndent(),
            ),
            ddl,
        )
    }

    @Test
    fun `string default with embedded single quote is escaped`() {
        class Quotes : EntSchema("quotes") {
            override fun id() = EntId.int()
            val label = string("label").default("O'Brien")
        }

        val ddl = renderDdl(Quotes())
        assertEquals(
            listOf(
                """
                CREATE TABLE "quotes" (
                  "id" serial PRIMARY KEY,
                  "label" text DEFAULT 'O''Brien' NOT NULL
                )
                """.trimIndent(),
            ),
            ddl,
        )
    }

    @Test
    fun `ADD COLUMN emits DEFAULT before NOT NULL`() {
        val col = NormalizedColumn("status", "text", nullable = false, primaryKey = false, default = "'active'")
        assertEquals(
            listOf("""ALTER TABLE "users" ADD COLUMN "status" text DEFAULT 'active' NOT NULL"""),
            renderer.render(MigrationOp.AddColumn("users", col)),
        )
    }

    @Test
    fun `nullable ADD COLUMN emits DEFAULT without NOT NULL`() {
        val col = NormalizedColumn("note", "text", nullable = true, primaryKey = false, default = "'n/a'")
        assertEquals(
            listOf("""ALTER TABLE "users" ADD COLUMN "note" text DEFAULT 'n/a'"""),
            renderer.render(MigrationOp.AddColumn("users", col)),
        )
    }

    @Test
    fun `destructive ops render as bare DDL for commenting`() {
        assertEquals(
            listOf("""ALTER TABLE "assets" ALTER COLUMN "content_type" DROP NOT NULL"""),
            renderer.render(MigrationOp.DropColumnNotNull("assets", "content_type")),
        )
        assertEquals(
            listOf("""DROP TABLE "old_table""""),
            renderer.render(MigrationOp.DropTable("old_table")),
        )
        assertEquals(
            listOf("""ALTER TABLE "posts" DROP COLUMN "legacy""""),
            renderer.render(MigrationOp.DropColumn("posts", "legacy")),
        )
        // A PK change has no single statement — render nothing rather than a
        // prose hint that would be commented as if it were uncommentable SQL.
        assertTrue(renderer.render(MigrationOp.AlterPrimaryKey("users", "email", added = true)).isEmpty())
    }

    @Test
    fun `SetColumnDefault and DropColumnDefault render as ALTER COLUMN`() {
        assertEquals(
            listOf("""ALTER TABLE "users" ALTER COLUMN "status" SET DEFAULT 'active'"""),
            renderer.render(MigrationOp.SetColumnDefault("users", "status", "'active'")),
        )
        assertEquals(
            listOf("""ALTER TABLE "users" ALTER COLUMN "status" DROP DEFAULT"""),
            renderer.render(MigrationOp.DropColumnDefault("users", "status")),
        )
    }

    @Test
    fun `unique column generates a unique index`() {
        class Users : EntSchema("users") {
            override fun id() = EntId.int()
            val email = string("email").unique()
        }

        val ddl = renderDdl(Users())
        assertEquals(
            listOf(
                """
                CREATE TABLE "users" (
                  "id" serial PRIMARY KEY,
                  "email" text NOT NULL
                )
                """.trimIndent(),
                """CREATE UNIQUE INDEX "idx_users_email_unique" ON "users" ("email")""",
            ),
            ddl,
        )
    }

    @Test
    fun `nullable foreign key with default ON DELETE SET NULL`() {
        class Users : EntSchema("users") {
            override fun id() = EntId.int()
        }
        class Posts : EntSchema("posts") {
            override fun id() = EntId.int()
            val title = string("title")
            val author = belongsTo<Users>("author").nullable()
        }

        val ddl = renderDdl(Users(), Posts())
        assertEquals(
            listOf(
                """
                CREATE TABLE "users" (
                  "id" serial PRIMARY KEY
                )
                """.trimIndent(),
                """
                CREATE TABLE "posts" (
                  "id" serial PRIMARY KEY,
                  "title" text NOT NULL,
                  "author_id" integer
                )
                """.trimIndent(),
                """ALTER TABLE "posts" ADD CONSTRAINT "fk_posts_author_id" FOREIGN KEY ("author_id") REFERENCES "users" ("id") ON DELETE SET NULL""",
            ),
            ddl,
        )
    }

    @Test
    fun `required foreign key with default ON DELETE RESTRICT`() {
        class Users : EntSchema("users") {
            override fun id() = EntId.int()
        }
        class Posts : EntSchema("posts") {
            override fun id() = EntId.int()
            val author = belongsTo<Users>("author")
        }

        val ddl = renderDdl(Users(), Posts())
        assertEquals(
            listOf(
                """
                CREATE TABLE "users" (
                  "id" serial PRIMARY KEY
                )
                """.trimIndent(),
                """
                CREATE TABLE "posts" (
                  "id" serial PRIMARY KEY,
                  "author_id" integer NOT NULL
                )
                """.trimIndent(),
                """ALTER TABLE "posts" ADD CONSTRAINT "fk_posts_author_id" FOREIGN KEY ("author_id") REFERENCES "users" ("id") ON DELETE RESTRICT""",
            ),
            ddl,
        )
    }

    @Test
    fun `foreign key with explicit CASCADE`() {
        class Users : EntSchema("users") {
            override fun id() = EntId.int()
        }
        class Posts : EntSchema("posts") {
            override fun id() = EntId.int()
            val author = belongsTo<Users>("author").onDelete(OnDelete.CASCADE)
        }

        val ddl = renderDdl(Users(), Posts())
        assertEquals(
            listOf(
                """
                CREATE TABLE "users" (
                  "id" serial PRIMARY KEY
                )
                """.trimIndent(),
                """
                CREATE TABLE "posts" (
                  "id" serial PRIMARY KEY,
                  "author_id" integer NOT NULL
                )
                """.trimIndent(),
                """ALTER TABLE "posts" ADD CONSTRAINT "fk_posts_author_id" FOREIGN KEY ("author_id") REFERENCES "users" ("id") ON DELETE CASCADE""",
            ),
            ddl,
        )
    }

    @Test
    fun `composite index`() {
        class Users : EntSchema("users") {
            override fun id() = EntId.int()
            val firstName = string("first_name")
            val lastName = string("last_name")
            val byName = index("idx_users_name", firstName, lastName)
        }

        val ddl = renderDdl(Users())
        assertEquals(
            listOf(
                """
                CREATE TABLE "users" (
                  "id" serial PRIMARY KEY,
                  "first_name" text NOT NULL,
                  "last_name" text NOT NULL
                )
                """.trimIndent(),
                """CREATE INDEX "idx_users_name" ON "users" ("first_name", "last_name")""",
            ),
            ddl,
        )
    }

    @Test
    fun `unique composite index`() {
        class UserRoles : EntSchema("user_roles") {
            override fun id() = EntId.int()
            val userId = int("user_id")
            val role = string("role")
            val byUserRole = index("idx_user_roles_unique", userId, role).unique()
        }

        val ddl = renderDdl(UserRoles())
        assertEquals(
            listOf(
                """
                CREATE TABLE "user_roles" (
                  "id" serial PRIMARY KEY,
                  "user_id" integer NOT NULL,
                  "role" text NOT NULL
                )
                """.trimIndent(),
                """CREATE UNIQUE INDEX "idx_user_roles_unique" ON "user_roles" ("user_id", "role")""",
            ),
            ddl,
        )
    }

    @Test
    fun `partial index with WHERE clause`() {
        class Users : EntSchema("users") {
            override fun id() = EntId.int()
            val email = string("email")
            val active = bool("active")
            val byEmailActive = index("idx_users_email_active", email).unique().where("active = true")
        }

        val ddl = renderDdl(Users())
        assertEquals(
            listOf(
                """
                CREATE TABLE "users" (
                  "id" serial PRIMARY KEY,
                  "email" text NOT NULL,
                  "active" boolean NOT NULL
                )
                """.trimIndent(),
                """CREATE UNIQUE INDEX "idx_users_email_active" ON "users" ("email") WHERE active = true""",
            ),
            ddl,
        )
    }

    @Test
    fun `unique column plus composite index on same table`() {
        class Posts : EntSchema("posts") {
            override fun id() = EntId.long()
            val slug = string("slug").unique()
            val authorId = int("author_id")
            val createdAt = time("created_at")
            val byAuthorDate = index("idx_posts_author_date", authorId, createdAt)
        }

        val ddl = renderDdl(Posts())
        assertEquals(
            listOf(
                """
                CREATE TABLE "posts" (
                  "id" bigserial PRIMARY KEY,
                  "slug" text NOT NULL,
                  "author_id" integer NOT NULL,
                  "created_at" timestamptz NOT NULL
                )
                """.trimIndent(),
                """CREATE UNIQUE INDEX "idx_posts_slug_unique" ON "posts" ("slug")""",
                """CREATE INDEX "idx_posts_author_date" ON "posts" ("author_id", "created_at")""",
            ),
            ddl,
        )
    }

    @Test
    fun `junction table with two foreign keys`() {
        class Users : EntSchema("users") {
            override fun id() = EntId.int()
        }
        class Groups : EntSchema("groups") {
            override fun id() = EntId.int()
        }
        class UserGroups : EntSchema("user_groups") {
            override fun id() = EntId.int()
            val user = belongsTo<Users>("user").onDelete(OnDelete.CASCADE)
            val group = belongsTo<Groups>("group").onDelete(OnDelete.CASCADE)
            val byUserGroup = index("idx_user_groups_unique", user.fk, group.fk).unique()
        }

        val ddl = renderDdl(Users(), Groups(), UserGroups())
        assertEquals(
            listOf(
                """
                CREATE TABLE "users" (
                  "id" serial PRIMARY KEY
                )
                """.trimIndent(),
                """
                CREATE TABLE "groups" (
                  "id" serial PRIMARY KEY
                )
                """.trimIndent(),
                """
                CREATE TABLE "user_groups" (
                  "id" serial PRIMARY KEY,
                  "user_id" integer NOT NULL,
                  "group_id" integer NOT NULL
                )
                """.trimIndent(),
                """CREATE UNIQUE INDEX "idx_user_groups_unique" ON "user_groups" ("user_id", "group_id")""",
                """ALTER TABLE "user_groups" ADD CONSTRAINT "fk_user_groups_user_id" FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE""",
                """ALTER TABLE "user_groups" ADD CONSTRAINT "fk_user_groups_group_id" FOREIGN KEY ("group_id") REFERENCES "groups" ("id") ON DELETE CASCADE""",
            ),
            ddl,
        )
    }

    // ── Relationship pattern tests (from docs/schema.md) ──────────

    @Test
    fun `O2O two types - hasOne + belongsTo unique`() {
        val ddl = renderDdl(O2oUser(), O2oProfile())
        assertEquals(
            listOf(
                """
                CREATE TABLE "o2o_users" (
                  "id" uuid PRIMARY KEY
                )
                """.trimIndent(),
                """
                CREATE TABLE "o2o_profiles" (
                  "id" uuid PRIMARY KEY,
                  "user_id" uuid NOT NULL
                )
                """.trimIndent(),
                """CREATE UNIQUE INDEX "idx_o2o_profiles_user_id_unique" ON "o2o_profiles" ("user_id")""",
                """ALTER TABLE "o2o_profiles" ADD CONSTRAINT "fk_o2o_profiles_user_id" FOREIGN KEY ("user_id") REFERENCES "o2o_users" ("id") ON DELETE RESTRICT""",
            ),
            ddl,
        )
    }

    @Test
    fun `O2O same type - self-referencing unique FK`() {
        class Employee : EntSchema("employees") {
            override fun id() = EntId.long()
            val mentee = hasOne<Employee>("mentee")
            val mentor = belongsTo<Employee>("mentor")
                .inverse(Employee::mentee)
                .unique()
                .nullable()
        }

        val ddl = renderDdl(Employee())
        assertEquals(
            listOf(
                """
                CREATE TABLE "employees" (
                  "id" bigserial PRIMARY KEY,
                  "mentor_id" bigint
                )
                """.trimIndent(),
                """CREATE UNIQUE INDEX "idx_employees_mentor_id_unique" ON "employees" ("mentor_id")""",
                """ALTER TABLE "employees" ADD CONSTRAINT "fk_employees_mentor_id" FOREIGN KEY ("mentor_id") REFERENCES "employees" ("id") ON DELETE SET NULL""",
            ),
            ddl,
        )
    }

    @Test
    fun `O2M two types - hasMany + required belongsTo`() {
        val ddl = renderDdl(O2mUser(), O2mPost())
        assertEquals(
            listOf(
                """
                CREATE TABLE "o2m_users" (
                  "id" bigserial PRIMARY KEY
                )
                """.trimIndent(),
                """
                CREATE TABLE "o2m_posts" (
                  "id" bigserial PRIMARY KEY,
                  "author_id" bigint NOT NULL
                )
                """.trimIndent(),
                """ALTER TABLE "o2m_posts" ADD CONSTRAINT "fk_o2m_posts_author_id" FOREIGN KEY ("author_id") REFERENCES "o2m_users" ("id") ON DELETE RESTRICT""",
            ),
            ddl,
        )
    }

    @Test
    fun `O2M same type - self-referencing tree`() {
        class Category : EntSchema("categories") {
            override fun id() = EntId.long()
            val children = hasMany<Category>("children")
            val parent = belongsTo<Category>("parent")
                .inverse(Category::children)
                .nullable()
        }

        val ddl = renderDdl(Category())
        assertEquals(
            listOf(
                """
                CREATE TABLE "categories" (
                  "id" bigserial PRIMARY KEY,
                  "parent_id" bigint
                )
                """.trimIndent(),
                """ALTER TABLE "categories" ADD CONSTRAINT "fk_categories_parent_id" FOREIGN KEY ("parent_id") REFERENCES "categories" ("id") ON DELETE SET NULL""",
            ),
            ddl,
        )
    }

    @Test
    fun `M2M two types - junction table with required FKs`() {
        val ddl = renderDdl(M2mUser(), M2mGroup(), M2mUserGroup())
        assertEquals(
            listOf(
                """
                CREATE TABLE "m2m_users" (
                  "id" bigserial PRIMARY KEY
                )
                """.trimIndent(),
                """
                CREATE TABLE "m2m_groups" (
                  "id" bigserial PRIMARY KEY
                )
                """.trimIndent(),
                """
                CREATE TABLE "m2m_user_groups" (
                  "id" bigserial PRIMARY KEY,
                  "user_id" bigint NOT NULL,
                  "group_id" bigint NOT NULL
                )
                """.trimIndent(),
                """CREATE UNIQUE INDEX "idx_m2m_user_groups_user_group" ON "m2m_user_groups" ("user_id", "group_id")""",
                """ALTER TABLE "m2m_user_groups" ADD CONSTRAINT "fk_m2m_user_groups_user_id" FOREIGN KEY ("user_id") REFERENCES "m2m_users" ("id") ON DELETE RESTRICT""",
                """ALTER TABLE "m2m_user_groups" ADD CONSTRAINT "fk_m2m_user_groups_group_id" FOREIGN KEY ("group_id") REFERENCES "m2m_groups" ("id") ON DELETE RESTRICT""",
            ),
            ddl,
        )
    }

    @Test
    fun `M2M same type - self-referencing junction`() {
        val ddl = renderDdl(M2mPerson(), M2mFriendship())
        assertEquals(
            listOf(
                """
                CREATE TABLE "m2m_people" (
                  "id" bigserial PRIMARY KEY
                )
                """.trimIndent(),
                """
                CREATE TABLE "m2m_friendships" (
                  "id" bigserial PRIMARY KEY,
                  "user_id" bigint NOT NULL,
                  "friend_id" bigint NOT NULL
                )
                """.trimIndent(),
                """CREATE UNIQUE INDEX "idx_m2m_friendships_user_friend" ON "m2m_friendships" ("user_id", "friend_id")""",
                """ALTER TABLE "m2m_friendships" ADD CONSTRAINT "fk_m2m_friendships_user_id" FOREIGN KEY ("user_id") REFERENCES "m2m_people" ("id") ON DELETE RESTRICT""",
                """ALTER TABLE "m2m_friendships" ADD CONSTRAINT "fk_m2m_friendships_friend_id" FOREIGN KEY ("friend_id") REFERENCES "m2m_people" ("id") ON DELETE RESTRICT""",
            ),
            ddl,
        )
    }

    @Test
    fun `M2M bidirectional - both endpoints declare manyToMany`() {
        val ddl = renderDdl(M2mBiUser(), M2mBiGroup(), M2mBiMembership())
        assertEquals(
            listOf(
                """
                CREATE TABLE "m2m_bi_users" (
                  "id" bigserial PRIMARY KEY
                )
                """.trimIndent(),
                """
                CREATE TABLE "m2m_bi_groups" (
                  "id" bigserial PRIMARY KEY
                )
                """.trimIndent(),
                """
                CREATE TABLE "m2m_bi_memberships" (
                  "id" bigserial PRIMARY KEY,
                  "user_id" bigint NOT NULL,
                  "group_id" bigint NOT NULL
                )
                """.trimIndent(),
                """ALTER TABLE "m2m_bi_memberships" ADD CONSTRAINT "fk_m2m_bi_memberships_user_id" FOREIGN KEY ("user_id") REFERENCES "m2m_bi_users" ("id") ON DELETE RESTRICT""",
                """ALTER TABLE "m2m_bi_memberships" ADD CONSTRAINT "fk_m2m_bi_memberships_group_id" FOREIGN KEY ("group_id") REFERENCES "m2m_bi_groups" ("id") ON DELETE RESTRICT""",
            ),
            ddl,
        )
    }
}

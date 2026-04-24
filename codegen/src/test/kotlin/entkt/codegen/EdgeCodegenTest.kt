package entkt.codegen

import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.OnDelete
import entkt.schema.Through
import entkt.schema.edges
import entkt.schema.fields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

object Owner : EntSchema() {
    override fun id() = EntId.long()

    override fun fields() = fields {
        string("name")
    }

    override fun edges() = edges {
        // The User-side of the Owner ↔ Pet pair: an Owner has many Pets.
        // No FK on Owner — the FK lives on Pet via the inverse `owner` edge.
        hasMany("pets", Pet)
    }
}

object Pet : EntSchema() {
    override fun fields() = fields {
        string("name")
    }

    override fun edges() = edges {
        belongsTo("owner", Owner).ref("pets")
    }
}

object RequiredPet : EntSchema() {
    override fun fields() = fields {
        string("name")
    }

    override fun edges() = edges {
        belongsTo("owner", Owner).required()
    }
}

// ---------- M2M test schemas ----------

object Team : EntSchema() {
    override fun fields() = fields {
        string("name")
    }

    override fun edges() = edges {
        manyToMany("members", Pet).through(TeamMember)
    }
}

object TeamMember : EntSchema() {
    override fun fields() = fields {
        time("joined_at")
        int("team_id")
        int("member_id")
    }

    override fun edges() = edges {
        belongsTo("team", Team).required().field("team_id")
        belongsTo("member", Pet).required().field("member_id")
    }
}

// ---------- Self-referential M2M test schemas ----------

object Person : EntSchema() {
    override fun fields() = fields {
        string("name")
    }

    override fun edges() = edges {
        manyToMany("friends", Person).through(Friendship, sourceEdge = "person", targetEdge = "friend")
    }
}

object Friendship : EntSchema() {
    override fun fields() = fields {
        time("created_at")
        int("person_id")
        int("friend_id")
    }

    override fun edges() = edges {
        belongsTo("person", Person).required().field("person_id")
        belongsTo("friend", Person).required().field("friend_id")
    }
}

// ---------- Ambiguous junction test schemas ----------

object Project : EntSchema() {
    override fun fields() = fields {
        string("name")
    }

    override fun edges() = edges {
        // The junction has two edges to Pet: "assignee" and "reviewer".
        // sourceEdge/targetEdge disambiguate which FK to use.
        manyToMany("assignees", Pet).through(
            ProjectAssignment,
            sourceEdge = "project", targetEdge = "assignee",
        )
    }
}

object ProjectAssignment : EntSchema() {
    override fun fields() = fields {
        time("assigned_at")
        int("project_id")
        int("assignee_id")
        int("reviewer_id").nullable()
    }

    override fun edges() = edges {
        belongsTo("project", Project).required().field("project_id")
        belongsTo("assignee", Pet).required().field("assignee_id")
        belongsTo("reviewer", Pet).field("reviewer_id")
    }
}

private val schemaNames: Map<EntSchema, String> = mapOf(
    Owner to "Owner",
    Pet to "Pet",
    RequiredPet to "RequiredPet",
    Team to "Team",
    TeamMember to "TeamMember",
    Person to "Person",
    Friendship to "Friendship",
    Project to "Project",
    ProjectAssignment to "ProjectAssignment",
)

class EdgeCodegenTest {

    @Test
    fun `entity gets nullable FK property for optional unique edge`() {
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("val ownerId: Long?")) { "Should add nullable ownerId FK\n$output" }
    }

    @Test
    fun `entity gets non-null FK property for required unique edge`() {
        val output = EntityGenerator("com.example.ent")
            .generate("RequiredPet", RequiredPet, schemaNames).toString()

        assert(output.contains("val ownerId: Long,") || output.contains("val ownerId: Long\n")) {
            "Should add non-null ownerId FK\n$output"
        }
    }

    @Test
    fun `create builder gets id and entity properties for unique edge`() {
        val output = CreateGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("var ownerId: Long?")) {
            "Should have ownerId: Long? property\n$output"
        }
        assert(output.contains("var owner: Owner?")) {
            "Should have owner: Owner? convenience property\n$output"
        }
        assert(output.contains("ownerId = value?.id")) {
            "owner setter should write value.id to ownerId\n$output"
        }
    }

    @Test
    fun `create builder save validates required edge`() {
        val output = CreateGenerator("com.example.ent")
            .generate("RequiredPet", RequiredPet, schemaNames).toString()

        assert(output.contains("\"owner is required\"")) {
            "Should validate required edge in save()\n$output"
        }
    }

    @Test
    fun `update builder gets id and entity properties for unique edge`() {
        val output = UpdateGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("var ownerId: Long?")) {
            "Should have ownerId: Long? property\n$output"
        }
        assert(output.contains("var owner: Owner?")) {
            "Should have owner: Owner? convenience property\n$output"
        }
    }

    @Test
    fun `update builder save uses dirty tracking for edge FK`() {
        val output = UpdateGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("if (\"ownerId\" in dirtyFields) this.ownerId else entity.ownerId")) {
            "Should check dirtyFields for edge FK fallback\n$output"
        }
    }

    @Test
    fun `update builder edge FK setter tracks dirty state`() {
        val output = UpdateGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("dirtyFields.add(\"ownerId\")")) {
            "Setting ownerId should add to dirtyFields\n$output"
        }
    }

    @Test
    fun `entity emits nullable column ref for optional unique edge FK`() {
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        // Optional edge -> nullable FK. Long is Comparable so the FK
        // gets NullableComparableColumn (range queries on IDs are useful
        // for pagination). Being nullable, it implements [Nullable] so
        // isNotNull() is callable.
        assert(output.contains("val ownerId: NullableComparableColumn<Long> = NullableComparableColumn<Long>(\"owner_id\")")) {
            "Should emit NullableComparableColumn<Long> for optional edge FK\n$output"
        }
    }

    @Test
    fun `entity emits non-null column ref for required unique edge FK`() {
        val output = EntityGenerator("com.example.ent")
            .generate("RequiredPet", RequiredPet, schemaNames).toString()

        assert(output.contains("val ownerId: ComparableColumn<Long> = ComparableColumn<Long>(\"owner_id\")")) {
            "Should emit non-null ComparableColumn<Long> for required edge FK\n$output"
        }
    }

    // ---------- Foreign key references in generated SCHEMA ----------

    @Test
    fun `generated SCHEMA includes ForeignKeyRef for edge FK columns`() {
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("ForeignKeyRef")) {
            "Should emit ForeignKeyRef for the owner_id FK column\n$output"
        }
        assert(output.contains("table = \"owners\"")) {
            "Should reference the owners table\n$output"
        }
        assert(output.contains("column = \"id\"")) {
            "Should reference the id column\n$output"
        }
    }

    @Test
    fun `non-FK columns do not get ForeignKeyRef`() {
        val output = EntityGenerator("com.example.ent")
            .generate("Owner", Owner, schemaNames).toString()

        assert(!output.contains("ForeignKeyRef")) {
            "Owner has no FK columns — should not emit ForeignKeyRef\n$output"
        }
    }

    // ---------- EdgeRef emission ----------

    @Test
    fun `entity emits EdgeRef on the companion for to-many edges`() {
        // Owner has `to("pets", Pet)`, no FK on Owner — but it should
        // still get an EdgeRef so callers can write
        // `Owner.pets.has { ... }` and `client.owners.query{}.queryPets()`.
        val output = EntityGenerator("com.example.ent")
            .generate("Owner", Owner, schemaNames).toString()

        assert(output.contains("import entkt.query.EdgeRef")) {
            "Should import EdgeRef\n$output"
        }
        assert(output.contains("val pets: EdgeRef<Pet, PetQuery> = EdgeRef(\"pets\") { PetQuery(NoopDriver) }")) {
            "Should emit EdgeRef for the pets edge wired to NoopDriver\n$output"
        }
    }

    @Test
    fun `entity emits EdgeRef on the companion for from-side unique edges`() {
        // Pet has `from("owner", Owner).ref("pets").unique()` — both the
        // FK column ref AND a separate EdgeRef should be emitted.
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("val owner: EdgeRef<Owner, OwnerQuery> = EdgeRef(\"owner\") { OwnerQuery(NoopDriver) }")) {
            "Should emit EdgeRef for the owner edge wired to NoopDriver\n$output"
        }
        // The FK column ref still lives next to it
        assert(output.contains("val ownerId: NullableComparableColumn<Long>")) {
            "FK column ref should coexist with the EdgeRef\n$output"
        }
    }

    // ---------- Traversal methods ----------

    @Test
    fun `query gets traversal method for paired to-many edge`() {
        // Owner has `to("pets", Pet)` paired with Pet's
        // `from("owner", Owner).ref("pets")` — the inverse on Pet is "owner".
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", Owner, schemaNames).toString()

        assert(output.contains("fun queryPets(): PetQuery")) {
            "Should generate traversal queryPets()\n$output"
        }
        // The inverse edge on Pet is "owner" — that's the name baked
        // into the HasEdgeWith node so the runtime knows which FK to
        // join through.
        assert(output.contains("Predicate.HasEdgeWith(\"owner\", parent)")) {
            "Should reference the inverse edge name in HasEdgeWith\n$output"
        }
        // Empty parent → still emit HasEdge so optional inverse edges
        // filter out unrelated rows (no-op for required edges).
        assert(output.contains("Predicate.HasEdge(\"owner\")")) {
            "Should fall back to HasEdge when parent has no wheres\n$output"
        }
    }

    @Test
    fun `query gets traversal method on the from-side too`() {
        // Same pair from the other direction: PetQuery.queryOwner() should
        // exist and reference Owner's "pets" edge as the inverse.
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("fun queryOwner(): OwnerQuery")) {
            "Should generate traversal queryOwner()\n$output"
        }
        assert(output.contains("Predicate.HasEdgeWith(\"pets\", parent)")) {
            "Should reference Owner's 'pets' edge as the inverse\n$output"
        }
    }

    @Test
    fun `does not emit traversal when the inverse edge cannot be resolved`() {
        // RequiredPet has `from("owner", Owner).unique()` with no .ref(),
        // and Owner declares `to("pets", Pet)` (not RequiredPet) — there's
        // no back-edge from Owner to RequiredPet, so PetQuery → Owner
        // can be resolved via the single-back-edge fallback, but the
        // OwnerQuery → RequiredPet direction has no inverse and should
        // skip emitting a traversal. We assert the failing direction.
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", Owner, schemaNames).toString()

        // Owner.queryPets exists (paired with Pet, not RequiredPet) but
        // no `queryRequiredPets()` should appear.
        assert(!output.contains("queryRequiredPets")) {
            "Should not emit traversal when there's no matching back-edge\n$output"
        }
    }

    @Test
    fun `bad ref value fails at codegen time`() {
        val parent = object : EntSchema() {
            override fun fields() = fields { string("name") }
        }
        val child = object : EntSchema() {
            override fun fields() = fields { string("name") }
            override fun edges() = edges {
                belongsTo("parent", parent).ref("typo")
            }
        }
        val names = mapOf(parent to "Parent", child to "Child")
        val error = kotlin.test.assertFailsWith<IllegalStateException> {
            QueryGenerator("com.example.ent").generate("Child", child, names)
        }
        assert(error.message!!.contains("typo")) {
            "Error should mention the bad ref value\n${error.message}"
        }
    }

    @Test
    fun `ambiguous ref aliases on target fail at codegen time`() {
        // Use a holder so user and post can reference each other
        class Schemas {
            val user: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("name") }
                override fun edges() = edges {
                    hasMany("posts", this@Schemas.post)
                }
            }
            val post: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("title") }
                override fun edges() = edges {
                    belongsTo("author", this@Schemas.user).ref("posts")
                    belongsTo("editor", this@Schemas.user).ref("posts")
                }
            }
        }
        val s = Schemas()
        val names = mapOf(s.user to "User", s.post to "Post")
        val error = kotlin.test.assertFailsWith<IllegalStateException> {
            QueryGenerator("com.example.ent").generate("User", s.user, names)
        }
        assert(error.message!!.contains("ambiguous")) {
            "Error should mention ambiguity\n${error.message}"
        }
    }

    // ---------- M2M edge codegen ----------

    @Test
    fun `entity emits EdgeRef for M2M edge`() {
        val output = EntityGenerator("com.example.ent")
            .generate("Team", Team, schemaNames).toString()

        assert(output.contains("val members: EdgeRef<Pet, PetQuery> = EdgeRef(\"members\") { PetQuery(NoopDriver) }")) {
            "Should emit EdgeRef for M2M members edge\n$output"
        }
    }

    @Test
    fun `M2M edge does not produce FK on source entity`() {
        val output = EntityGenerator("com.example.ent")
            .generate("Team", Team, schemaNames).toString()

        assert(!output.contains("membersId")) {
            "M2M edge should not produce an FK property on the source\n$output"
        }
    }

    @Test
    fun `generated SCHEMA includes junction metadata for M2M edge`() {
        val output = EntityGenerator("com.example.ent")
            .generate("Team", Team, schemaNames).toString()

        assert(output.contains("junctionTable")) {
            "Should include junction metadata in SCHEMA\n$output"
        }
        assert(output.contains("\"teamMembers\"")) {
            "Junction table should be the TeamMember table name\n$output"
        }
    }

    @Test
    fun `target entity gets reverse M2M edge in SCHEMA`() {
        // Pet is the target of Team's M2M "members" edge — Pet's SCHEMA
        // should include a reverse "teams" edge with junction metadata.
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("\"teams_members\"")) {
            "Pet SCHEMA should include reverse 'teams_members' M2M edge\n$output"
        }
        assert(output.contains("junctionTable = \"teamMembers\"")) {
            "Reverse edge should carry junction table metadata\n$output"
        }
    }

    @Test
    fun `query gets M2M traversal method`() {
        val output = QueryGenerator("com.example.ent")
            .generate("Team", Team, schemaNames).toString()

        assert(output.contains("fun queryMembers(): PetQuery")) {
            "Should generate M2M traversal queryMembers()\n$output"
        }
        // M2M traversal references the reverse edge name on the target
        // (Pet's table-name-derived "teams" edge).
        assert(output.contains("Predicate.HasEdgeWith(\"teams_members\", parent)")) {
            "Should reference reverse M2M edge name\n$output"
        }
        assert(output.contains("Predicate.HasEdge(\"teams_members\")")) {
            "Should fall back to HasEdge when parent has no wheres\n$output"
        }
    }

    // ---------- Edges inner data class ----------

    @Test
    fun `entity Edges class has nullable entity for to-one edge`() {
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("data class Edges")) {
            "Should generate inner Edges class\n$output"
        }
        assert(output.contains("val owner: Owner? = null")) {
            "To-one edge should produce nullable entity property\n$output"
        }
    }

    @Test
    fun `entity Edges class has nullable list for to-many edge`() {
        val output = EntityGenerator("com.example.ent")
            .generate("Owner", Owner, schemaNames).toString()

        assert(output.contains("val pets: List<Pet>? = null")) {
            "To-many edge should produce nullable list property\n$output"
        }
    }

    @Test
    fun `entity Edges class has nullable list for M2M edge`() {
        val output = EntityGenerator("com.example.ent")
            .generate("Team", Team, schemaNames).toString()

        assert(output.contains("val members: List<Pet>? = null")) {
            "M2M edge should produce nullable list property\n$output"
        }
    }

    // ---------- Eager loading: with{Edge} methods ----------

    @Test
    fun `query generates withPets for to-many edge`() {
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", Owner, schemaNames).toString()

        assert(output.contains("fun withPets(")) {
            "Should generate withPets method\n$output"
        }
        assert(output.contains("PetQuery.() -> Unit")) {
            "withPets should accept a PetQuery DSL block\n$output"
        }
        assert(output.contains(": OwnerQuery")) {
            "withPets should return OwnerQuery for chaining\n$output"
        }
    }

    @Test
    fun `query generates withOwner for to-one edge`() {
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("fun withOwner(")) {
            "Should generate withOwner method\n$output"
        }
        assert(output.contains("OwnerQuery.() -> Unit")) {
            "withOwner should accept an OwnerQuery DSL block\n$output"
        }
    }

    @Test
    fun `query generates withMembers for M2M edge`() {
        val output = QueryGenerator("com.example.ent")
            .generate("Team", Team, schemaNames).toString()

        assert(output.contains("fun withMembers(")) {
            "Should generate withMembers method\n$output"
        }
    }

    @Test
    fun `query generates loadEdges for schemas with edges`() {
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", Owner, schemaNames).toString()

        assert(output.contains("fun loadEdges(")) {
            "Should generate loadEdges method\n$output"
        }
    }

    @Test
    fun `all() delegates to loadEdges for schemas with edges`() {
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", Owner, schemaNames).toString()

        assert(output.contains("loadEdges(results, privacy)")) {
            "all() should delegate to loadEdges after privacy check\n$output"
        }
    }

    @Test
    fun `to-many eager loading queries target with IN predicate on FK column`() {
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", Owner, schemaNames).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("Predicate.Leaf(\"owner_id\", Op.IN, sourceIds)")) {
            "Should build IN predicate on the FK column\n$output"
        }
    }

    @Test
    fun `to-one eager loading queries target by id`() {
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("Predicate.Leaf(\"id\", Op.IN, fkValues)")) {
            "Should build IN predicate on target id column\n$output"
        }
    }

    @Test
    fun `M2M eager loading queries junction table then target`() {
        val output = QueryGenerator("com.example.ent")
            .generate("Team", Team, schemaNames).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("\"teamMembers\"")) {
            "Should query junction table\n$output"
        }
        assert(output.contains("Predicate.Leaf(\"team_id\", Op.IN, sourceIds)")) {
            "Should query junction with source FK\n$output"
        }
    }

    // ---------- Self-referential M2M ----------

    @Test
    fun `self-referential M2M resolves distinct source and target FKs`() {
        val output = EntityGenerator("com.example.ent")
            .generate("Person", Person, schemaNames).toString()

        // The junction has person_id (source) and friend_id (target).
        // Both junction edges target Person, so without the fix they'd
        // both resolve to person_id.
        assert(output.contains("junctionSourceColumn = \"person_id\"")) {
            "Source FK should be person_id\n$output"
        }
        assert(output.contains("junctionTargetColumn = \"friend_id\"")) {
            "Target FK should be friend_id (not person_id again)\n$output"
        }
    }

    @Test
    fun `self-referential M2M query uses correct junction FKs`() {
        val output = QueryGenerator("com.example.ent")
            .generate("Person", Person, schemaNames).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("Predicate.Leaf(\"person_id\", Op.IN, sourceIds)")) {
            "Should query junction with source FK person_id\n$output"
        }
    }

    @Test
    fun `self-referential M2M without hints fails`() {
        class Schemas {
            val person: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("name") }
                override fun edges() = edges {
                    manyToMany("friends", person).through(junction)
                }
            }
            val junction: EntSchema = object : EntSchema() {
                override fun fields() = fields {
                    int("person_id")
                    int("friend_id")
                }
                override fun edges() = edges {
                    belongsTo("person", person).required().field("person_id")
                    belongsTo("friend", person).required().field("friend_id")
                }
            }
        }
        val s = Schemas()
        val names = mapOf(s.person to "Person", s.junction to "Friendship")
        val error = runCatching {
            EntityGenerator("com.example.ent").generate("Person", s.person, names)
        }.exceptionOrNull()
        assert(error != null) { "Should fail without sourceEdge/targetEdge hints" }
        assert(error!!.message!!.contains("Ambiguous M2M")) {
            "Error should mention ambiguity: ${error.message}"
        }
    }

    @Test
    fun `self-referential M2M rejects duplicate sourceEdge and targetEdge hints`() {
        class Schemas {
            val person: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("name") }
                override fun edges() = edges {
                    manyToMany("friends", person).through(
                        junction, sourceEdge = "person", targetEdge = "person",
                    )
                }
            }
            val junction: EntSchema = object : EntSchema() {
                override fun fields() = fields {
                    int("person_id")
                    int("friend_id")
                }
                override fun edges() = edges {
                    belongsTo("person", person).required().field("person_id")
                    belongsTo("friend", person).required().field("friend_id")
                }
            }
        }
        val s = Schemas()
        val names = mapOf(s.person to "Person", s.junction to "Friendship")
        val error = runCatching {
            EntityGenerator("com.example.ent").generate("Person", s.person, names)
        }.exceptionOrNull()
        assert(error != null) { "Should fail when sourceEdge == targetEdge" }
        assert(error!!.message!!.contains("same junction edge")) {
            "Error should mention duplicate edge: ${error.message}"
        }
    }

    // ---------- Per-group limit/offset in eager loading ----------

    @Test
    fun `to-many eager loading applies limit per group not globally`() {
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", Owner, schemaNames).toString().replace("\\s+".toRegex(), " ")

        // The batch query should pass null for limit/offset
        assert(output.contains("subQuery.orderFields, null, null)")) {
            "Batch query should not pass limit/offset to driver\n$output"
        }
        // Per-group slicing should exist
        assert(output.contains("perGroupOffset") && output.contains("perGroupLimit")) {
            "Should apply limit/offset per group\n$output"
        }
    }

    @Test
    fun `M2M eager loading applies limit per group not globally`() {
        val output = QueryGenerator("com.example.ent")
            .generate("Team", Team, schemaNames).toString().replace("\\s+".toRegex(), " ")

        // Target query should pass null for limit/offset
        assert(output.contains("subQuery.orderFields, null, null)")) {
            "Target query should not pass limit/offset to driver\n$output"
        }
        assert(output.contains("perGroupOffset") && output.contains("perGroupLimit")) {
            "Should apply limit/offset per group\n$output"
        }
    }

    // ---------- Ambiguous junction disambiguation ----------

    @Test
    fun `through with sourceEdge and targetEdge picks the correct junction FKs`() {
        val output = EntityGenerator("com.example.ent")
            .generate("Project", Project, schemaNames).toString()

        // Should use "assignee_id" (from the "assignee" edge), not "reviewer_id"
        assert(output.contains("junctionSourceColumn = \"project_id\"")) {
            "Source FK should be project_id\n$output"
        }
        assert(output.contains("junctionTargetColumn = \"assignee_id\"")) {
            "Target FK should be assignee_id, not reviewer_id\n$output"
        }
    }

    @Test
    fun `ambiguous junction without hints fails fast`() {
        // ProjectAssignment has two unique edges to Pet ("assignee" and
        // "reviewer"). Without sourceEdge/targetEdge hints, the target
        // side is ambiguous and should fail at codegen time.
        val edge = Edge(
            name = "assignees",
            target = Pet,
            kind = EdgeKind.ManyToMany(Through(ProjectAssignment)),
        )

        val error = assertFailsWith<IllegalStateException> {
            resolveM2MEdgeJoin(edge, Project, schemaNames)
        }
        assert(error.message!!.contains("Ambiguous M2M")) {
            "Should mention ambiguous M2M: ${error.message}"
        }
        assert(error.message!!.contains("sourceEdge")) {
            "Should suggest sourceEdge/targetEdge: ${error.message}"
        }
    }

    @Test
    fun `wrong sourceEdge hint fails fast with clear error`() {
        // sourceEdge "assignee" points at Pet, not Project — should
        // error rather than silently dropping the edge.
        val edge = Edge(
            name = "assignees",
            target = Pet,
            kind = EdgeKind.ManyToMany(Through(
                ProjectAssignment,
                sourceEdge = "assignee",  // wrong: points at Pet, not source (Project)
                targetEdge = "reviewer",
            )),
        )

        val error = assertFailsWith<IllegalStateException> {
            resolveM2MEdgeJoin(edge, Project, schemaNames)
        }
        assert(error.message!!.contains("sourceEdge hint")) {
            "Should mention sourceEdge hint: ${error.message}"
        }
    }

    @Test
    fun `wrong targetEdge hint fails fast with clear error`() {
        // targetEdge "project" points at Project, not Pet — should
        // error rather than silently dropping the edge.
        val edge = Edge(
            name = "assignees",
            target = Pet,
            kind = EdgeKind.ManyToMany(Through(
                ProjectAssignment,
                sourceEdge = "project",
                targetEdge = "project",  // wrong: points at Project, not target (Pet)
            )),
        )

        val error = assertFailsWith<IllegalStateException> {
            resolveM2MEdgeJoin(edge, Project, schemaNames)
        }
        assert(error.message!!.contains("targetEdge hint")) {
            "Should mention targetEdge hint: ${error.message}"
        }
    }

    // ---------- onDelete with explicit .field() ----------

    @Test
    fun `onDelete propagates through explicit field edges`() {
        val parent = object : EntSchema() {
            override fun id() = EntId.long()
            override fun fields() = fields { string("name") }
        }
        val child = object : EntSchema() {
            override fun fields() = fields {
                string("name")
                long("owner_id")
            }
            override fun edges() = edges {
                belongsTo("owner", parent).field("owner_id").onDelete(OnDelete.CASCADE)
            }
        }
        val names = mapOf(parent to "Parent", child to "Child")
        val columns = columnMetadataFor(child, names)
        val fkCol = columns.firstOrNull { it.name == "owner_id" }

        assertNotNull(fkCol, "Should find owner_id column")
        val refs = assertNotNull(fkCol.references, "Explicit-field edge should produce FK references")
        assertEquals("parents", refs.first, "Should reference parents table")
        assertEquals(OnDelete.CASCADE, fkCol.onDelete, "Should carry CASCADE from edge")
    }

    @Test
    fun `unique propagates through explicit field edges`() {
        val parent = object : EntSchema() {
            override fun id() = EntId.long()
            override fun fields() = fields { string("name") }
        }
        val child = object : EntSchema() {
            override fun fields() = fields {
                string("name")
                long("owner_id")
            }
            override fun edges() = edges {
                belongsTo("owner", parent).unique().field("owner_id")
            }
        }
        val names = mapOf(parent to "Parent", child to "Child")
        val columns = columnMetadataFor(child, names)
        val fkCol = columns.firstOrNull { it.name == "owner_id" }

        assertNotNull(fkCol, "Should find owner_id column")
        assertEquals(true, fkCol.unique, "Edge .unique() should propagate to column")
    }

    // ---------- storageKey + explicit .field() ----------

    @Test
    fun `explicit field edge with storageKey resolves FK reference correctly`() {
        val parent = object : EntSchema() {
            override fun id() = EntId.long()
            override fun fields() = fields { string("name") }
        }
        val child = object : EntSchema() {
            override fun fields() = fields {
                string("name")
                long("owner_id").storageKey("owner_fk")
            }
            override fun edges() = edges {
                belongsTo("owner", parent).field("owner_id")
            }
        }
        val names = mapOf(parent to "Parent", child to "Child")
        val columns = columnMetadataFor(child, names)
        val fkCol = columns.firstOrNull { it.name == "owner_fk" }

        assertNotNull(fkCol, "Should find column by storageKey 'owner_fk'")
        val refs = assertNotNull(fkCol.references, "Field with storageKey should still get FK reference from edge")
        assertEquals("parents", refs.first)
    }

    @Test
    fun `explicit field edge with storageKey resolves join to physical column`() {
        val parent = object : EntSchema() {
            override fun id() = EntId.long()
            override fun fields() = fields { string("name") }
        }
        val child = object : EntSchema() {
            override fun fields() = fields {
                string("name")
                long("owner_id").storageKey("owner_fk")
            }
            override fun edges() = edges {
                belongsTo("owner", parent).field("owner_id")
            }
        }
        val join = resolveEdgeJoin(
            child.edges().first(),
            child,
        )
        assertNotNull(join)
        assertEquals("owner_fk", join.sourceColumn, "Join should use physical column name from storageKey")
    }

    @Test
    fun `SET_NULL rejected on non-nullable explicit field`() {
        val parent = object : EntSchema() {
            override fun id() = EntId.long()
            override fun fields() = fields { string("name") }
        }
        val child = object : EntSchema() {
            override fun fields() = fields {
                string("name")
                long("owner_id") // not optional — non-nullable
            }
            override fun edges() = edges {
                belongsTo("owner", parent).field("owner_id").onDelete(OnDelete.SET_NULL)
            }
        }
        val names = mapOf(parent to "Parent", child to "Child")
        assertFailsWith<IllegalStateException> {
            columnMetadataFor(child, names)
        }
    }

    // ---------- explicit .field() validation ----------

    @Test
    fun `explicit field edge rejected when field does not exist`() {
        val parent = object : EntSchema() {
            override fun id() = EntId.long()
            override fun fields() = fields { string("name") }
        }
        val child = object : EntSchema() {
            override fun fields() = fields {
                string("name")
            }
            override fun edges() = edges {
                belongsTo("owner", parent).field("owner_id") // no such field
            }
        }
        val names = mapOf(parent to "Parent", child to "Child")
        val ex = assertFailsWith<IllegalStateException> {
            columnMetadataFor(child, names)
        }
        assert(ex.message!!.contains("no field with that name")) {
            "Error should mention missing field\n${ex.message}"
        }
    }

    @Test
    fun `explicit field edge rejected when field type mismatches target id`() {
        val parent = object : EntSchema() {
            override fun id() = EntId.uuid()
            override fun fields() = fields { string("name") }
        }
        val child = object : EntSchema() {
            override fun fields() = fields {
                string("name")
                long("owner_id") // Long field but target uses UUID id
            }
            override fun edges() = edges {
                belongsTo("owner", parent).field("owner_id")
            }
        }
        val names = mapOf(parent to "Parent", child to "Child")
        val ex = assertFailsWith<IllegalStateException> {
            columnMetadataFor(child, names)
        }
        assert(ex.message!!.contains("type")) {
            "Error should mention type mismatch\n${ex.message}"
        }
    }

    // ---------- HasOne / HasMany cardinality validation ----------

    @Test
    fun `hasMany rejects inverse belongsTo with unique`() {
        class S {
            val parent: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("name") }
                override fun edges() = edges {
                    hasMany("children", this@S.child)
                }
            }
            val child: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("name") }
                override fun edges() = edges {
                    belongsTo("parent", this@S.parent).unique()
                }
            }
        }
        val s = S()
        val ex = assertFailsWith<IllegalStateException> {
            resolveEdgeJoin(s.parent.edges().first(), s.parent)
        }
        assert(ex.message!!.contains("hasOne")) {
            "Error should suggest using hasOne\n${ex.message}"
        }
    }

    @Test
    fun `hasOne edge requires inverse belongsTo to have unique`() {
        class S {
            val parent: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("name") }
                override fun edges() = edges {
                    hasOne("child", this@S.child)
                }
            }
            val child: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("name") }
                override fun edges() = edges {
                    belongsTo("parent", this@S.parent) // missing .unique()
                }
            }
        }
        val s = S()
        val ex = assertFailsWith<IllegalStateException> {
            resolveEdgeJoin(s.parent.edges().first(), s.parent)
        }
        assert(ex.message!!.contains("unique")) {
            "Error should mention unique requirement\n${ex.message}"
        }
    }

    @Test
    fun `hasOne edge succeeds when inverse belongsTo has unique`() {
        class S {
            val parent: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("name") }
                override fun edges() = edges {
                    hasOne("child", this@S.child)
                }
            }
            val child: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("name") }
                override fun edges() = edges {
                    belongsTo("parent", this@S.parent).unique()
                }
            }
        }
        val s = S()
        val join = resolveEdgeJoin(s.parent.edges().first(), s.parent)
        assertNotNull(join)
        assertEquals("id", join.sourceColumn)
        assertEquals("parent_id", join.targetColumn)
    }

    // ---------- HasOne eager loading ----------

    @Test
    fun `hasOne eager loading queries target by FK not source FK`() {
        class S {
            val parent: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("name") }
                override fun edges() = edges {
                    hasOne("profile", this@S.child)
                }
            }
            val child: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("bio") }
                override fun edges() = edges {
                    belongsTo("owner", this@S.parent).unique()
                }
            }
        }
        val s = S()
        val names = mapOf(s.parent to "Parent", s.child to "Profile")
        val output = QueryGenerator("com.example.ent")
            .generate("Parent", s.parent, names).toString().replace("\\s+".toRegex(), " ")

        // HasOne eager loading should query target by FK (owner_id),
        // group results by owner_id, and map to source via entity.id.
        assert(output.contains("Predicate.Leaf(\"owner_id\", Op.IN, sourceIds)")) {
            "Should query target by FK column, not source FK\n$output"
        }
        assert(output.contains("groupBy { it[\"owner_id\"] }")) {
            "Should group loaded rows by FK column\n$output"
        }
        assert(output.contains("loadedGroups[entity.id]?.firstOrNull()")) {
            "Should map source.id to grouped target, collapsing to single entity\n$output"
        }
    }

    @Test
    fun `hasOne Edges property is nullable entity not list`() {
        class S {
            val parent: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("name") }
                override fun edges() = edges {
                    hasOne("profile", this@S.child)
                }
            }
            val child: EntSchema = object : EntSchema() {
                override fun fields() = fields { string("bio") }
                override fun edges() = edges {
                    belongsTo("owner", this@S.parent).unique()
                }
            }
        }
        val s = S()
        val names = mapOf(s.parent to "Parent", s.child to "Profile")
        val output = EntityGenerator("com.example.ent")
            .generate("Parent", s.parent, names).toString()

        assert(output.contains("val profile: Profile? = null")) {
            "HasOne edge should produce nullable entity property, not a list\n$output"
        }
    }
}
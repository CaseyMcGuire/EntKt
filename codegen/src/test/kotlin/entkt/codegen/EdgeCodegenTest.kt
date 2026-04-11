package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.edges
import entkt.schema.fields
import kotlin.test.Test

object Owner : EntSchema() {
    override fun id() = EntId.long()

    override fun fields() = fields {
        string("name")
    }

    override fun edges() = edges {
        // The User-side of the Owner ↔ Pet pair: an Owner has many Pets.
        // No FK on Owner — the FK lives on Pet via the inverse `owner` edge.
        to("pets", Pet)
    }
}

object Pet : EntSchema() {
    override fun fields() = fields {
        string("name")
    }

    override fun edges() = edges {
        from("owner", Owner).ref("pets").unique()
    }
}

object RequiredPet : EntSchema() {
    override fun fields() = fields {
        string("name")
    }

    override fun edges() = edges {
        from("owner", Owner).unique().required()
    }
}

object LooseDog : EntSchema() {
    override fun fields() = fields {
        string("name")
    }

    override fun edges() = edges {
        // Non-unique: no FK on source
        to("friends", Pet)
    }
}

// ---------- M2M test schemas ----------

object Team : EntSchema() {
    override fun fields() = fields {
        string("name")
    }

    override fun edges() = edges {
        to("members", Pet).through("team_members", TeamMember)
    }
}

object TeamMember : EntSchema() {
    override fun fields() = fields {
        time("joined_at")
    }

    override fun edges() = edges {
        to("team", Team).unique().required().field("team_id")
        to("member", Pet).unique().required().field("member_id")
    }
}

private val schemaNames: Map<EntSchema, String> = mapOf(
    Owner to "Owner",
    Pet to "Pet",
    RequiredPet to "RequiredPet",
    LooseDog to "LooseDog",
    Team to "Team",
    TeamMember to "TeamMember",
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
    fun `non-unique edges do not produce a FK on the source entity`() {
        val output = EntityGenerator("com.example.ent")
            .generate("LooseDog", LooseDog, schemaNames).toString()

        assert(!output.contains("friendsId")) { "Non-unique edge should not add FK\n$output" }
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
    fun `update builder save falls back to entity value for edge FK`() {
        val output = UpdateGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("ownerId = this.ownerId ?: entity.ownerId")) {
            "Should fall back to existing entity value\n$output"
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

        assert(output.contains("\"teams\"")) {
            "Pet SCHEMA should include reverse 'teams' M2M edge\n$output"
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
        assert(output.contains("Predicate.HasEdgeWith(\"teams\", parent)")) {
            "Should reference reverse M2M edge name\n$output"
        }
        assert(output.contains("Predicate.HasEdge(\"teams\")")) {
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

        assert(output.contains("return loadEdges(results)")) {
            "all() should delegate to loadEdges\n$output"
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
}
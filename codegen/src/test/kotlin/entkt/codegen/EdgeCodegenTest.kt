package entkt.codegen

import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.OnDelete
import entkt.schema.Through
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class Owner : EntSchema("owners") {
    override fun id() = EntId.long()

    val name = string("name")

    val pets = hasMany<Pet>("pets")
}

class Pet : EntSchema("pets") {
    override fun id() = EntId.int()
    val name = string("name")

    val owner = belongsTo<Owner>("owner").inverse(Owner::pets)
}

class RequiredPet : EntSchema("required_pets") {
    override fun id() = EntId.int()
    val name = string("name")

    val owner = belongsTo<Owner>("owner").required()
}

// ---------- M2M test schemas ----------

class Team : EntSchema("teams") {
    override fun id() = EntId.int()
    val name = string("name")

    val members = manyToMany<Pet>("members").through<TeamMember>(TeamMember::team, TeamMember::member)
}

class TeamMember : EntSchema("team_members") {
    override fun id() = EntId.int()
    val joinedAt = time("joined_at")
    val teamId = int("team_id")
    val memberId = int("member_id")

    val team = belongsTo<Team>("team").required().field(teamId)
    val member = belongsTo<Pet>("member").required().field(memberId)
}

// ---------- Self-referential M2M test schemas ----------

class Person : EntSchema("persons") {
    override fun id() = EntId.int()
    val name = string("name")

    val friends = manyToMany<Person>("friends").through<Friendship>(Friendship::person, Friendship::friend)
}

class Friendship : EntSchema("friendships") {
    override fun id() = EntId.int()
    val createdAt = time("created_at")
    val personId = int("person_id")
    val friendId = int("friend_id")

    val person = belongsTo<Person>("person").required().field(personId)
    val friend = belongsTo<Person>("friend").required().field(friendId)
}

// ---------- Ambiguous junction test schemas ----------

class Project : EntSchema("projects") {
    override fun id() = EntId.int()
    val name = string("name")

    val assignees = manyToMany<Pet>("assignees").through<ProjectAssignment>(ProjectAssignment::project, ProjectAssignment::assignee)
}

class ProjectAssignment : EntSchema("project_assignments") {
    override fun id() = EntId.int()
    val assignedAt = time("assigned_at")
    val projectId = int("project_id")
    val assigneeId = int("assignee_id")
    val reviewerId = int("reviewer_id").nullable()

    val project = belongsTo<Project>("project").required().field(projectId)
    val assignee = belongsTo<Pet>("assignee").required().field(assigneeId)
    val reviewer = belongsTo<Pet>("reviewer").field(reviewerId)
}

// ---------- Test schemas for "ambiguous ref" test ----------

private class AmbigPostSchema : EntSchema("posts") {
    override fun id() = EntId.int()
    val title = string("title")
    val author = belongsTo<AmbigUserSchema>("author").inverse(AmbigUserSchema::posts)
    val editor = belongsTo<AmbigUserSchema>("editor").inverse(AmbigUserSchema::posts)
}

private class AmbigUserSchema : EntSchema("users") {
    override fun id() = EntId.int()
    val name = string("name")
    val posts = hasMany<AmbigPostSchema>("posts")
}

// ---------- Test schemas for self-ref M2M "same edge" tests ----------

private class SameEdgeJunctionSchema : EntSchema("friendships") {
    override fun id() = EntId.int()
    val personId = int("person_id")
    val friendId = int("friend_id")
    val person = belongsTo<SameEdgePersonSchema>("person").required().field(personId)
    val friend = belongsTo<SameEdgePersonSchema>("friend").required().field(friendId)
}

private class SameEdgePersonSchema : EntSchema("persons") {
    override fun id() = EntId.int()
    val name = string("name")
    val friends = manyToMany<SameEdgePersonSchema>("friends")
        .through<SameEdgeJunctionSchema>(SameEdgeJunctionSchema::person, SameEdgeJunctionSchema::person)
}

// ---------- Test schemas for onDelete / .field() tests ----------

private class FkParentSchema : EntSchema("parents") {
    override fun id() = EntId.long()
    val name = string("name")
}

private class FkChildCascadeSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val ownerId = long("owner_id")
    val owner = belongsTo<FkParentSchema>("owner").field(ownerId).onDelete(OnDelete.CASCADE)
}

private class FkChildUniqueSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val ownerId = long("owner_id")
    val owner = belongsTo<FkParentSchema>("owner").unique().field(ownerId)
}

private class FkChildSetNullSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val ownerId = long("owner_id") // non-nullable
    val owner = belongsTo<FkParentSchema>("owner").field(ownerId).onDelete(OnDelete.SET_NULL)
}

private class FkChildTypeMismatchParent : EntSchema("parents") {
    override fun id() = EntId.uuid()
    val name = string("name")
}

private class FkChildTypeMismatchSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val ownerId = long("owner_id") // Long field but target uses UUID id
    val owner = belongsTo<FkChildTypeMismatchParent>("owner").field(ownerId)
}

// ---------- HasMany / HasOne cardinality test schemas ----------

private class HasManyChildSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val parent = belongsTo<HasManyParentSchema>("parent").unique().inverse(HasManyParentSchema::children)
}

private class HasManyParentSchema : EntSchema("parents") {
    override fun id() = EntId.int()
    val name = string("name")
    val children = hasMany<HasManyChildSchema>("children")
}

private class HasOneChildNonUniqueSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val parent = belongsTo<HasOneParentNonUniqueSchema>("parent").inverse(HasOneParentNonUniqueSchema::child) // missing .unique()
}

private class HasOneParentNonUniqueSchema : EntSchema("parents") {
    override fun id() = EntId.int()
    val name = string("name")
    val child = hasOne<HasOneChildNonUniqueSchema>("child")
}

private class HasOneChildSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val parent = belongsTo<HasOneParentSchema>("parent").unique().inverse(HasOneParentSchema::child)
}

private class HasOneParentSchema : EntSchema("parents") {
    override fun id() = EntId.int()
    val name = string("name")
    val child = hasOne<HasOneChildSchema>("child")
}

// ---------- HasOne eager loading test schemas ----------

private class ProfileSchema : EntSchema("profiles") {
    override fun id() = EntId.int()
    val bio = string("bio")
    val owner = belongsTo<HasOneEagerParentSchema>("owner").unique().inverse(HasOneEagerParentSchema::profile)
}

private class HasOneEagerParentSchema : EntSchema("parents") {
    override fun id() = EntId.int()
    val name = string("name")
    val profile = hasOne<ProfileSchema>("profile")
}

private class ProfileSchema2 : EntSchema("profiles") {
    override fun id() = EntId.int()
    val bio = string("bio")
    val owner = belongsTo<HasOneEdgesParentSchema>("owner").unique().inverse(HasOneEdgesParentSchema::profile)
}

private class HasOneEdgesParentSchema : EntSchema("parents") {
    override fun id() = EntId.int()
    val name = string("name")
    val profile = hasOne<ProfileSchema2>("profile")
}

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class EdgeCodegenTest {

    private fun createAllSchemas(): Triple<
        List<EntSchema>,
        Map<EntSchema, String>,
        Map<String, EntSchema>
    > {
        val owner = Owner()
        val pet = Pet()
        val requiredPet = RequiredPet()
        val team = Team()
        val teamMember = TeamMember()
        val person = Person()
        val friendship = Friendship()
        val project = Project()
        val projectAssignment = ProjectAssignment()

        val all: List<EntSchema> = listOf(owner, pet, requiredPet, team, teamMember, person, friendship, project, projectAssignment)
        finalize(*all.toTypedArray())

        val names: Map<EntSchema, String> = mapOf(
            owner to "Owner",
            pet to "Pet",
            requiredPet to "RequiredPet",
            team to "Team",
            teamMember to "TeamMember",
            person to "Person",
            friendship to "Friendship",
            project to "Project",
            projectAssignment to "ProjectAssignment",
        )
        val byName: Map<String, EntSchema> = names.entries.associate { (k, v) -> v to k }
        return Triple(all, names, byName)
    }

    @Test
    fun `entity gets nullable FK property for optional unique edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("val ownerId: Long?")) { "Should add nullable ownerId FK\n$output" }
    }

    @Test
    fun `entity gets non-null FK property for required unique edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()

        assert(output.contains("val ownerId: Long,") || output.contains("val ownerId: Long\n")) {
            "Should add non-null ownerId FK\n$output"
        }
    }

    @Test
    fun `create builder gets id and entity properties for unique edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

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
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()

        assert(output.contains("\"owner is required\"")) {
            "Should validate required edge in save()\n$output"
        }
    }

    @Test
    fun `update builder gets id and entity properties for unique edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = UpdateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("var ownerId: Long?")) {
            "Should have ownerId: Long? property\n$output"
        }
        assert(output.contains("var owner: Owner?")) {
            "Should have owner: Owner? convenience property\n$output"
        }
    }

    @Test
    fun `update builder save uses dirty tracking for edge FK`() {
        val (_, names, byName) = createAllSchemas()
        val output = UpdateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("if (\"ownerId\" in dirtyFields) this.ownerId else entity.ownerId")) {
            "Should check dirtyFields for edge FK fallback\n$output"
        }
    }

    @Test
    fun `update builder edge FK setter tracks dirty state`() {
        val (_, names, byName) = createAllSchemas()
        val output = UpdateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("dirtyFields.add(\"ownerId\")")) {
            "Setting ownerId should add to dirtyFields\n$output"
        }
    }

    @Test
    fun `entity emits nullable column ref for optional unique edge FK`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("val ownerId: NullableComparableColumn<Long> = NullableComparableColumn<Long>(\"owner_id\")")) {
            "Should emit NullableComparableColumn<Long> for optional edge FK\n$output"
        }
    }

    @Test
    fun `entity emits non-null column ref for required unique edge FK`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()

        assert(output.contains("val ownerId: ComparableColumn<Long> = ComparableColumn<Long>(\"owner_id\")")) {
            "Should emit non-null ComparableColumn<Long> for required edge FK\n$output"
        }
    }

    // ---------- Foreign key references in generated SCHEMA ----------

    @Test
    fun `generated SCHEMA includes ForeignKeyRef for edge FK columns`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

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
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(!output.contains("ForeignKeyRef")) {
            "Owner has no FK columns -- should not emit ForeignKeyRef\n$output"
        }
    }

    // ---------- EdgeRef emission ----------

    @Test
    fun `entity emits EdgeRef on the companion for to-many edges`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("import entkt.query.EdgeRef")) {
            "Should import EdgeRef\n$output"
        }
        assert(output.contains("val pets: EdgeRef<Pet, PetQuery> = EdgeRef(\"pets\") { PetQuery(NoopDriver) }")) {
            "Should emit EdgeRef for the pets edge wired to NoopDriver\n$output"
        }
    }

    @Test
    fun `entity emits EdgeRef on the companion for from-side unique edges`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

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
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("fun queryPets(): PetQuery")) {
            "Should generate traversal queryPets()\n$output"
        }
        assert(output.contains("Predicate.HasEdgeWith(\"owner\", parent)")) {
            "Should reference the inverse edge name in HasEdgeWith\n$output"
        }
        assert(output.contains("Predicate.HasEdge(\"owner\")")) {
            "Should fall back to HasEdge when parent has no wheres\n$output"
        }
    }

    @Test
    fun `query gets traversal method on the from-side too`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("fun queryOwner(): OwnerQuery")) {
            "Should generate traversal queryOwner()\n$output"
        }
        assert(output.contains("Predicate.HasEdgeWith(\"pets\", parent)")) {
            "Should reference Owner's 'pets' edge as the inverse\n$output"
        }
    }

    @Test
    fun `does not emit traversal when the inverse edge cannot be resolved`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(!output.contains("queryRequiredPets")) {
            "Should not emit traversal when there's no matching back-edge\n$output"
        }
    }

    // NOTE: The old test "bad ref value fails at codegen time" has been removed
    // because the typed builder API's .inverse() now prevents bad ref values at
    // compile time. The ref field on BelongsTo edges is set by the framework from
    // the KProperty1 reference, so a bad ref is no longer possible.

    @Test
    fun `ambiguous ref aliases on target fail at codegen time`() {
        val user = AmbigUserSchema()
        val post = AmbigPostSchema()
        finalize(user, post)
        val names = mapOf<EntSchema, String>(user to "User", post to "Post")
        val error = assertFailsWith<IllegalStateException> {
            QueryGenerator("com.example.ent").generate("User", user, names)
        }
        assert(error.message!!.contains("ambiguous")) {
            "Error should mention ambiguity\n${error.message}"
        }
    }

    // ---------- M2M edge codegen ----------

    @Test
    fun `entity emits EdgeRef for M2M edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(output.contains("val members: EdgeRef<Pet, PetQuery> = EdgeRef(\"members\") { PetQuery(NoopDriver) }")) {
            "Should emit EdgeRef for M2M members edge\n$output"
        }
    }

    @Test
    fun `M2M edge does not produce FK on source entity`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(!output.contains("membersId")) {
            "M2M edge should not produce an FK property on the source\n$output"
        }
    }

    @Test
    fun `generated SCHEMA includes junction metadata for M2M edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(output.contains("junctionTable")) {
            "Should include junction metadata in SCHEMA\n$output"
        }
        assert(output.contains("\"team_members\"")) {
            "Junction table should be the TeamMember table name\n$output"
        }
    }

    @Test
    fun `target entity gets reverse M2M edge in SCHEMA`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("\"teams_members\"")) {
            "Pet SCHEMA should include reverse 'teams_members' M2M edge\n$output"
        }
        assert(output.contains("junctionTable = \"team_members\"")) {
            "Reverse edge should carry junction table metadata\n$output"
        }
    }

    @Test
    fun `query gets M2M traversal method`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(output.contains("fun queryMembers(): PetQuery")) {
            "Should generate M2M traversal queryMembers()\n$output"
        }
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
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("data class Edges")) {
            "Should generate inner Edges class\n$output"
        }
        assert(output.contains("val owner: Owner? = null")) {
            "To-one edge should produce nullable entity property\n$output"
        }
    }

    @Test
    fun `entity Edges class has nullable list for to-many edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("val pets: List<Pet>? = null")) {
            "To-many edge should produce nullable list property\n$output"
        }
    }

    @Test
    fun `entity Edges class has nullable list for M2M edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(output.contains("val members: List<Pet>? = null")) {
            "M2M edge should produce nullable list property\n$output"
        }
    }

    // ---------- Eager loading: with{Edge} methods ----------

    @Test
    fun `query generates withPets for to-many edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

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
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("fun withOwner(")) {
            "Should generate withOwner method\n$output"
        }
        assert(output.contains("OwnerQuery.() -> Unit")) {
            "withOwner should accept an OwnerQuery DSL block\n$output"
        }
    }

    @Test
    fun `query generates withMembers for M2M edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(output.contains("fun withMembers(")) {
            "Should generate withMembers method\n$output"
        }
    }

    @Test
    fun `query generates loadEdges for schemas with edges`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("fun loadEdges(")) {
            "Should generate loadEdges method\n$output"
        }
    }

    @Test
    fun `all() delegates to loadEdges for schemas with edges`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("loadEdges(results, privacy)")) {
            "all() should delegate to loadEdges after privacy check\n$output"
        }
    }

    @Test
    fun `to-many eager loading queries target with IN predicate on FK column`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("Predicate.Leaf(\"owner_id\", Op.IN, sourceIds)")) {
            "Should build IN predicate on the FK column\n$output"
        }
    }

    @Test
    fun `to-one eager loading queries target by id`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("Predicate.Leaf(\"id\", Op.IN, fkValues)")) {
            "Should build IN predicate on target id column\n$output"
        }
    }

    @Test
    fun `M2M eager loading queries junction table then target`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("\"team_members\"")) {
            "Should query junction table\n$output"
        }
        assert(output.contains("Predicate.Leaf(\"team_id\", Op.IN, sourceIds)")) {
            "Should query junction with source FK\n$output"
        }
    }

    // ---------- Self-referential M2M ----------

    @Test
    fun `self-referential M2M resolves distinct source and target FKs`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Person", byName["Person"]!!, names).toString()

        assert(output.contains("junctionSourceColumn = \"person_id\"")) {
            "Source FK should be person_id\n$output"
        }
        assert(output.contains("junctionTargetColumn = \"friend_id\"")) {
            "Target FK should be friend_id (not person_id again)\n$output"
        }
    }

    @Test
    fun `self-referential M2M query uses correct junction FKs`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Person", byName["Person"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("Predicate.Leaf(\"person_id\", Op.IN, sourceIds)")) {
            "Should query junction with source FK person_id\n$output"
        }
    }

    @Test
    fun `self-referential M2M with same source and target edge fails`() {
        val person = SameEdgePersonSchema()
        val junction = SameEdgeJunctionSchema()
        finalize(person, junction)
        val names = mapOf<EntSchema, String>(person to "Person", junction to "Friendship")
        val error = runCatching {
            EntityGenerator("com.example.ent").generate("Person", person, names)
        }.exceptionOrNull()
        assert(error != null) { "Should fail when sourceEdge and targetEdge resolve to same junction edge" }
        assert(error!!.message!!.contains("same junction edge")) {
            "Error should mention same junction edge: ${error.message}"
        }
    }

    // ---------- Per-group limit/offset in eager loading ----------

    @Test
    fun `to-many eager loading applies limit per group not globally`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("subQuery.orderFields, null, null)")) {
            "Batch query should not pass limit/offset to driver\n$output"
        }
        assert(output.contains("perGroupOffset") && output.contains("perGroupLimit")) {
            "Should apply limit/offset per group\n$output"
        }
    }

    @Test
    fun `M2M eager loading applies limit per group not globally`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString().replace("\\s+".toRegex(), " ")

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
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Project", byName["Project"]!!, names).toString()

        assert(output.contains("junctionSourceColumn = \"project_id\"")) {
            "Source FK should be project_id\n$output"
        }
        assert(output.contains("junctionTargetColumn = \"assignee_id\"")) {
            "Target FK should be assignee_id, not reviewer_id\n$output"
        }
    }

    @Test
    fun `ambiguous junction without hints fails fast`() {
        val (_, names, byName) = createAllSchemas()
        val pet = byName["Pet"]!!
        val projectAssignment = byName["ProjectAssignment"]!!
        val project = byName["Project"]!!
        val edge = Edge(
            name = "assignees",
            target = pet,
            kind = EdgeKind.ManyToMany(Through(projectAssignment)),
        )

        val error = assertFailsWith<IllegalStateException> {
            resolveM2MEdgeJoin(edge, project, names)
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
        val (_, names, byName) = createAllSchemas()
        val pet = byName["Pet"]!!
        val projectAssignment = byName["ProjectAssignment"]!!
        val project = byName["Project"]!!
        val edge = Edge(
            name = "assignees",
            target = pet,
            kind = EdgeKind.ManyToMany(Through(
                projectAssignment,
                sourceEdge = "assignee",
                targetEdge = "reviewer",
            )),
        )

        val error = assertFailsWith<IllegalStateException> {
            resolveM2MEdgeJoin(edge, project, names)
        }
        assert(error.message!!.contains("sourceEdge hint")) {
            "Should mention sourceEdge hint: ${error.message}"
        }
    }

    @Test
    fun `wrong targetEdge hint fails fast with clear error`() {
        val (_, names, byName) = createAllSchemas()
        val pet = byName["Pet"]!!
        val projectAssignment = byName["ProjectAssignment"]!!
        val project = byName["Project"]!!
        val edge = Edge(
            name = "assignees",
            target = pet,
            kind = EdgeKind.ManyToMany(Through(
                projectAssignment,
                sourceEdge = "project",
                targetEdge = "project",
            )),
        )

        val error = assertFailsWith<IllegalStateException> {
            resolveM2MEdgeJoin(edge, project, names)
        }
        assert(error.message!!.contains("targetEdge hint")) {
            "Should mention targetEdge hint: ${error.message}"
        }
    }

    // ---------- onDelete with explicit .field() ----------

    @Test
    fun `onDelete propagates through explicit field edges`() {
        val parent = FkParentSchema()
        val child = FkChildCascadeSchema()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "Parent", child to "Child")
        val columns = columnMetadataFor(child, names)
        val fkCol = columns.firstOrNull { it.name == "owner_id" }

        assertNotNull(fkCol, "Should find owner_id column")
        val refs = assertNotNull(fkCol.references, "Explicit-field edge should produce FK references")
        assertEquals("parents", refs.first, "Should reference parents table")
        assertEquals(OnDelete.CASCADE, fkCol.onDelete, "Should carry CASCADE from edge")
    }

    @Test
    fun `unique propagates through explicit field edges`() {
        val parent = FkParentSchema()
        val child = FkChildUniqueSchema()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "Parent", child to "Child")
        val columns = columnMetadataFor(child, names)
        val fkCol = columns.firstOrNull { it.name == "owner_id" }

        assertNotNull(fkCol, "Should find owner_id column")
        assertEquals(true, fkCol.unique, "Edge .unique() should propagate to column")
    }

    @Test
    fun `SET_NULL rejected on non-nullable explicit field`() {
        val parent = FkParentSchema()
        val child = FkChildSetNullSchema()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "Parent", child to "Child")
        assertFailsWith<IllegalStateException> {
            columnMetadataFor(child, names)
        }
    }

    // ---------- explicit .field() validation ----------

    // NOTE: The old test "explicit field edge rejected when field does not exist"
    // has been removed because the typed builder API now prevents this at compile
    // time — .field(handle) takes a FieldHandle from the same schema, so referencing
    // a nonexistent field is a compile error.

    @Test
    fun `explicit field edge rejected when field type mismatches target id`() {
        val parent = FkChildTypeMismatchParent()
        val child = FkChildTypeMismatchSchema()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "Parent", child to "Child")
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
        val parent = HasManyParentSchema()
        val child = HasManyChildSchema()
        val ex = assertFailsWith<IllegalStateException> {
            finalize(parent, child)
        }
        assert(ex.message!!.contains("hasMany")) {
            "Error should mention hasMany cardinality mismatch\n${ex.message}"
        }
    }

    @Test
    fun `hasOne edge requires inverse belongsTo to have unique`() {
        val parent = HasOneParentNonUniqueSchema()
        val child = HasOneChildNonUniqueSchema()
        val ex = assertFailsWith<IllegalStateException> {
            finalize(parent, child)
        }
        assert(ex.message!!.contains("unique")) {
            "Error should mention unique requirement\n${ex.message}"
        }
    }

    @Test
    fun `hasOne edge succeeds when inverse belongsTo has unique`() {
        val parent = HasOneParentSchema()
        val child = HasOneChildSchema()
        finalize(parent, child)
        val join = resolveEdgeJoin(parent.edges().first(), parent)
        assertNotNull(join)
        assertEquals("id", join.sourceColumn)
        assertEquals("parent_id", join.targetColumn)
    }

    // ---------- HasOne eager loading ----------

    @Test
    fun `hasOne eager loading queries target by FK not source FK`() {
        val parent = HasOneEagerParentSchema()
        val profile = ProfileSchema()
        finalize(parent, profile)
        val names = mapOf<EntSchema, String>(parent to "Parent", profile to "Profile")
        val output = QueryGenerator("com.example.ent")
            .generate("Parent", parent, names).toString().replace("\\s+".toRegex(), " ")

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
        val parent = HasOneEdgesParentSchema()
        val profile = ProfileSchema2()
        finalize(parent, profile)
        val names = mapOf<EntSchema, String>(parent to "Parent", profile to "Profile")
        val output = EntityGenerator("com.example.ent")
            .generate("Parent", parent, names).toString()

        assert(output.contains("val profile: Profile? = null")) {
            "HasOne edge should produce nullable entity property, not a list\n$output"
        }
    }
}

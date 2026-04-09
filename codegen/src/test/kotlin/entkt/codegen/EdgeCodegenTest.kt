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
}

object Pet : EntSchema() {
    override fun fields() = fields {
        string("name")
    }

    override fun edges() = edges {
        from("owner", Owner).unique()
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

private val schemaNames: Map<EntSchema, String> = mapOf(
    Owner to "Owner",
    Pet to "Pet",
    RequiredPet to "RequiredPet",
    LooseDog to "LooseDog",
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
    fun `create builder gets id and entity setters for unique edge`() {
        val output = CreateGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("fun setOwnerId(")) {
            "Should have setOwnerId(Long)\n$output"
        }
        assert(output.contains("fun setOwner(owner: Owner)")) {
            "Should have setOwner(Owner) convenience setter\n$output"
        }
        assert(output.contains("this.ownerId = owner.id")) {
            "setOwner should write owner.id to ownerId\n$output"
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
    fun `update builder gets id and entity setters for unique edge`() {
        val output = UpdateGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("fun setOwnerId(")) {
            "Should have setOwnerId(Long)\n$output"
        }
        assert(output.contains("fun setOwner(owner: Owner)")) {
            "Should have setOwner(Owner) convenience setter\n$output"
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
    fun `query builder emits FK predicates and whereHas helpers`() {
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", Pet, schemaNames).toString()

        assert(output.contains("fun whereOwnerIdEq(")) { "Should have whereOwnerIdEq\n$output" }
        assert(output.contains("fun whereOwnerIdIn(")) { "Should have whereOwnerIdIn\n$output" }
        assert(output.contains("fun whereHasOwner(): PetQuery")) {
            "Should have whereHasOwner alias\n$output"
        }
        assert(output.contains("fun whereHasNoOwner(): PetQuery")) {
            "Should have whereHasNoOwner alias\n$output"
        }
    }
}
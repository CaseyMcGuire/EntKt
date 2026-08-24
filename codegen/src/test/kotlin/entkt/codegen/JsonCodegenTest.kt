@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Plain data class: the codegen tests assert on generated TEXT, not compiled
// output, so @Serializable isn't required here (it is exercised end-to-end in
// integration-tests). Generated code references Meta.serializer() either way.
data class Meta(val nickname: String?, val tags: List<String>)

private class JsonArticle : EntSchema("articles", clientName = "jsonArticles") {
    override fun id() = EntId.long()
    val title by string("title")
    val metadata by json("metadata", Meta::class).nullable()
}

// Generic JSON shapes: the full KType must survive into the property type,
// the column ref, the fromRow cast, and the serializer expression.
private class JsonBoard : EntSchema("boards", clientName = "jsonBoards") {
    override fun id() = EntId.long()
    val rects by json<List<Meta>>("rects")
    val labels by json<Map<String, Meta>>("labels").nullable()
    val sparse by json<List<Meta?>>("sparse")
}

private class JsonArrayBoard : EntSchema("array_boards", clientName = "jsonArrayBoards") {
    override fun id() = EntId.long()
    val booleans by json<BooleanArray>("booleans")
    val bytes by json<ByteArray>("bytes")
    val chars by json<CharArray>("chars")
    val counts by json<IntArray>("counts")
    val doubles by json<DoubleArray>("doubles")
    val floats by json<FloatArray>("floats")
    val longs by json<LongArray>("longs")
    val shorts by json<ShortArray>("shorts")
    val unsignedBytes by json<UByteArray>("unsigned_bytes")
    val unsignedCounts by json<UIntArray>("unsigned_counts")
    val unsignedLongs by json<ULongArray>("unsigned_longs")
    val unsignedShorts by json<UShortArray>("unsigned_shorts")
    val labels by json<Array<String?>>("labels")
}

class JsonCodegenTest {

    private fun gen(
        schema: EntSchema,
        jsonMapper: String = entkt.runtime.driver.JsonMapperIds.KOTLINX,
    ): Map<String, String> {
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(schema::class to schema)
        schema.finalize(registry)
        return EntGenerator("com.example.ent", jsonMapper)
            .generate(listOf(SchemaInput(schema)))
            .associate { it.name to it.toString().replace("\\s+".toRegex(), " ") }
    }

    private fun gen(): Map<String, String> = gen(JsonArticle())

    private fun genGeneric(): Map<String, String> = gen(JsonBoard())

    @Test
    fun `entity exposes the supplied JSON class as the property type`() {
        val entity = gen().getValue("JsonArticle")
        assertTrue("metadata: Meta?" in entity, entity)
    }

    @Test
    fun `companion column ref is a narrow NullableJsonColumn`() {
        val entity = gen().getValue("JsonArticle")
        assertTrue("NullableJsonColumn<JsonArticle, Meta>" in entity, entity)
        assertTrue("import entkt.query.NullableJsonColumn" in entity, entity)
    }

    @Test
    fun `fromRow decodes JSON via a direct cast (driver owns decode)`() {
        val entity = gen().getValue("JsonArticle")
        assertTrue("""metadata = row["metadata"] as Meta?""" in entity, entity)
    }

    @Test
    fun `SCHEMA literal carries JsonColumnMetadata with the serializer`() {
        val entity = gen().getValue("JsonArticle")
        assertTrue(
            """json = JsonColumnMetadata(klass = Meta::class, kType = typeOf<Meta>(), typeName = "entkt.codegen.Meta", mapper = JsonMapperIds.KOTLINX, kotlinxSerializer = Meta.serializer())""" in entity,
            entity,
        )
    }

    @Test
    fun `a non-generic JSON field does not suppress unchecked casts`() {
        val entity = gen().getValue("JsonArticle")
        assertFalse("UNCHECKED_CAST" in entity, entity)
    }

    @Test
    fun `create preparation detaches typed JSON before the write map and candidate`() {
        val create = gen().getValue("JsonArticleRepo")
        assertTrue(
            """val _entktPreparedMetadata = driver.copyJsonValue(JsonArticle.TABLE, "metadata", _entktValueMetadata)""" in create,
            create,
        )
        assertTrue(""""metadata" to _entktPreparedMetadata,""" in create, create)
        assertTrue("metadata = _entktPreparedMetadata" in create, create)
    }

    @Test
    fun `lifecycle value and patch snapshots copy JSON through the driver`() {
        val repo = gen().getValue("JsonArticleRepo")
        assertTrue(
            """metadata = driver.copyJsonValue(JsonArticle.TABLE, "metadata", item.metadata)""" in repo,
            repo,
        )
        assertTrue(
            """FieldPatch.Set(driver.copyJsonValue(JsonArticle.TABLE, "metadata", entry.value))""" in repo,
            repo,
        )
        assertFalse("copyJsonValue(JsonArticle.TABLE, \"metadata\", item.metadata) as Meta?" in repo, repo)

        val update = gen().getValue("JsonArticleUpdate")
        assertTrue("val snapshot = JsonArticleUpdatePatch(" in update, update)
        assertTrue("metadata = when (val entry = snapshot.metadata)" in update, update)
        assertTrue(
            """driver.copyJsonValue(JsonArticle.TABLE, "metadata",""" in update,
            update,
        )
        assertTrue("val beforeSnapshot = entity.copy(" in update, update)
        assertTrue(
            """metadata = driver.copyJsonValue(JsonArticle.TABLE, "metadata", entity.metadata)""" in update,
            update,
        )
    }

    // ── Generic JSON types ─────────────────────────────────────────

    @Test
    fun `entity exposes the full parameterized type, not a raw class`() {
        val entity = genGeneric().getValue("JsonBoard")
        assertTrue("rects: List<Meta>" in entity, entity)
        assertTrue("labels: Map<String, Meta>?" in entity, entity)
    }

    @Test
    fun `generic column refs carry the parameterized type`() {
        val entity = genGeneric().getValue("JsonBoard")
        assertTrue("JsonColumn<JsonBoard, List<Meta>>" in entity, entity)
        assertTrue("NullableJsonColumn<JsonBoard, Map<String, Meta>>" in entity, entity)
    }

    @Test
    fun `fromRow casts to the parameterized type under an unchecked-cast suppression`() {
        val entity = genGeneric().getValue("JsonBoard")
        assertTrue("""rects = row["rects"] as List<Meta>""" in entity, entity)
        assertTrue("""@Suppress("UNCHECKED_CAST") public fun fromRow""" in entity, entity)
    }

    @Test
    fun `SCHEMA literal builds collection serializers from builtins factories`() {
        val entity = genGeneric().getValue("JsonBoard")
        assertTrue(
            """json = JsonColumnMetadata(klass = List::class, kType = typeOf<List<Meta>>(), typeName = "kotlin.collections.List<entkt.codegen.Meta>", mapper = JsonMapperIds.KOTLINX, kotlinxSerializer = ListSerializer(Meta.serializer()))""" in entity,
            entity,
        )
        assertTrue(
            """json = JsonColumnMetadata(klass = Map::class, kType = typeOf<Map<String, Meta>>(), typeName = "kotlin.collections.Map<kotlin.String, entkt.codegen.Meta>", mapper = JsonMapperIds.KOTLINX, kotlinxSerializer = MapSerializer(String.serializer(), Meta.serializer()))""" in entity,
            entity,
        )
        assertTrue("import kotlinx.serialization.builtins.ListSerializer" in entity, entity)
        assertTrue("import kotlinx.serialization.builtins.MapSerializer" in entity, entity)
        assertTrue("import kotlinx.serialization.builtins.serializer" in entity, entity)
    }

    @Test
    fun `a nullable type argument wraps its serializer with nullable`() {
        val entity = genGeneric().getValue("JsonBoard")
        assertTrue("kotlinxSerializer = ListSerializer(Meta.serializer().nullable)" in entity, entity)
        assertTrue("import kotlinx.serialization.builtins.nullable" in entity, entity)
    }

    @Test
    fun `generic create preparation detaches the typed value without a cast`() {
        val create = genGeneric().getValue("JsonBoardRepo")
        assertTrue(
            """val _entktPreparedRects = driver.copyJsonValue(JsonBoard.TABLE, "rects", _entktValueRects)""" in create,
            create,
        )
        assertTrue(""""rects" to _entktPreparedRects,""" in create, create)
        assertTrue("rects = _entktPreparedRects" in create, create)
        assertFalse("copyJsonValue(JsonBoard.TABLE, \"rects\", rects) as List<Meta>" in create, create)
        assertTrue("rects: List<Meta>" in create, create)
    }

    @Test
    fun `generic JSON lifecycle snapshots preserve their declared type`() {
        val repo = genGeneric().getValue("JsonBoardRepo")
        assertTrue(
            """rects = driver.copyJsonValue(JsonBoard.TABLE, "rects", item.rects)""" in repo,
            repo,
        )
        assertFalse("copyJsonValue(JsonBoard.TABLE, \"rects\", item.rects) as List<Meta>" in repo, repo)
    }

    @Test
    fun `built-in arrays use their kotlinx serializer factories`() {
        val entity = gen(JsonArrayBoard()).getValue("JsonArrayBoard")
        listOf(
            "BooleanArraySerializer",
            "ByteArraySerializer",
            "CharArraySerializer",
            "DoubleArraySerializer",
            "FloatArraySerializer",
            "IntArraySerializer",
            "LongArraySerializer",
            "ShortArraySerializer",
            "UByteArraySerializer",
            "UIntArraySerializer",
            "ULongArraySerializer",
            "UShortArraySerializer",
        ).forEach { factory ->
            assertTrue("kotlinxSerializer = $factory()" in entity, entity)
            assertTrue("import kotlinx.serialization.builtins.$factory" in entity, entity)
        }
        assertTrue(
            "kotlinxSerializer = ArraySerializer(String.serializer().nullable)" in entity,
            entity,
        )
        assertTrue("import kotlinx.serialization.builtins.ArraySerializer" in entity, entity)
    }

    @Test
    fun `reference arrays opt in within generated source`() {
        val entity = gen(JsonArrayBoard()).getValue("JsonArrayBoard")
        assertTrue("ExperimentalSerializationApi::class" in entity, entity)
        assertTrue("import kotlinx.serialization.ExperimentalSerializationApi" in entity, entity)
        assertTrue("ExperimentalUnsignedTypes::class" in entity, entity)
        assertTrue("import kotlin.ExperimentalUnsignedTypes" in entity, entity)
    }

    // ── Non-kotlinx mappers ────────────────────────────────────────

    @Test
    fun `jackson mode emits mapper-neutral metadata with no serializer references`() {
        val entity = gen(JsonBoard(), jsonMapper = entkt.runtime.driver.JsonMapperIds.JACKSON)
            .getValue("JsonBoard")
        assertTrue(
            """json = JsonColumnMetadata(klass = List::class, kType = typeOf<List<Meta>>(), typeName = "kotlin.collections.List<entkt.codegen.Meta>", mapper = JsonMapperIds.JACKSON)""" in entity,
            entity,
        )
        // The whole point: nothing in the generated file references kotlinx
        // symbols the serialization plugin would have had to generate.
        assertFalse("kotlinxSerializer" in entity, entity)
        assertFalse("kotlinx.serialization" in entity, entity)
    }

    @Test
    fun `jackson mode keeps the typed property, column ref, and cast`() {
        val entity = gen(JsonBoard(), jsonMapper = entkt.runtime.driver.JsonMapperIds.JACKSON)
            .getValue("JsonBoard")
        assertTrue("rects: List<Meta>" in entity, entity)
        assertTrue("JsonColumn<JsonBoard, List<Meta>>" in entity, entity)
        assertTrue("""rects = row["rects"] as List<Meta>""" in entity, entity)
    }

    @Test
    fun `a third-party mapper id is stamped verbatim as a string literal`() {
        val entity = gen(JsonBoard(), jsonMapper = "moshi").getValue("JsonBoard")
        assertTrue("""mapper = "moshi"""" in entity, entity)
        assertFalse("kotlinxSerializer" in entity, entity)
    }
}

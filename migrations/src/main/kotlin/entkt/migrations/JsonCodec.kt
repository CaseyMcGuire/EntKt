package entkt.migrations

/**
 * Minimal hand-rolled JSON serialization for [NormalizedSchema] snapshots.
 * Avoids pulling in a JSON library just for this one use case.
 *
 * Output is deterministically ordered: tables sorted by name, columns in
 * declaration order, indexes sorted by (columns, unique), FKs sorted by
 * column name.
 */
internal object JsonCodec {

    fun encode(schema: NormalizedSchema, parentChecksum: String? = null): String {
        val sb = StringBuilder()
        sb.append("{\n")
        if (parentChecksum != null) {
            sb.append("  \"parent\": ").append(escStr(parentChecksum)).append(",\n")
        } else {
            sb.append("  \"parent\": null,\n")
        }
        sb.append("  \"tables\": {\n")
        val sortedTables = schema.tables.values.sortedBy { it.name }
        for ((ti, table) in sortedTables.withIndex()) {
            sb.append("    ").append(escStr(table.name)).append(": {\n")
            encodeTable(sb, table, indent = "      ")
            sb.append("    }")
            if (ti < sortedTables.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  }\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun encodeTable(sb: StringBuilder, table: NormalizedTable, indent: String) {
        // columns (declaration order preserved)
        sb.append(indent).append("\"columns\": [\n")
        for ((ci, col) in table.columns.withIndex()) {
            sb.append(indent).append("  {")
            sb.append("\"name\": ").append(escStr(col.name))
            sb.append(", \"sqlType\": ").append(escStr(col.sqlType))
            sb.append(", \"nullable\": ").append(col.nullable)
            sb.append(", \"primaryKey\": ").append(col.primaryKey)
            sb.append("}")
            if (ci < table.columns.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append(indent).append("],\n")

        // indexes (sorted by columns then unique)
        val sortedIndexes = table.indexes.sortedWith(
            compareBy<NormalizedIndex> { it.columns.joinToString(",") }
                .thenBy { it.unique },
        )
        sb.append(indent).append("\"indexes\": [\n")
        for ((ii, idx) in sortedIndexes.withIndex()) {
            sb.append(indent).append("  {")
            sb.append("\"columns\": [").append(idx.columns.joinToString(", ") { escStr(it) }).append("]")
            sb.append(", \"unique\": ").append(idx.unique)
            if (idx.storageKey != null) {
                sb.append(", \"storageKey\": ").append(escStr(idx.storageKey))
            }
            sb.append("}")
            if (ii < sortedIndexes.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append(indent).append("],\n")

        // foreignKeys (sorted by column)
        val sortedFks = table.foreignKeys.sortedBy { it.column }
        sb.append(indent).append("\"foreignKeys\": [\n")
        for ((fi, fk) in sortedFks.withIndex()) {
            sb.append(indent).append("  {")
            sb.append("\"column\": ").append(escStr(fk.column))
            sb.append(", \"targetTable\": ").append(escStr(fk.targetTable))
            sb.append(", \"targetColumn\": ").append(escStr(fk.targetColumn))
            sb.append(", \"columnNullable\": ").append(fk.columnNullable)
            if (fk.constraintName != null) {
                sb.append(", \"constraintName\": ").append(escStr(fk.constraintName))
            }
            sb.append("}")
            if (fi < sortedFks.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append(indent).append("]\n")
    }

    private fun escStr(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    // ---- Decoding ----

    fun decode(json: String): NormalizedSchema {
        val parser = JsonParser(json)
        return parser.parseSchema().second
    }

    fun decodeParent(json: String): String? {
        val parser = JsonParser(json)
        return parser.parseSchema().first
    }

    /**
     * Minimal recursive-descent JSON parser. Only handles the subset
     * needed for NormalizedSchema snapshots.
     */
    private class JsonParser(private val input: String) {
        private var pos = 0

        fun parseSchema(): Pair<String?, NormalizedSchema> {
            skipWs()
            expect('{')
            val tables = mutableMapOf<String, NormalizedTable>()
            var parent: String? = null
            skipWs()
            while (peek() != '}') {
                val key = readString()
                skipWs(); expect(':'); skipWs()
                when (key) {
                    "parent" -> parent = readNullableString()
                    "tables" -> {
                        expect('{')
                        skipWs()
                        if (peek() != '}') {
                            while (true) {
                                skipWs()
                                val tableName = readString()
                                skipWs(); expect(':'); skipWs()
                                val table = parseTable(tableName)
                                tables[tableName] = table
                                skipWs()
                                if (peek() == ',') { advance(); continue }
                                break
                            }
                        }
                        skipWs(); expect('}')
                    }
                    else -> skipValue()
                }
                skipWs()
                if (peek() == ',') { advance(); skipWs() }
            }
            expect('}')
            return parent to NormalizedSchema(tables)
        }

        private fun parseTable(name: String): NormalizedTable {
            expect('{')
            var columns = emptyList<NormalizedColumn>()
            var indexes = emptyList<NormalizedIndex>()
            var foreignKeys = emptyList<NormalizedForeignKey>()
            skipWs()
            while (peek() != '}') {
                val key = readString()
                skipWs(); expect(':'); skipWs()
                when (key) {
                    "columns" -> columns = parseArray { parseColumn() }
                    "indexes" -> indexes = parseArray { parseIndex() }
                    "foreignKeys" -> foreignKeys = parseArray { parseForeignKey() }
                    else -> skipValue()
                }
                skipWs()
                if (peek() == ',') { advance(); skipWs() }
            }
            expect('}')
            return NormalizedTable(name, columns, indexes, foreignKeys)
        }

        private fun parseColumn(): NormalizedColumn {
            expect('{')
            var name = ""
            var sqlType = ""
            var nullable = false
            var primaryKey = false
            skipWs()
            while (peek() != '}') {
                val key = readString()
                skipWs(); expect(':'); skipWs()
                when (key) {
                    "name" -> name = readString()
                    "sqlType" -> sqlType = readString()
                    "nullable" -> nullable = readBool()
                    "primaryKey" -> primaryKey = readBool()
                    else -> skipValue()
                }
                skipWs()
                if (peek() == ',') { advance(); skipWs() }
            }
            expect('}')
            return NormalizedColumn(name, sqlType, nullable, primaryKey)
        }

        private fun parseIndex(): NormalizedIndex {
            expect('{')
            var columns = emptyList<String>()
            var unique = false
            var storageKey: String? = null
            skipWs()
            while (peek() != '}') {
                val key = readString()
                skipWs(); expect(':'); skipWs()
                when (key) {
                    "columns" -> columns = parseArray { readString() }
                    "unique" -> unique = readBool()
                    "storageKey" -> storageKey = readString()
                    else -> skipValue()
                }
                skipWs()
                if (peek() == ',') { advance(); skipWs() }
            }
            expect('}')
            return NormalizedIndex(columns, unique, storageKey)
        }

        private fun parseForeignKey(): NormalizedForeignKey {
            expect('{')
            var column = ""
            var targetTable = ""
            var targetColumn = ""
            var columnNullable = false
            var constraintName: String? = null
            skipWs()
            while (peek() != '}') {
                val key = readString()
                skipWs(); expect(':'); skipWs()
                when (key) {
                    "column" -> column = readString()
                    "targetTable" -> targetTable = readString()
                    "targetColumn" -> targetColumn = readString()
                    "columnNullable" -> columnNullable = readBool()
                    "constraintName" -> constraintName = readString()
                    else -> skipValue()
                }
                skipWs()
                if (peek() == ',') { advance(); skipWs() }
            }
            expect('}')
            return NormalizedForeignKey(column, targetTable, targetColumn, columnNullable, constraintName)
        }

        private fun <T> parseArray(parseItem: () -> T): List<T> {
            expect('[')
            val items = mutableListOf<T>()
            skipWs()
            if (peek() != ']') {
                while (true) {
                    skipWs()
                    items.add(parseItem())
                    skipWs()
                    if (peek() == ',') { advance(); continue }
                    break
                }
            }
            skipWs(); expect(']')
            return items
        }

        private fun readString(): String {
            skipWs()
            expect('"')
            val sb = StringBuilder()
            while (peek() != '"') {
                val c = advance()
                if (c == '\\') {
                    sb.append(advance())
                } else {
                    sb.append(c)
                }
            }
            expect('"')
            return sb.toString()
        }

        private fun readNullableString(): String? {
            skipWs()
            return if (input.startsWith("null", pos)) {
                pos += 4; null
            } else {
                readString()
            }
        }

        private fun readBool(): Boolean {
            skipWs()
            return if (input.startsWith("true", pos)) {
                pos += 4; true
            } else if (input.startsWith("false", pos)) {
                pos += 5; false
            } else {
                error("Expected boolean at pos $pos")
            }
        }

        private fun skipValue() {
            skipWs()
            when (peek()) {
                '"' -> readString()
                '{' -> { advance(); var depth = 1; while (depth > 0) { val c = advance(); if (c == '{') depth++; if (c == '}') depth-- } }
                '[' -> { advance(); var depth = 1; while (depth > 0) { val c = advance(); if (c == '[') depth++; if (c == ']') depth-- } }
                else -> { while (pos < input.length && peek() !in ",]}") advance() }
            }
        }

        private fun skipWs() {
            while (pos < input.length && input[pos] in " \t\n\r") pos++
        }

        private fun peek(): Char = input[pos]

        private fun advance(): Char = input[pos++]

        private fun expect(c: Char) {
            val actual = advance()
            check(actual == c) { "Expected '$c' but got '$actual' at pos ${pos - 1}" }
        }
    }
}

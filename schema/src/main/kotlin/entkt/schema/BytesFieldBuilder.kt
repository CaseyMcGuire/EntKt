package entkt.schema

import entkt.types.Bytes

class BytesFieldBuilder internal constructor(name: String) : FieldBuilder<BytesFieldBuilder, Bytes>(name, FieldType.BYTES) {
    fun unique(): BytesFieldBuilder = apply { setUnique() }
}

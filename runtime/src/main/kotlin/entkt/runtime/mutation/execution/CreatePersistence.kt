package entkt.runtime.mutation.execution

/** Preserve scalar and bulk driver acknowledgement contracts within the shared create lifecycle. */
internal enum class CreatePersistence {
    One,
    Many,
}

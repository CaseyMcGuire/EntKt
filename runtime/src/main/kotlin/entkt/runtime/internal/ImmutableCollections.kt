package entkt.runtime.internal

import java.util.Collections

/** Return a detached list that cannot be mutated through a runtime cast. */
internal fun <T> immutableListCopy(source: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))

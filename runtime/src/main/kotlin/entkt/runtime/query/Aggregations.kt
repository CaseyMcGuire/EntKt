package entkt.runtime.query

import entkt.runtime.driver.DatabaseDriver

/**
 * The aggregate function computed by [DatabaseDriver.aggregate]. V1 supports a single
 * metric per call.
 */
enum class AggregateFunction { COUNT, SUM, AVG, MIN, MAX }

/**
 * One row returned by [DatabaseDriver.aggregate]. [key] is the group-key value (null
 * when the query is ungrouped, or for the single NULL-key bucket of a nullable
 * group column); [value] is the metric — already decoded to its Kotlin type
 * (`Long` for COUNT and integral SUM, `Double` for floating SUM / AVG, the
 * column's own type for MIN / MAX), or null per SQL NULL. Enum group keys come
 * back as their stored `String`; a typed adapter can decode them through the
 * column's metadata.
 */
data class AggregateResultRow(val key: Any?, val value: Any?)

/**
 * A typed bucket for adapters built over [DatabaseDriver.aggregate]. [key] is
 * the group value and [value] the metric.
 */
data class AggregateBucket<K, V>(val key: K, val value: V)

package entkt.gradle

import org.gradle.api.provider.Property

interface EntktExtension {
    val packageName: Property<String>
}
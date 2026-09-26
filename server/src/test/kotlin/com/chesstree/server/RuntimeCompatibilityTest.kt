package com.chesstree.server

import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeCompatibilityTest {
    @Test
    fun serverDependenciesTargetJava17() {
        listOf(
            "/com/chesstree/multiplayer/contract/ErrorResponse.class",
            "/com/chesstree/game/domain/BoardCoordinate.class",
        ).forEach { classResource ->
            val majorVersion = classFileMajorVersion(classResource)
            assertTrue(
                majorVersion <= JAVA_17_CLASS_FILE_VERSION,
                "$classResource requires class-file version $majorVersion, " +
                        "but the production server runs on Java 17 ($JAVA_17_CLASS_FILE_VERSION)",
            )
        }
    }

    private fun classFileMajorVersion(resourceName: String): Int {
        val stream = checkNotNull(javaClass.getResourceAsStream(resourceName)) {
            "Missing class resource $resourceName"
        }
        return DataInputStream(stream).use { input ->
            assertEquals(CLASS_FILE_MAGIC, input.readInt())
            input.readUnsignedShort()
            input.readUnsignedShort()
        }
    }

    private companion object {
        const val CLASS_FILE_MAGIC = 0xCAFEBABE.toInt()
        const val JAVA_17_CLASS_FILE_VERSION = 61
    }
}

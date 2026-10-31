package io.github.hamzabhai.jhatpat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Guards on what the manifest promises. It is read as a file, so no Android is needed. */
class ManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private val manifest: Element by lazy {
        val file = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
    }

    private fun elements(tag: String): List<Element> =
        (0 until manifest.getElementsByTagName(tag).length).map { manifest.getElementsByTagName(tag).item(it) as Element }

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    @Test
    fun requestsOnlyTheDocumentedPermissions() {
        val requested = elements("uses-permission").map { it.attr("name").removePrefix("android.permission.") }.toSet()
        assertEquals(
            setOf(
                "INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE",
                "ACCESS_FINE_LOCATION", "NEARBY_WIFI_DEVICES", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE",
                "POST_NOTIFICATIONS", "WAKE_LOCK", "WRITE_EXTERNAL_STORAGE",
            ),
            requested,
        )
    }

    @Test
    fun legacyAndLocationPermissionsAreLimitedToTheVersionsThatNeedThem() {
        val byName = elements("uses-permission").associateBy { it.attr("name").removePrefix("android.permission.") }
        assertEquals("28", byName.getValue("WRITE_EXTERNAL_STORAGE").attr("maxSdkVersion"))
        assertEquals("32", byName.getValue("ACCESS_FINE_LOCATION").attr("maxSdkVersion"))
        assertEquals("neverForLocation", byName.getValue("NEARBY_WIFI_DEVICES").attr("usesPermissionFlags"))
    }

    @Test
    fun neverAsksForAllFilesAccess() {
        assertFalse(elements("uses-permission").any { it.attr("name").endsWith("MANAGE_EXTERNAL_STORAGE") })
    }

    @Test
    fun theShareScreenHandlesRotationItselfSoASendIsNotCancelled() {
        val share = elements("activity").first { it.attr("name") == ".share.ShareReceiverActivity" }
        val changes = share.attr("configChanges").split("|").toSet()
        assertTrue(changes.toString(), changes.containsAll(setOf("orientation", "screenSize", "keyboardHidden")))
    }

    @Test
    fun theSharingServiceIsPrivateAndAForegroundService() {
        val service = elements("service").first { it.attr("name") == ".service.SharingService" }
        assertEquals("false", service.attr("exported"))
        assertEquals("specialUse", service.attr("foregroundServiceType"))
    }

    @Test
    fun cleartextIsOnlyAllowedForTheLoopbackServer() {
        val file = listOf("src/main/res/xml/network_security_config.xml", "app/src/main/res/xml/network_security_config.xml").map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val base = doc.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        val domains = (0 until doc.getElementsByTagName("domain").length).map { doc.getElementsByTagName("domain").item(it).textContent }
        assertEquals(listOf("127.0.0.1", "localhost"), domains)
    }
}

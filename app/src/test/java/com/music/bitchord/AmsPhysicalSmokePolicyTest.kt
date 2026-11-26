package com.music.bitchord

import com.music.bitchord.data.providers.*
import org.junit.Assert.*
import org.junit.Test

class AmsPhysicalSmokePolicyTest {
    @Test fun onlyExplicitIsolatedDebugPackageCanExposeAms() {
        val packageId = "com.dev.bitchord.ams.smoke"
        assertEquals(ProviderReadiness.READY,amsReadinessForBuild(true,true,packageId))
        for (debug in listOf(false,true)) for (optIn in listOf(false,true)) {
            for (id in listOf("com.music.bitchord","com.dev.bitchord","com.dev.bitchord.ams.smoke.benchmark")) {
                assertEquals(ProviderReadiness.BLOCKED,amsReadinessForBuild(debug,optIn,id))
            }
        }
        assertEquals(ProviderReadiness.BLOCKED,amsReadinessForBuild(false,true,packageId))
        assertEquals(ProviderReadiness.BLOCKED,amsReadinessForBuild(true,false,packageId))
    }

    @Test fun smokeReadinessDoesNotAuthorizeAnyImplicitChoice() {
        val expected = amsReadinessForBuild(BuildConfig.DEBUG,BuildConfig.AMS_PHYSICAL_SMOKE,
            BuildConfig.APPLICATION_ID)
        val inventory = ProviderCapabilityRegistry.inventory()
        val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
        val registered = inventory.filter { it.key == key }
        val caps = setOf(ProviderCapability.CATALOG_SEARCH,
            ProviderCapability.EDITORIAL_DISCOGRAPHY,ProviderCapability.CHART_MOST_PLAYED)
        assertEquals(caps,registered.map { it.capability }.toSet())
        assertTrue(registered.all { it.readiness == expected })
        val descriptors = ProviderCapabilityRegistry.descriptorsFrom(inventory)
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(descriptors))
        for (cap in caps) assertNull(gate.open(cap,key))
        gate.replace(ProviderCapabilityPolicy(descriptors,choices=mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(key,true,0)),
        )))
        if (expected == ProviderReadiness.READY)
            assertNotNull(gate.open(ProviderCapability.CATALOG_SEARCH,key))
        else assertNull(gate.open(ProviderCapability.CATALOG_SEARCH,key))
        assertNull(gate.open(ProviderCapability.EDITORIAL_DISCOGRAPHY,key))
        assertNull(gate.open(ProviderCapability.CHART_MOST_PLAYED,key))
    }
}

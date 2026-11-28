package com.music.aria.acquisition.a0
import org.junit.Assert.*
import org.junit.Test
class A0SearchTicketRegistryTest {
    @Test fun correlateOnlyValidatedResponsesToTheirQueryOrdinal() {
        val tickets=A0SearchTicketRegistry(81)
        assertTrue(tickets.register(82))
        tickets.creditValidated(81)
        tickets.creditValidated(82)
        tickets.creditValidated(82)
        tickets.creditValidated(999999)
        assertEquals(listOf(1,2,0),tickets.responseCounts())
        assertTrue(tickets.register(83))
        tickets.creditValidated(83)
        assertEquals(listOf(1,2,1),tickets.responseCounts())
        assertFalse(tickets.register(84))
        assertFalse(tickets.toString().contains("999999"))
    }
    @Test fun liveTicketSetAcceptsNewFallbackWithoutAcceptingUnknownTokens() {
        val tickets=A0SearchTicketRegistry(27182)
        val accepted=tickets.accepted
        assertEquals(setOf(27182),accepted.toSet())
        assertTrue(tickets.register(27183))
        assertTrue(27183 in accepted)
        assertFalse(27184 in accepted)
        tickets.creditValidated(27183)
        tickets.creditValidated(27184)
        assertEquals(listOf(0,1,0),tickets.responseCounts())
        assertTrue(tickets.register(27185))
        tickets.creditValidated(27185)
        assertEquals(listOf(0,1,1),tickets.responseCounts())
        assertFalse(tickets.register(27186))
    }

    @Test fun publicFileCountsArePerTicketEvenForZeroFileReplies() {
        val tickets=A0SearchTicketRegistry(100)
        tickets.creditValidated(100,publicFiles=3)
        assertTrue(tickets.register(101))
        tickets.creditValidated(101,publicFiles=0)
        tickets.creditValidated(101,publicFiles=9)
        tickets.creditValidated(102,publicFiles=20) // Unregistered ticket
        assertTrue(tickets.register(102))
        tickets.creditValidated(102,publicFiles=2)
        assertEquals(listOf(1,2,1),tickets.responseCounts())
        assertEquals(listOf(3,9,2),tickets.publicFileCounts())
    }
    
    @Test fun onlyOriginalAndTwoRegisteredVariantsArePermitted() {
        val tickets=A0SearchTicketRegistry(81)
        assertFalse(tickets.register(81))
        assertFalse(tickets.register(0))
        assertTrue(tickets.register(82))
        assertTrue(tickets.register(83))
        assertFalse(tickets.register(84))
        assertEquals(setOf(81,82,83),tickets.accepted.toSet())
    }
    @Test fun recentTicketHistoryIsBoundedAndDoesNotAuthorizeOldReplies() {
        val history=A0RecentSearchTickets(3)
        listOf(81,82,83,84).forEach(history::record)
        assertFalse(history.wasSent(81))
        assertTrue(history.wasSent(82))
        assertFalse(history.wasSent(999))
        val active=A0SearchTicketRegistry(85)
        assertFalse(82 in active.accepted)
        active.creditValidated(82)
        assertEquals(listOf(0,0,0),active.responseCounts())
        assertFalse(history.toString().contains("82"))
    }

}

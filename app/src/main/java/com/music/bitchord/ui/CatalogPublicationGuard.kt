package com.music.bitchord.ui

import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderRequestTicket

/**
 * Publication is the last capability gate, separate from HTTP admission.
 * Revocation, query changes and source visibility are checked together.
 * It applies equally to any provider/instance and never resolves identity.
 */
internal fun catalogPublicationAllowed(
    gate: ProviderPolicyGate?,
    ticket: ProviderRequestTicket?,
    correctSource: Boolean,
    correctQuery: Boolean,
    visibleProvider: Boolean,
): Boolean =
    correctSource && correctQuery && visibleProvider &&
        ticket != null && gate?.accept(ticket) == true

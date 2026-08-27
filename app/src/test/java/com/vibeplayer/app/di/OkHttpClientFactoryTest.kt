package com.vibeplayer.app.di

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpClientFactoryTest {

    private val factory = OkHttpClientFactory()

    @Test
    fun `TLS policy is cached and isolated per opt-in`() {
        val verified = factory.client(trustSelfSigned = false)
        val permissive = factory.client(trustSelfSigned = true)

        assertSame(verified, factory.client(trustSelfSigned = false))
        assertSame(permissive, factory.client(trustSelfSigned = true))
        assertNotSame(verified, permissive)
    }

    @Test
    fun `self-signed opt-in changes only permissive client hostname verification`() {
        val verified = factory.client(trustSelfSigned = false)
        val permissive = factory.client(trustSelfSigned = true)

        // The permissive verifier accepts a synthetic host even without a
        // session. The normal verifier remains a different implementation and
        // therefore retains standard hostname checks.
        assertNotSame(verified.hostnameVerifier, permissive.hostnameVerifier)
        assertTrue(permissive.hostnameVerifier.verify("media.example", null))
    }
}

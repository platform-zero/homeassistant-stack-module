package org.webservices.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.webservices.testrunner.framework.*


suspend fun TestRunner.homeAssistantTests() = suite("Home Assistant Tests") {
    test("Home Assistant web interface loads") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}")
        requireOkOrRedirectResponse(response, "Home Assistant web interface")

        println("      ✓ Home Assistant web interface accessible")
    }

    test("Home Assistant API requires authentication") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/")
        requireAuthBoundary(response, "Home Assistant API")

        println("      ✓ Home Assistant API endpoint responds")
    }

    test("Home Assistant config endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/config")
        requireAuthBoundary(response, "Home Assistant config endpoint")

        println("      ✓ Home Assistant config API exists")
    }

    test("Home Assistant states endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/states")
        requireAuthBoundary(response, "Home Assistant states endpoint")

        println("      ✓ Home Assistant states API exists")
    }

    test("Home Assistant services endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/services")
        requireAuthBoundary(response, "Home Assistant services endpoint")

        println("      ✓ Home Assistant services API exists")
    }

    test("Home Assistant events endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/events")
        requireAuthBoundary(response, "Home Assistant events endpoint")

        println("      ✓ Home Assistant events API exists")
    }

    test("Home Assistant error log endpoint") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/error_log")
        requireAuthBoundary(response, "Home Assistant error log endpoint")

        println("      ✓ Home Assistant error log API exists")
    }

    test("Home Assistant history endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/history/period")
        requireAuthBoundary(response, "Home Assistant history endpoint")
        println("      ✓ Home Assistant history API exists")
    }

    test("Home Assistant logbook endpoint exists") {
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/api/logbook")
        requireAuthBoundary(response, "Home Assistant logbook endpoint")
        println("      ✓ Home Assistant logbook API exists")
    }

    test("Home Assistant panel manifest") {
        
        val response = client.getRawResponse("${env.endpoints.homeassistant!!}/static/icons/favicon.ico")
        response.status shouldBe HttpStatusCode.OK
        require(response.bodyAsText().isNotEmpty()) { "Home Assistant favicon response was empty" }

        println("      ✓ Home Assistant static assets configured")
    }
}

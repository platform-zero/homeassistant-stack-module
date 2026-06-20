package org.webservices.testrunner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeAssistantAuthConfigTest {
    private val retiredDirectoryId = "ld" + "ap"
    private val retiredDirectoryEnvPrefix = retiredDirectoryId.uppercase() + "_"

    @Test
    fun `home assistant exposes keycloak edge auth through trusted frontend flow`() {
        val configuration = repoFileText("stack.config/homeassistant/configuration.yaml")
        val compose = repoFileText("stack.compose/homeassistant.yml")
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")
        val domainToken = "{${'$'}DOMAIN}"
        val directBlock = siteBlock(caddyfile, "direct.homeassistant.$domainToken, direct.home.$domainToken")
        val apiBlock = siteBlock(caddyfile, "api.homeassistant.$domainToken, api.home.$domainToken")

        assertTrue(configuration.contains("- type: trusted_networks"))
        assertTrue(configuration.contains("name: Keycloak"))
        assertTrue(configuration.contains("- type: homeassistant"))
        assertTrue(compose.contains("./configs/homeassistant/auth_keycloak.py:/usr/src/homeassistant/homeassistant/auth/providers/trusted_networks.py:ro"))

        assertFalse(configuration.contains("allow_bypass_login"))
        assertFalse(configuration.contains("${retiredDirectoryId}_"))
        assertFalse(compose.contains(retiredDirectoryEnvPrefix))
        assertFalse(compose.contains("$retiredDirectoryId:"))
        assertTrue(compose.contains("TRUSTED_PROXY_NETWORKS: 172.16.0.0/12"))
        assertTrue(compose.contains("HOMEASSISTANT_TRUSTED_PROXY_SECRET: \${HOMEASSISTANT_TRUSTED_PROXY_SECRET}"))
        assertTrue(caddyfile.contains("header_up X-Trusted-Proxy-Secret {\$HOMEASSISTANT_TRUSTED_PROXY_SECRET}"))

        assertTrue(directBlock.contains("reverse_proxy homeassistant:8123"))
        assertTrue(directBlock.contains("header_up -Remote-User"))
        assertTrue(directBlock.contains("header_up -X-Remote-User"))
        assertTrue(directBlock.contains("header_up -X-Forwarded-User"))
        assertTrue(directBlock.contains("header_up -X-Trusted-Proxy-Secret"))
        assertFalse(directBlock.contains("keycloak_auth"))

        assertTrue(apiBlock.contains("header_up -X-Remote-User"))
        assertTrue(apiBlock.contains("header_up -X-Forwarded-User"))
        assertTrue(apiBlock.contains("header_up -X-Trusted-Proxy-Secret"))
    }

    @Test
    fun `home assistant keycloak provider canonicalizes usernames and trusts only edge headers`() {
        val provider = repoFileText("stack.config/homeassistant/auth_keycloak.py")

        assertTrue(provider.contains("unicodedata.normalize(\"NFKC\", username).strip().casefold()"))
        assertTrue(provider.contains("USERNAME_PATTERN.fullmatch(canonical_username)"))
        assertTrue(provider.contains("current_request.get(None)"))
        assertTrue(provider.contains("trusted_remote_user_header"))
        assertTrue(provider.contains("Missing trusted edge identity"))
        assertTrue(provider.contains("async_validate_trusted_header_login"))
        assertTrue(provider.contains("@AUTH_PROVIDERS.register(\"trusted_networks\")"))
        assertTrue(provider.contains("os.getenv(\"TRUSTED_PROXY_NETWORKS\", \"172.16.0.0/12\")"))
        assertTrue(provider.contains("HOMEASSISTANT_TRUSTED_PROXY_SECRET"))
        assertTrue(provider.contains("Invalid trusted proxy secret"))
        assertTrue(provider.contains("Missing trusted proxy secret configuration"))
        assertTrue(provider.contains("if \"user\" in flow_result:"))
        assertTrue(provider.contains("await self.store.async_link_user(selected_user, credential)"))
        assertTrue(provider.contains("user is not None and user.is_active"))
        assertTrue(provider.contains("Ignoring inactive Home Assistant credential link"))

        assertFalse(provider.contains("@AUTH_PROVIDERS.register(\"$retiredDirectoryId\")"))
        assertFalse(provider.contains("${retiredDirectoryId}3"))
        assertFalse(provider.contains("async_validate_login"))
    }

    @Test
    fun `home assistant bootstrap relinks stack admin credentials to active user`() {
        val initScript = repoFileText("stack.config/homeassistant/init-homeassistant.sh")

        assertTrue(initScript.contains("credential[\"user_id\"] = keep.get(\"id\")"))
        assertTrue(initScript.contains("candidate_admin_users"))
        assertTrue(initScript.contains("Linked Home Assistant stack-admin credentials to active user"))
    }

    private fun repoFileText(relativePath: String): String =
        Files.readString(repoRoot().resolve(relativePath))

    private fun siteBlock(caddyfile: String, siteLabel: String): String {
        val start = caddyfile.indexOf(siteLabel)
        require(start >= 0) { "Missing Caddy site block $siteLabel" }
        val blockOpen = caddyfile.indexOf("{", start + siteLabel.length)
        require(blockOpen >= 0) { "Missing Caddy site block open brace for $siteLabel" }
        var depth = 0
        var inBlock = false
        for (index in blockOpen until caddyfile.length) {
            when (caddyfile[index]) {
                '{' -> {
                    depth += 1
                    inBlock = true
                }
                '}' -> {
                    depth -= 1
                    if (inBlock && depth == 0) return caddyfile.substring(start, index + 1)
                }
            }
        }
        error("Unterminated Caddy site block $siteLabel")
    }

    private fun repoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("MODULE.bazel"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}

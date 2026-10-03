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
        val runtime = repoFileText("stack.runtime.yaml")
        val caddyfile = repoFileText("stack.config/caddy/Caddyfile")
        val domainToken = "{${'$'}DOMAIN}"
        val nativeBlock = siteBlock(caddyfile, "home-native.$domainToken")
        val webBlock = siteBlock(caddyfile, "homeassistant.$domainToken, home.$domainToken")

        assertTrue(configuration.contains("- type: trusted_networks"))
        assertTrue(configuration.contains("name: Keycloak"))
        assertTrue(configuration.contains("- type: homeassistant"))
        assertTrue(configuration.contains("use_x_forwarded_for: true"))
        assertTrue(configuration.contains("- 10.89.0.0/16"))
        assertTrue(runtime.contains("./configs/homeassistant/auth_keycloak.py:/usr/src/homeassistant/homeassistant/auth/providers/trusted_networks.py:ro"))

        assertFalse(configuration.contains("allow_bypass_login"))
        assertFalse(configuration.contains("${retiredDirectoryId}_"))
        assertFalse(runtime.contains(retiredDirectoryEnvPrefix))
        assertFalse(runtime.contains("$retiredDirectoryId:"))
        assertTrue(runtime.contains("TRUSTED_PROXY_NETWORKS: 10.89.0.0/16"))
        assertTrue(runtime.contains("HOMEASSISTANT_TRUSTED_PROXY_SECRET: \${HOMEASSISTANT_TRUSTED_PROXY_SECRET}"))
        assertTrue(caddyfile.contains("header_up X-Trusted-Proxy-Secret {\$HOMEASSISTANT_TRUSTED_PROXY_SECRET}"))
        assertTrue(caddyfile.contains("header_up X-Auth-Request-Redirect {scheme}://{host}{orig_uri}"))
        assertTrue(caddyfile.contains("rewrite * /oauth2/start"))
        assertFalse(caddyfile.contains("/oauth2/start?rd={scheme}://{host}{uri}"))

        assertTrue(nativeBlock.contains("reverse_proxy homeassistant:8123"))
        assertTrue(nativeBlock.contains("header_up -Remote-User"))
        assertTrue(nativeBlock.contains("header_up -X-Remote-User"))
        assertTrue(nativeBlock.contains("header_up -X-Forwarded-User"))
        assertTrue(nativeBlock.contains("header_up -X-Trusted-Proxy-Secret"))
        assertFalse(nativeBlock.contains("keycloak_auth"))

        assertTrue(webBlock.contains("@mobile_app header_regexp User-Agent"))
        assertTrue(webBlock.contains("@external_auth query external_auth=1"))
        assertTrue(webBlock.contains("import keycloak_group_allow homeassistant admins|operators|users"))
        assertTrue(webBlock.contains("header_up -X-Remote-User"))
        assertTrue(webBlock.contains("header_up -X-Forwarded-User"))
        assertTrue(webBlock.contains("header_up -X-Trusted-Proxy-Secret"))
    }

    @Test
    fun `home assistant keycloak provider canonicalizes usernames and trusts only edge headers`() {
        val provider = repoFileText("stack.config/homeassistant/auth_keycloak.py")

        assertTrue(provider.contains("unicodedata.normalize(\"NFKC\", username).strip().casefold()"))
        assertTrue(provider.contains("USERNAME_PATTERN.fullmatch(canonical_username)"))
        assertTrue(provider.contains("current_request.get(None)"))
        assertTrue(provider.contains("trusted_remote_user_header"))
        assertTrue(provider.contains("if not username:"))
        assertTrue(provider.contains("async_validate_trusted_header_login"))
        assertTrue(provider.contains("@AUTH_PROVIDERS.register(\"trusted_networks\")"))
        assertTrue(provider.contains("os.getenv(\"TRUSTED_PROXY_NETWORKS\", \"10.89.0.0/16\")"))
        assertTrue(provider.contains("HOMEASSISTANT_TRUSTED_PROXY_SECRET"))
        assertTrue(provider.contains("if not TRUSTED_PROXY_SECRET:"))
        assertTrue(provider.contains("hmac.compare_digest("))
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
        TestSourceFiles.moduleText("homeassistant", relativePath)

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

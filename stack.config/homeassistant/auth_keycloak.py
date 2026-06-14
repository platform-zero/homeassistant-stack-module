"""Keycloak edge-auth provider for Home Assistant.

The edge already authenticates browser users with Keycloak and forwards trusted
identity headers only from Caddy. This provider converts those headers into Home
Assistant credentials while the native Home Assistant auth provider remains
available for direct device access.
"""

from __future__ import annotations

from collections.abc import Mapping
import hmac
from ipaddress import ip_address, ip_network
import logging
import os
import re
from typing import Any
import unicodedata

import voluptuous as vol

from homeassistant.auth.auth_store import AuthStore
from homeassistant.auth.models import AuthFlowContext, AuthFlowResult, Credentials, UserMeta
from homeassistant.components.http import current_request
from homeassistant.const import CONF_TYPE
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError

from . import AUTH_PROVIDER_SCHEMA, AUTH_PROVIDERS, AuthProvider, LoginFlow

_LOGGER = logging.getLogger(__name__)

CONF_TRUSTED_REMOTE_USER_HEADER = "trusted_remote_user_header"
CONF_TRUSTED_REMOTE_NAME_HEADER = "trusted_remote_name_header"
CONF_TRUSTED_REMOTE_EMAIL_HEADER = "trusted_remote_email_header"
CONF_TRUSTED_PROXY_SECRET_HEADER = "X-Trusted-Proxy-Secret"
TRUSTED_PROXY_SECRET = os.getenv("HOMEASSISTANT_TRUSTED_PROXY_SECRET", "").strip()
TRUSTED_PROXY_NETWORKS = [
    ip_network(value.strip())
    for value in os.getenv("TRUSTED_PROXY_NETWORKS", "172.16.0.0/12").split(",")
    if value.strip()
]
USERNAME_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")

CONFIG_SCHEMA = AUTH_PROVIDER_SCHEMA.extend(
    {
        vol.Optional(CONF_TRUSTED_REMOTE_USER_HEADER, default=os.getenv("TRUSTED_REMOTE_USER_HEADER", "Remote-User")): str,
        vol.Optional(CONF_TRUSTED_REMOTE_NAME_HEADER, default=os.getenv("TRUSTED_REMOTE_NAME_HEADER", "Remote-Name")): str,
        vol.Optional(CONF_TRUSTED_REMOTE_EMAIL_HEADER, default=os.getenv("TRUSTED_REMOTE_EMAIL_HEADER", "Remote-Email")): str,
    },
    extra=vol.PREVENT_EXTRA,
)


class InvalidAuthError(HomeAssistantError):
    """Raised when trusted edge identity is missing or invalid."""


class InvalidUserError(HomeAssistantError):
    """Raised when trusted-network user selection is invalid."""


def _canonicalize_username(username: str) -> str:
    """Normalize Keycloak username claims to Home Assistant credential keys."""
    canonical_username = unicodedata.normalize("NFKC", username).strip().casefold()
    if not USERNAME_PATTERN.fullmatch(canonical_username):
        raise InvalidAuthError("Invalid username")
    return canonical_username


@AUTH_PROVIDERS.register("trusted_networks")
class KeycloakTrustedAuthProvider(AuthProvider):
    """Auth provider accepting trusted Keycloak edge identities."""

    DEFAULT_TITLE = "Keycloak"

    def __init__(
        self, hass: HomeAssistant, store: AuthStore, config: dict[str, Any]
    ) -> None:
        super().__init__(hass, store, config)
        self.trusted_remote_user_header = config[CONF_TRUSTED_REMOTE_USER_HEADER]
        self.trusted_remote_name_header = config[CONF_TRUSTED_REMOTE_NAME_HEADER]
        self.trusted_remote_email_header = config[CONF_TRUSTED_REMOTE_EMAIL_HEADER]
        self._user_meta: dict[str, dict[str, str]] = {}

    async def async_login_flow(
        self, context: AuthFlowContext | None
    ) -> "KeycloakTrustedLoginFlow":
        """Return a login flow for trusted proxy SSO."""
        trusted_username = await self.async_validate_trusted_header_login()
        return KeycloakTrustedLoginFlow(self, trusted_username)

    def async_validate_access(self, ip_addr: Any) -> None:
        """Expose a trusted_networks-compatible access check for the HA frontend."""
        address = ip_address(str(ip_addr))
        if not any(address in network for network in TRUSTED_PROXY_NETWORKS):
            raise InvalidAuthError("Not in trusted proxy networks")
        request = current_request.get(None)
        if request is None:
            raise InvalidAuthError("Missing trusted proxy request")
        if not TRUSTED_PROXY_SECRET:
            raise InvalidAuthError("Missing trusted proxy secret configuration")
        if not hmac.compare_digest(
            request.headers.get(CONF_TRUSTED_PROXY_SECRET_HEADER, ""),
            TRUSTED_PROXY_SECRET,
        ):
            raise InvalidAuthError("Invalid trusted proxy secret")
        username = request.headers.get(
            self.trusted_remote_user_header
        ) or request.headers.get("X-Remote-User")
        if not username:
            raise InvalidAuthError("Missing trusted edge identity")
        _canonicalize_username(username)

    async def async_validate_trusted_header_login(self) -> str | None:
        """Validate SSO username passed by a trusted reverse proxy header."""
        request = current_request.get(None)
        if request is None:
            return None

        if not TRUSTED_PROXY_SECRET:
            return None
        if not hmac.compare_digest(
            request.headers.get(CONF_TRUSTED_PROXY_SECRET_HEADER, ""),
            TRUSTED_PROXY_SECRET,
        ):
            return None

        username = request.headers.get(self.trusted_remote_user_header) or request.headers.get("X-Remote-User")
        if not username:
            return None

        canonical_username = _canonicalize_username(username)
        display_name = (
            request.headers.get(self.trusted_remote_name_header)
            or request.headers.get("X-Remote-Name")
            or canonical_username
        ).strip()
        email = (
            request.headers.get(self.trusted_remote_email_header)
            or request.headers.get("X-Remote-Email")
            or ""
        ).strip()
        self._user_meta[canonical_username] = {
            "name": display_name or canonical_username,
            "email": email,
        }
        return canonical_username

    async def async_get_or_create_credentials(
        self, flow_result: Mapping[str, str]
    ) -> Credentials:
        """Return active-user credentials or create them for first login."""
        if "user" in flow_result:
            user_id = flow_result["user"]
            users = await self.store.async_get_users()
            selected_user = next(
                (
                    user
                    for user in users
                    if user.id == user_id
                    and user.is_active
                    and not user.system_generated
                ),
                None,
            )
            if selected_user is None:
                raise InvalidUserError

            for credential in await self.async_credentials():
                if credential.data.get("user_id") == user_id:
                    return credential

            credential = self.async_create_credentials({"user_id": user_id})
            await self.store.async_link_user(selected_user, credential)
            return credential

        username = flow_result["username"]
        for credential in await self.async_credentials():
            if credential.data.get("username") == username:
                user = await self.hass.auth.async_get_user_by_credentials(credential)
                if user is not None and user.is_active:
                    return credential
                _LOGGER.warning(
                    "Ignoring inactive Home Assistant credential link for %s",
                    username,
                )

        return self.async_create_credentials({"username": username})

    async def async_user_meta_for_credentials(
        self, credentials: Credentials
    ) -> UserMeta:
        """Provide display metadata for newly-created users."""
        if "user_id" in credentials.data:
            return UserMeta(name=None, is_active=True)
        username = credentials.data["username"]
        name = self._user_meta.get(username, {}).get("name", username)
        return UserMeta(name=name, is_active=True)

    @property
    def type(self) -> str:
        """Provider type registry key."""
        return self.config.get(CONF_TYPE, "trusted_networks")

    @property
    def support_mfa(self) -> bool:
        """MFA is enforced upstream by Keycloak."""
        return False


class KeycloakTrustedLoginFlow(LoginFlow[KeycloakTrustedAuthProvider]):
    """Login flow for Keycloak edge-auth headers."""

    def __init__(self, auth_provider: KeycloakTrustedAuthProvider, trusted_username: str | None = None) -> None:
        super().__init__(auth_provider)
        self._trusted_username = trusted_username

    async def async_step_init(
        self, user_input: dict[str, str] | None = None
    ) -> AuthFlowResult:
        """Complete login only when Caddy supplied a trusted Keycloak identity."""
        if self._trusted_username:
            return await self.async_finish({"username": self._trusted_username})

        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema({}),
            errors={"base": "invalid_auth"},
        )

import { test, expect } from '@playwright/test';
import {
  authenticatedSessionState,
  testForwardAuthService,
  waitForHomeAssistantShell,
} from '../shared/forward-auth';
import { serviceUrl } from '../../../utils/stack-urls';

test.use({ storageState: authenticatedSessionState });

  test('Home Assistant - mobile OAuth uses native auth without crossing browser cookie stores', async ({ browser }) => {
    const context = await browser.newContext({
      storageState: { cookies: [], origins: [] },
      userAgent: 'Home Assistant/2026.6.5 (Android 17)',
    });
    const mobileAuthorizationUrl = serviceUrl(
      'homeassistant',
      '/auth/authorize?response_type=code&client_id=https%3A%2F%2Fhome-assistant.io%2Fandroid&redirect_uri=homeassistant%3A%2F%2Fauth-callback&state=mobile-redirect-contract'
    );

    try {
      const response = await context.request.get(mobileAuthorizationUrl, { maxRedirects: 0 });
      expect([200, 302, 303]).toContain(response.status());
      const location = response.headers().location;
      if (location) {
        expect(new URL(location, mobileAuthorizationUrl).origin)
          .not.toBe(new URL(serviceUrl('keycloak')).origin);
      }
    } finally {
      await context.close();
    }
  });

  test('Home Assistant - Access with forward auth', async ({ page }) => {
    test.setTimeout(120000);
    await testForwardAuthService(
      page,
      'Home Assistant',
      serviceUrl('homeassistant'),
      /Overview|Developer Tools|History|Logbook|Automations|Devices|Areas|Integrations|Energy|Settings|Map|Media/i,
      {
        requireUI: true,
        disallowPatterns: [
          /Home Assistant\s+Login/i,
          /Trusted Networks/i,
          /select a user/i,
          /please select a user/i,
          /^start over$/im,
          /forgot password\?/i,
          /keep me logged in/i,
          /^log in$/im,
        ],
        onAfterLoad: async (page) => {
          const precheckText = ((await page.textContent('body').catch(() => '')) || '').toLowerCase();
          if (precheckText.includes('403') && precheckText.includes('forbidden')) {
            throw new Error('Home Assistant returned 403 after Keycloak SSO instead of an authenticated dashboard');
          }
          await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
          await expect(page).not.toHaveURL(/\/auth\/(authorize|login_flow)/i);
          await expect(page).not.toHaveURL(/\/auth\/login/i);
          expect(await page.locator('input[name="username"]').first().isVisible().catch(() => false)).toBe(false);
          await waitForHomeAssistantShell(page);
          await expect(page.getByText(/^Overview$/i).first()).toBeVisible({ timeout: 30000 });
          await expect(page.getByText(/^Developer tools$/i).first()).toBeVisible({ timeout: 30000 });
          await expect(page.getByText(/^Settings$/i).first()).toBeVisible({ timeout: 30000 });
        },
      }
    );
  });

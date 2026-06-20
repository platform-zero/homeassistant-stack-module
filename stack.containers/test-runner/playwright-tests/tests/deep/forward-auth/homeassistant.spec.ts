import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import {
  authenticatedSessionState,
  domain,
  requireStackAdminCredentials,
  screenshotRoot,
  seafileOnlyOfficeFixturePath,
  testForwardAuthService,
  waitForGrafanaShell,
  waitForHomeAssistantShell,
} from '../shared/forward-auth';
import { serviceUrl } from '../../../utils/stack-urls';
import { logPageTelemetry, setupNetworkLogging } from '../../../utils/telemetry';

test.use({ storageState: authenticatedSessionState });

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
          expect(await page.locator('input[name="username"]').first().isVisible().catch(() => false)).toBeFalsy();
          await waitForHomeAssistantShell(page);
          await expect(page.getByText(/^Overview$/i).first()).toBeVisible({ timeout: 30000 });
          await expect(page.getByText(/^Developer tools$/i).first()).toBeVisible({ timeout: 30000 });
          await expect(page.getByText(/^Settings$/i).first()).toBeVisible({ timeout: 30000 });
        },
      }
    );
  });

import { defineConfig, devices } from '@playwright/test'

const PORT = process.env.E2E_PORT ?? '8188'

/**
 * BROWSER TESTS AGAINST THE REAL STACK.
 *
 * The two worst bugs in this port were invisible to everything except a browser: a page that typed
 * the payload with an envelope the endpoint does not send and threw on mount, and a zone that served
 * the dashboard it was replacing. Both compiled. Both prerendered. Both returned 200.
 *
 * So `webServer` boots the actual Java dashboard over a fixture record and serves the actual static
 * export — the same two things the container runs. A mocked API would not have caught either.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['list']] : 'list',
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'node e2e/serve.mjs',
    url: `http://127.0.0.1:${PORT}/api/health`,
    // NEVER REUSE. The default is to reuse a server already on the port when not in CI, and that
    // turned this suite into a check that passes on both the right answer and the wrong one: a
    // deliberately broken build was verified against a server still running the previous one, and
    // nine of ten tests stayed green. Starting fresh costs about a second and is the difference
    // between testing the code and testing whatever was left running.
    reuseExistingServer: false,
    timeout: 60_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
})

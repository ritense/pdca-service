import { defineConfig } from 'vitest/config';

// The tests run in a timezone that is not UTC, so date code that mixes up
// local time and UTC shows up as a day off.
const TZ = 'Europe/Amsterdam';
process.env.TZ = TZ;

export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/setupTests.ts'],
    env: { TZ },
    restoreMocks: true,
    unstubGlobals: true,
  },
});

import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
    // The Play HTML fixtures are large; keep default timeouts generous.
    testTimeout: 30_000,
  },
});

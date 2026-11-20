import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Unmounts what a test rendered (automatic only with vitest globals, which are off).
afterEach(() => cleanup());

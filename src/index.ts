import { registerPlugin } from '@capacitor/core';

import type { NFCScannerPlugin } from './definitions';

const NFCScanner = registerPlugin<NFCScannerPlugin>('NFCScanner', {
  web: () => import('./web').then((m) => new m.NFCScannerWeb()),
});

export * from './definitions';
export { NFCScanner };

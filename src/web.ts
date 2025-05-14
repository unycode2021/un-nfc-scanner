import { WebPlugin } from '@capacitor/core';

import type { NFCScannerPlugin } from './definitions';

export class NFCScannerWeb extends WebPlugin implements NFCScannerPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}

import { WebPlugin } from '@capacitor/core';

import type { NFCScannerPlugin, NFCTagData } from './definitions';

// Define the NDEFMessage type based on the Web NFC API specification
interface NDEFMessage {
  records: NDEFRecord[];
}

interface NDEFRecord {
  recordType: string;
  mediaType?: string;
  id?: string;
  data?: ArrayBuffer;
  encoding?: string;
  lang?: string;
}

export class NFCScannerWeb extends WebPlugin implements NFCScannerPlugin {
 
  constructor() {
    super();
  }

  async isEnabled(): Promise<{ enabled: boolean }> {
    // Web NFC API check (not widely supported)
    const nfcSupported = 'NDEFReader' in window;
    return { enabled: nfcSupported };
  }

  async startScan(): Promise<void> {
    if (!('NDEFReader' in window)) {
      throw new Error('Web NFC is not supported in this browser');
    }
    
    try {
      // @ts-ignore - NDEFReader may not be recognized in TypeScript
      const ndef = new NDEFReader();
      await ndef.scan();
      
      ndef.addEventListener("reading", ({ message, serialNumber }: { message: NDEFMessage; serialNumber: string }) => {
        // Convert Web NFC message format to our plugin's format
        const ndefMessages = [{
          records: message.records.map(record => {
            // Convert each record to our expected format
            let payload = '';
            let textContent = undefined;
            let uri = undefined;
            
            // Try to extract text content from the record
            if (record.recordType === 'text') {
              try {
                const decoder = new TextDecoder(record.encoding || 'utf-8');
                if (record.data) {
                  textContent = decoder.decode(record.data);
                  payload = Array.from(new Uint8Array(record.data))
                    .map(b => b.toString(16).padStart(2, '0').toUpperCase())
                    .join('');
                }
              } catch (e) {
                console.error('Error decoding text record:', e);
              }
            }
            
            // Handle URL records
            if (record.recordType === 'url') {
              try {
                const decoder = new TextDecoder('utf-8');
                if (record.data) {
                  uri = decoder.decode(record.data);
                  payload = Array.from(new Uint8Array(record.data))
                    .map(b => b.toString(16).padStart(2, '0').toUpperCase())
                    .join('');
                }
              } catch (e) {
                console.error('Error decoding URL record:', e);
              }
            }
            
            return {
              tnf: 1, // Default to TNF_WELL_KNOWN for web
              type: record.recordType,
              id: record.id || '',
              payload,
              textContent,
              languageCode: record.lang,
              uri
            };
          })
        }];
        
        const tagData: NFCTagData = {
          id: serialNumber,
          serialNumber,
          techTypes: ['NDEF'], // Web NFC only supports NDEF
          data: {
            tagId: serialNumber,
            discoveryAction: 'webNDEF', // Web doesn't have the same discovery actions as Android
            type: 'NDEF',
            manufacturer: 'Unknown', // Web NFC doesn't provide manufacturer info
            ndefMessages
          }
        };
        
        this.notifyListeners('tagDetected', tagData);
      });
    } catch (error) {
      throw new Error(`Error starting NFC scan: ${error}`);
    }
  }

  async stopScan(): Promise<void> {
    // Web NFC doesn't have an explicit stop method
    console.warn('stopScan not fully implemented on web');
    return;
  }

  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }

  async getLaunchIntent(): Promise<NFCTagData | null> {
    console.warn('getLaunchIntent is not supported on web');
    return null;
  }

  async clearLaunchIntent(): Promise<void> {
    console.warn('clearLaunchIntent is not supported on web');
    return;
  }

  async writeTag(_options: { uri: string; payload: string; lock?: boolean }): Promise<{ tagId: string; written: boolean; locked: boolean }> {
    throw new Error('NFC tag writing is only supported on Android');
  }

  async cancelWrite(): Promise<void> {
    return;
  }
}

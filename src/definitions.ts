import { PluginListenerHandle } from "@capacitor/core";

export interface NFCScannerPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;

  /**
   * Check if NFC is available and enabled
   */
  isEnabled(): Promise<{ enabled: boolean }>;
  
  /**
   * Start scanning for NFC tags
   */
  startScan(): Promise<void>;
  
  /**
   * Stop scanning for NFC tags
   */
  stopScan(): Promise<void>;
  
  /**
   * Add listener for NFC tag detection
   */
  addListener(
    eventName: 'tagDetected',
    listenerFunc: (data: NFCTagData) => void
  ): Promise<PluginListenerHandle>;
  
  /**
   * Remove listeners for NFC events
   */
  removeAllListeners(): Promise<void>;

  /**
   * Checks if the app was launched via NFC and returns the tag data
   */
  getLaunchIntent(): Promise<NFCTagData | null>;
  
  /**
   * Clears the saved launch intent data
   */
  clearLaunchIntent(): Promise<void>;

  /**
   * Write Domino tag (URI + JSON payload) to NFC tag. Keeps call alive until user taps tag.
   * For NTAG215 (~540 bytes) or NTAG213 (~144 bytes). Optionally lock tag after write.
   */
  writeDominoTag(options: {
    uri: string;
    jsonPayload: string;
    lock?: boolean;
  }): Promise<{ tagId: string; written: boolean; locked: boolean; tagData?: NFCTagData }>;

  /**
   * Cancel a pending write operation
   */
  cancelWrite(): Promise<void>;
}

export interface NFCTagData {
  id: string;
  techTypes?: string[];
  type?: string;
  data?: {
    tagId: string;
    discoveryAction: string;
    manufacturer?: string;
    type?: string;
    techDetails?: {
      mifareClassic?: any;
      mifareUltralight?: any;
      isoDep?: any;
      nfcA?: any;
      // other tech types
    };
    ndefMessages?: Array<{
      records: Array<{
        tnf: number;
        type: string;
        id: string;
        payload: string;
        textContent?: string;
        languageCode?: string;
        uri?: string;
        mimeType?: string;
        mimeContent?: string;
        jsonContent?: string;
      }>
    }>;
  };
  serialNumber?: string;
}

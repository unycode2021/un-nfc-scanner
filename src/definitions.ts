export interface NFCScannerPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}

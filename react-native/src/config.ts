/** Configuration for the WebEngage Digia CEP plugin. */
export interface WebEngagePluginConfig {
    /** When true, verbose diagnostic logs are emitted. Defaults to false. */
    readonly diagnosticsEnabled: boolean;
}

export const defaultConfig: WebEngagePluginConfig = {
    diagnosticsEnabled: false,
};

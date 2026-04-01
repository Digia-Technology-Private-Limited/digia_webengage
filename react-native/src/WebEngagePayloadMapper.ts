import type { InAppPayload } from './types';
import { SuppressionMode, defaultConfig } from './config';
import type { WebEngagePluginConfig } from './config';

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Maps WebEngage callback payloads to Digia [InAppPayload]s.
 *
 * Mirrors the logic in the Android `WebEngagePayloadMapper` and the Flutter
 * `WebEngagePayloadMapper` exactly:
 *
 * 1. Extract a Digia contract from the raw data (top-level keys → `<digia>`
 *    HTML attribute tag → `<script digia-payload>` tag).
 * 2. If `type == "inline"`, build an inline payload (requires `placementKey`
 *    and `viewId`).
 * 3. If `command` is present, build a nudge payload.
 * 4. Otherwise fall back to a forced-dialog payload when
 *    `suppressionMode == SUPPRESS_ALL` and `forcedDialogComponentId` is set.
 */
export class WebEngagePayloadMapper {
    private readonly _config: WebEngagePluginConfig;

    constructor(config: Partial<WebEngagePluginConfig> = {}) {
        this._config = { ...defaultConfig, ...config };
    }

    /**
     * Maps a WebEngage in-app notification data map (with injected
     * `experimentId`) to a list of Digia [InAppPayload]s.
     */
    map(data: Record<string, unknown>): InAppPayload[] {
        const normalized = this._normalizeWithDigiaContract(data);

        const campaignId = str(
            normalized['experimentId'] ??
            normalized['id'] ??
            normalized['campaignId'],
        );
        if (!campaignId) return [];

        const screenId =
            str(normalized['screenId'] ?? normalized['screen_id']) ?? '*';
        const args = normalizeArgs(normalized['args']);
        const payloads: InAppPayload[] = [];

        const type = str(normalized['type'])?.toLowerCase();
        const command = normalizeCommand(str(normalized['command']));
        const viewId = str(normalized['viewId']) ?? '';
        const variationId = str(normalized['variationId']) ?? '';

        if (type === 'inline') {
            const placementKey = str(normalized['placementKey']) ?? '';
            if (placementKey && viewId) {
                payloads.push({
                    id: campaignId,
                    content: {
                        type: 'inline',
                        screenId,
                        placementKey,
                        componentId: viewId,
                        args,
                    },
                    cepContext: {
                        experimentId: campaignId,
                        campaignId,
                        ...(variationId && { variationId }),
                    },
                });
            }
        } else if (command && viewId) {
            payloads.push({
                id: campaignId,
                content: {
                    command,
                    viewId,
                    screenId,
                    args,
                },
                cepContext: { experimentId: campaignId },
            });
        } else {
            const forcedId = this._config.forcedDialogComponentId?.trim() ?? '';
            if (
                this._config.suppressionMode === SuppressionMode.SUPPRESS_ALL &&
                forcedId
            ) {
                payloads.push(buildForcedDialogPayload(campaignId, forcedId));
            }
        }

        return payloads;
    }

    // ─── Contract extraction ────────────────────────────────────────────────────

    private _normalizeWithDigiaContract(
        raw: Record<string, unknown>,
    ): Record<string, unknown> {
        const topLevel = parseContractFromMap(raw, 'top_level');
        if (topLevel) return { ...raw, ...topLevel };

        const htmlContract = extractContractFromHtml(raw);
        if (htmlContract) return { ...raw, ...htmlContract };

        return raw;
    }
}

// ─── Contract extraction helpers (module-private) ────────────────────────────

function extractContractFromHtml(
    raw: Record<string, unknown>,
): Record<string, unknown> | null {
    const candidates = collectTextCandidates(raw);

    // Source 1: <digia> attribute-based tag (matches Android HtmlDigiaTagContractSource)
    const digiaTagRe = /<digia\b([^/]*)\/?>/gi;
    for (const text of candidates) {
        for (const match of text.matchAll(digiaTagRe)) {
            const attrs = parseHtmlAttributes(match[1] ?? '');
            if (Object.keys(attrs).length === 0) continue;
            const contract = parseContractFromMap(attrs, 'html_digia_tag');
            if (contract) return contract;
        }
    }

    // Source 2: <script digia-payload> tag (legacy)
    const scriptRe = /<script[^>]*digia-payload[^>]*>([\s\S]*?)<\/script>/gi;
    for (const text of candidates) {
        for (const match of text.matchAll(scriptRe)) {
            const body = htmlUnescape((match[1] ?? '').trim());
            if (!body) continue;
            const decoded = tryDecodeJsonObject(body);
            if (!decoded) continue;
            const contract = parseContractFromMap(decoded, 'html_script');
            if (contract) return contract;
        }
    }

    return null;
}

function parseContractFromMap(
    raw: Record<string, unknown>,
    source: string,
): Record<string, unknown> | null {
    const type = str(raw['type'])?.toLowerCase();
    const resolvedCommand = type === 'inline' ? null : normalizeCommand(str(raw['command']));

    // At least one routing signal must exist
    if (!type && !resolvedCommand) return null;

    const viewId = str(raw['viewId']);
    if (!viewId) return null;

    const placementKey = str(raw['placementKey']);

    // Inline requires placementKey
    if (type === 'inline' && !placementKey) return null;

    const screenId = str(raw['screenId'] ?? (raw['screen_id']));

    const contract: Record<string, unknown> = {
        ...(type && { type }),
        ...(resolvedCommand && { command: resolvedCommand }),
        viewId,
        args: normalizeArgs(raw['args']),
        digiaContractSource: source,
        ...(placementKey && { placementKey }),
        ...(screenId && { screenId }),
    };
    return contract;
}

function parseHtmlAttributes(
    attrsString: string,
): Record<string, unknown> {
    const result: Record<string, unknown> = {};
    // Matches: key="value" | key='value' | key=value | standalone key
    const attrRe = /(\w[\w-]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|(\S+)))?/g;
    for (const match of attrsString.matchAll(attrRe)) {
        const key = match[1];
        const value = match[2] ?? match[3] ?? match[4] ?? '';
        if (key) result[key] = htmlUnescape(value);
    }
    return result;
}

// ─── Payload builders ─────────────────────────────────────────────────────────

function buildForcedDialogPayload(
    campaignId: string,
    componentId: string,
): InAppPayload {
    return {
        id: `${campaignId}:forced_dialog`,
        content: {
            command: 'SHOW_DIALOG',
            viewId: componentId,
            screenId: '*',
            args: {},
        },
        cepContext: { experimentId: campaignId },
    };
}

// ─── Utilities ────────────────────────────────────────────────────────────────

function str(value: unknown): string | null {
    if (value == null) return null;
    const s = String(value).trim();
    return s.length > 0 ? s : null;
}

function normalizeCommand(value: string | null): string | null {
    const upper = value?.toUpperCase();
    if (upper === 'SHOW_DIALOG' || upper === 'SHOW_BOTTOM_SHEET') return upper;
    return null;
}

function normalizeArgs(value: unknown): Record<string, unknown> {
    if (value == null) return {};
    if (typeof value === 'object' && !Array.isArray(value)) {
        return value as Record<string, unknown>;
    }
    if (typeof value === 'string') {
        return tryDecodeJsonObject(value) ?? {};
    }
    return {};
}

function collectTextCandidates(node: unknown): string[] {
    const results: string[] = [];
    const queue: unknown[] = [node];
    while (queue.length > 0) {
        const current = queue.shift();
        if (typeof current === 'string') {
            if (current.includes('<')) results.push(current);
        } else if (current != null && typeof current === 'object') {
            if (Array.isArray(current)) {
                queue.push(...current);
            } else {
                queue.push(...Object.values(current as Record<string, unknown>));
            }
        }
    }
    return results;
}

function tryDecodeJsonObject(
    value: string,
): Record<string, unknown> | null {
    try {
        const decoded: unknown = JSON.parse(value);
        if (
            decoded != null &&
            typeof decoded === 'object' &&
            !Array.isArray(decoded)
        ) {
            return decoded as Record<string, unknown>;
        }
        return null;
    } catch {
        return null;
    }
}

function htmlUnescape(value: string): string {
    return value
        .replace(/&quot;/g, '"')
        .replace(/&#34;/g, '"')
        .replace(/&apos;/g, "'")
        .replace(/&#39;/g, "'")
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&amp;/g, '&');
}

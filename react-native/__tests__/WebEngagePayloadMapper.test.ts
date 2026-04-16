import { WebEngagePayloadMapper } from '../src/WebEngagePayloadMapper';

// ─── Default mapper (SUPPRESS_DIGIA_ONLY, no forced dialog) ──────────────────
const mapper = new WebEngagePayloadMapper();

// ─── Helpers ─────────────────────────────────────────────────────────────────

const nudgeData = (overrides: Record<string, unknown> = {}) => ({
    experimentId: 'exp-1',
    command: 'SHOW_DIALOG',
    viewId: 'welcome_modal',
    screenId: 'home',
    args: { title: 'Hello' },
    ...overrides,
});

const inlineData = (overrides: Record<string, unknown> = {}) => ({
    experimentId: 'exp-5',
    type: 'inline',
    viewId: 'hero_component',
    placementKey: 'hero_banner',
    screenId: 'home',
    args: { title: 'Flash Sale' },
    variationId: 'var-5',
    ...overrides,
});

// ─── Basic routing ────────────────────────────────────────────────────────────

describe('WebEngagePayloadMapper — basic routing', () => {
    it('returns empty list when campaign id missing', () => {
        const result = mapper.map({ command: 'SHOW_DIALOG', viewId: 'welcome_modal' });
        expect(result).toEqual([]);
    });

    it('returns empty list when command missing', () => {
        const result = mapper.map({ experimentId: 'exp-1', screenId: 'home' });
        expect(result).toEqual([]);
    });

    it('returns empty list when viewId missing', () => {
        const result = mapper.map({ experimentId: 'exp-1', command: 'SHOW_DIALOG' });
        expect(result).toEqual([]);
    });

    it('returns empty list for unknown command', () => {
        const result = mapper.map({
            experimentId: 'exp-1',
            command: 'SHOW_BANNER',
            viewId: 'my_view',
        });
        expect(result).toEqual([]);
    });

    it('returns empty list when only placement/component keys present', () => {
        const result = mapper.map({
            experimentId: 'exp-1',
            screenId: 'home',
            hero_slot: 'hero_component',
        });
        expect(result).toEqual([]);
    });
});

// ─── Nudge payloads ───────────────────────────────────────────────────────────

describe('WebEngagePayloadMapper — nudge payloads', () => {
    it('creates SHOW_DIALOG payload from top-level keys', () => {
        const [payload] = mapper.map(nudgeData());

        expect(payload).toBeDefined();
        expect(payload.id).toBe('exp-1');
        expect(payload.content['command']).toBe('SHOW_DIALOG');
        expect(payload.content['viewId']).toBe('welcome_modal');
        expect(payload.content['screenId']).toBe('home');
        expect(payload.cepContext['experimentId']).toBe('exp-1');
    });

    it('creates SHOW_BOTTOM_SHEET payload', () => {
        const [payload] = mapper.map(
            nudgeData({ command: 'SHOW_BOTTOM_SHEET', viewId: 'sheet_view' }),
        );

        expect(payload.content['command']).toBe('SHOW_BOTTOM_SHEET');
        expect(payload.content['viewId']).toBe('sheet_view');
    });

    it('normalises command to uppercase', () => {
        const [payload] = mapper.map(
            nudgeData({ command: 'show_dialog', viewId: 'v1' }),
        );
        expect(payload.content['command']).toBe('SHOW_DIALOG');
    });

    it('defaults screenId to * when absent', () => {
        const data = { ...nudgeData() };
        delete (data as Record<string, unknown>)['screenId'];
        const [payload] = mapper.map(data);
        expect(payload.content['screenId']).toBe('*');
    });

    it('copies args object into content', () => {
        const [payload] = mapper.map(nudgeData({ args: { cta: 'Buy' } }));
        expect((payload.content['args'] as Record<string, unknown>)['cta']).toBe('Buy');
    });

    it('defaults args to empty object when absent', () => {
        const data = { ...nudgeData() };
        delete (data as Record<string, unknown>)['args'];
        const [payload] = mapper.map(data);
        expect(payload.content['args']).toEqual({});
    });

    it('uses id as fallback campaign id key', () => {
        const [payload] = mapper.map(nudgeData({ experimentId: undefined, id: 'id-99' }));
        expect(payload.id).toBe('id-99');
    });

    it('uses campaignId as fallback campaign id key', () => {
        const [payload] = mapper.map(
            nudgeData({ experimentId: undefined, campaignId: 'cmp-99' }),
        );
        expect(payload.id).toBe('cmp-99');
    });
});

// ─── Inline payloads ──────────────────────────────────────────────────────────

describe('WebEngagePayloadMapper — inline payloads', () => {
    it('creates inline payload from type=inline data', () => {
        const [payload] = mapper.map(inlineData());

        expect(payload.id).toBe('exp-5');
        expect(payload.content['type']).toBe('inline');
        expect(payload.content['placementKey']).toBe('hero_banner');
        expect(payload.content['componentId']).toBe('hero_component');
        expect(payload.content['screenId']).toBe('home');
        expect(
            (payload.content['args'] as Record<string, unknown>)['title'],
        ).toBe('Flash Sale');
        expect(payload.cepContext['experimentId']).toBe('exp-5');
        expect(payload.cepContext['variationId']).toBe('var-5');
    });

    it('drops inline payload when placementKey missing', () => {
        const result = mapper.map(inlineData({ placementKey: undefined }));
        expect(result).toEqual([]);
    });

    it('drops inline payload when viewId missing', () => {
        const result = mapper.map(inlineData({ viewId: undefined }));
        expect(result).toEqual([]);
    });

    it('type matching is case-insensitive', () => {
        const [payload] = mapper.map(inlineData({ type: 'INLINE' }));
        expect(payload.content['type']).toBe('inline');
    });
});

// ─── HTML <digia> tag contract extraction ─────────────────────────────────────

describe('WebEngagePayloadMapper — HTML contract extraction', () => {
    it('extracts contract from <digia> attribute tag in string value', () => {
        const result = mapper.map({
            experimentId: 'exp-html',
            body: '<digia command="SHOW_DIALOG" viewId="html_modal" screenId="profile"/>',
        });

        expect(result).toHaveLength(1);
        expect(result[0].content['command']).toBe('SHOW_DIALOG');
        expect(result[0].content['viewId']).toBe('html_modal');
    });

    it('extracts inline contract from <digia> tag', () => {
        const result = mapper.map({
            experimentId: 'exp-html-inline',
            body: '<digia type="inline" viewId="hero" placementKey="slot1" screenId="home"/>',
        });

        expect(result).toHaveLength(1);
        expect(result[0].content['type']).toBe('inline');
        expect(result[0].content['componentId']).toBe('hero');
        expect(result[0].content['placementKey']).toBe('slot1');
    });

    it('extracts contract from <script digia-payload> tag', () => {
        const scriptPayload = JSON.stringify({
            command: 'SHOW_BOTTOM_SHEET',
            viewId: 'sheet_view',
            screenId: 'cart',
        });
        const result = mapper.map({
            experimentId: 'exp-script',
            body: `<script type="application/json" digia-payload>${scriptPayload}</script>`,
        });

        expect(result).toHaveLength(1);
        expect(result[0].content['command']).toBe('SHOW_BOTTOM_SHEET');
        expect(result[0].content['viewId']).toBe('sheet_view');
    });

    it('top-level keys take precedence over embedded HTML contract', () => {
        const result = mapper.map({
            experimentId: 'exp-both',
            command: 'SHOW_DIALOG',
            viewId: 'top_level_modal',
            body: '<digia command="SHOW_BOTTOM_SHEET" viewId="html_modal"/>',
        });

        expect(result).toHaveLength(1);
        expect(result[0].content['viewId']).toBe('top_level_modal');
    });

    it('html-unescapes attribute values extracted from <digia> tag', () => {
        const result = mapper.map({
            experimentId: 'exp-esc',
            body: '<digia command="SHOW_DIALOG" viewId="modal&amp;v2" screenId="home"/>',
        });
        expect(result[0].content['viewId']).toBe('modal&v2');
    });
});

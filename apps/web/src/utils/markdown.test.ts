// @vitest-environment jsdom

import { describe, expect, it } from 'vitest';
import { renderMarkdown } from './markdown';

describe('renderMarkdown', () => {
  it('renders common markdown structures', () => {
    const html = renderMarkdown(
      [
        '# Title',
        '',
        'A [link](https://example.com) and a list:',
        '',
        '- first',
        '- second',
        '',
        '```ts',
        'const value = 1;',
        '```',
        '',
        '| a | b |',
        '| - | - |',
        '| 1 | 2 |',
      ].join('\n'),
    );

    expect(html).toContain('<h1>Title</h1>');
    expect(html).toContain('<a href="https://example.com" target="_blank" rel="noopener noreferrer">link</a>');
    expect(html).toContain('<ul>');
    expect(html).toContain('<pre><code class="language-ts">');
    expect(html).toContain('<table>');
  });

  it('sanitizes unsafe html and links', () => {
    const html = renderMarkdown(
      [
        '<img src="x" onerror="alert(1)">',
        '',
        '[bad](javascript:alert(1))',
        '',
        '<script>alert(1)</script>',
      ].join('\n'),
    );

    expect(html).toContain('<img src="x">');
    expect(html).not.toContain('onerror=');
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('javascript:alert(1)');
  });
});

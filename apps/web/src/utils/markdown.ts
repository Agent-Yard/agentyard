import DOMPurify from 'dompurify';
import { marked } from 'marked';

marked.setOptions({
  async: false,
  breaks: true,
  gfm: true,
});

export function renderMarkdown(source: string): string {
  if (!source.trim()) {
    return '';
  }

  const rendered = marked.parse(source) as string;
  const sanitized = DOMPurify.sanitize(rendered, {
    USE_PROFILES: { html: true },
  });

  if (typeof window === 'undefined' || typeof window.document === 'undefined') {
    return sanitized;
  }

  const template = window.document.createElement('template');
  template.innerHTML = sanitized;
  for (const link of template.content.querySelectorAll('a[href]')) {
    link.setAttribute('target', '_blank');
    link.setAttribute('rel', 'noopener noreferrer');
  }
  for (const table of template.content.querySelectorAll('table')) {
    const scroll = window.document.createElement('div');
    scroll.className = 'markdown-table-scroll';
    scroll.tabIndex = 0;
    scroll.setAttribute('role', 'region');
    scroll.setAttribute('aria-label', '表格，可横向滚动');
    table.replaceWith(scroll);
    scroll.append(table);
  }
  return template.innerHTML;
}

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
  return template.innerHTML;
}

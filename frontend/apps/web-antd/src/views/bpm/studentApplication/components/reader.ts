import MarkdownIt from 'markdown-it';
import DOMPurify from 'dompurify';
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: false, typographer: false });
markdown.renderer.rules.image = (tokens, index) =>
  markdown.utils.escapeHtml(tokens[index]?.content || '');
const defaultLink = markdown.renderer.rules.link_open;
markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  const token = tokens[index];
  if (!token) return '';
  token.attrSet('target', '_blank');
  token.attrSet('rel', 'noopener noreferrer');
  return defaultLink
    ? defaultLink(tokens, index, options, env, self)
    : self.renderToken(tokens, index, options);
};
// Attachments are untrusted: no remote images, active HTML, embedded frames or automatic link requests.
export function readableMarkdown(value: string) {
  return DOMPurify.sanitize(markdown.render(value), {
    FORCE_BODY: true,
    FORBID_TAGS: ['img', 'svg', 'math', 'script', 'iframe'],
    ALLOWED_TAGS: [
      'p',
      'br',
      'h1',
      'h2',
      'h3',
      'h4',
      'h5',
      'h6',
      'strong',
      'em',
      's',
      'ul',
      'ol',
      'li',
      'blockquote',
      'pre',
      'code',
      'hr',
      'table',
      'thead',
      'tbody',
      'tr',
      'th',
      'td',
      'a',
    ],
    ALLOWED_ATTR: ['href', 'title', 'start', 'target', 'rel'],
    ALLOW_DATA_ATTR: false,
  });
}
export interface ScriptAttachment {
  file_id: number;
  file_name: string;
  file_size: number;
}
export function fileKind(file: ScriptAttachment) {
  return file.file_name.split('.').pop()?.toLowerCase() || '';
}
export function formatFileSize(size: number) {
  return size < 1024 * 1024
    ? `${Math.max(1, Math.ceil(size / 1024))} KB`
    : `${(size / 1024 / 1024).toFixed(1)} MB`;
}

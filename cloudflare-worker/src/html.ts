/**
 * Tiny HTML utilities for Google Play parsing.
 *
 * Google Play pages are machine-generated; we avoid a full DOM dependency to keep
 * the worker dependency-free and fast. These helpers are deliberately defensive:
 * they never throw on malformed input.
 *
 * These helpers intentionally only see well-formed attribute fragments. Google's
 * own emitted HTML closes attributes with quotes, and the data we extract is always
 * short plain text (titles, labels, numbers) — the full long-form description is
 * taken from the itemprop content attribute, never from arbitrary markup.
 */

export function decodeHtmlEntities(text: string): string {
  return text
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;/g, "'")
    .replace(/&apos;/g, "'")
    .replace(/&#x27;/gi, "'")
    .replace(/&nbsp;/g, ' ')
    .replace(/&#(\d+);/g, (_m, d: string) => {
      const code = Number(d);
      return Number.isFinite(code) && code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : '';
    })
    .replace(/&#x([0-9a-fA-F]+);/g, (_m, h: string) => {
      const code = parseInt(h, 16);
      return Number.isFinite(code) && code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : '';
    })
    .replace(/&amp;/g, '&');
}

/** Extract attribute value for a given attribute name within one tag string. */
export function attr(tag: string, name: string): string | null {
  const re = new RegExp(`${name}\\s*=\\s*("([^"]*)"|'([^']*)')`, 'i');
  const m = tag.match(re);
  if (!m) return null;
  return decodeHtmlEntities(m[2] ?? m[3] ?? '');
}

/** Strip tags and collapse whitespace. */
export function textOf(htmlFragment: string): string {
  return decodeHtmlEntities(
    htmlFragment
      .replace(/<[^>]*>/g, ' ')
      .replace(/\s+/g, ' ')
      .trim(),
  );
}

/** True when the fragment appears to be an HTML document (not a consent page, not empty). */
export function looksLikeHtmlDocument(html: string): boolean {
  return html.includes('<html') && html.length > 2000;
}

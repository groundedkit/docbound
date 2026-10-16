/**
 * A deliberately small subset of Markdown for answers: paragraphs, "- " / "* " bullet lists, "1. " numbered lists
 * and **bold**. Anything else is shown as plain text, so raw model output can never inject HTML.
 */
export type Block =
  | { kind: 'paragraph'; lines: string[] }
  | { kind: 'bullets'; items: string[] }
  | { kind: 'numbered'; items: string[] }

const BULLET = /^\s*[-*•]\s+(.*)$/
const NUMBERED = /^\s*\d+[.)]\s+(.*)$/

export function parseBlocks(text: string): Block[] {
  const blocks: Block[] = []
  for (const line of text.split('\n')) {
    const bullet = BULLET.exec(line)
    const numbered = bullet ? null : NUMBERED.exec(line)
    const last = blocks.at(-1)
    if (bullet) {
      if (last?.kind === 'bullets') last.items.push(bullet[1])
      else blocks.push({ kind: 'bullets', items: [bullet[1]] })
    } else if (numbered) {
      if (last?.kind === 'numbered') last.items.push(numbered[1])
      else blocks.push({ kind: 'numbered', items: [numbered[1]] })
    } else if (line.trim() === '') {
      if (last && !(last.kind === 'paragraph' && last.lines.length === 0)) blocks.push({ kind: 'paragraph', lines: [] })
    } else if (last?.kind === 'paragraph') {
      last.lines.push(line)
    } else {
      blocks.push({ kind: 'paragraph', lines: [line] })
    }
  }
  return blocks.filter((block) => block.kind !== 'paragraph' || block.lines.length > 0)
}

export type InlinePart = { bold: boolean; text: string }

/** Splits **bold** spans out of a line. An unclosed ** (e.g. while an answer is still streaming) stays as text. */
export function splitBold(text: string): InlinePart[] {
  const parts: InlinePart[] = []
  let last = 0
  for (const match of text.matchAll(/\*\*(.+?)\*\*/g)) {
    if (match.index > last) parts.push({ bold: false, text: text.slice(last, match.index) })
    parts.push({ bold: true, text: match[1] })
    last = match.index + match[0].length
  }
  if (last < text.length) parts.push({ bold: false, text: text.slice(last) })
  return parts
}

/**
 * `policy_workplace_concerns.pdf` → `Policy Workplace Concerns`, matching the backend's DocumentTitles so the
 * document list and the answers name documents the same way. Mixed- or upper-case words (API, iOS) are kept.
 */
export function documentTitle(filename: string): string {
  const name = filename.trim().replace(/\.[^.]+$/, '')
  const words = name.split(/[\s_\-.]+/).filter(Boolean)
  if (words.length === 0) return filename
  return words.map((word) => (word === word.toLowerCase() ? word[0].toUpperCase() + word.slice(1) : word)).join(' ')
}

const LIST_ITEM = /^\s*([-*•●▪◦]|\d+[.)])\s+/

/**
 * Text extracted from PDFs breaks lines wherever the page did. For reading a passage, joins those wrapped lines
 * back into paragraphs, keeping blank lines and list items (bullets, numbers) on their own lines.
 */
export function tidyPassage(text: string): string[] {
  const paragraphs: string[] = []
  let current = ''
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (line === '') {
      if (current) paragraphs.push(current)
      current = ''
    } else if (LIST_ITEM.test(line) && current) {
      paragraphs.push(current)
      current = line
    } else {
      current = current ? `${current} ${line}` : line
    }
  }
  if (current) paragraphs.push(current)
  return paragraphs.map((p) => p.replace(/\(\s+/g, '(').replace(/\s+\)/g, ')').replace(/\s{2,}/g, ' '))
}

/** A file's type as a short tag for the document list, e.g. "PDF". */
export function fileTag(filename: string): string {
  const extension = /\.([^.]+)$/.exec(filename)?.[1]?.toLowerCase() ?? ''
  return { markdown: 'MD', htm: 'HTML' }[extension] ?? (extension.toUpperCase() || 'FILE')
}

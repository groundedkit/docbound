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
